package com.sal7one.hearth.desktop.service

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.service.ServiceLimits
import com.sal7one.hearth.desktop.data.DesktopSettingsStore
import com.sal7one.hearth.desktop.media.DesktopMediaDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Another host already serves this data folder. */
class ServiceAlreadyRunning : IllegalStateException("A Hearth service host is already running for this data folder")

/**
 * The headless Hearth service (docs/service-api.md): a small supervisor that answers a
 * loopback API and runs engines in one worker process. It loads no model itself, so its
 * capabilities answer from memory and ending the worker returns everything to the computer.
 */
class DesktopServiceHost(
    private val dataDirectory: Path,
    private val launcher: WorkerLauncher,
    private val savedConfiguration: suspend () -> PipelineConfig = { readSavedConfiguration(dataDirectory) },
    // The decoder is unpacked once beside the service, not into every job's folder.
    private val decode: suspend (input: Path, directory: Path) -> Path = { input, directory ->
        DesktopMediaDecoder.decode(input, directory, helperDirectory = ServiceDirectory(dataDirectory).root)
    },
    private val idleUnloadSeconds: Long = 120,
    private val routeRefreshMillis: Long = 10_000,
) : AutoCloseable {
    private val service = ServiceDirectory(dataDirectory)
    private val log = ServiceLog(service.log)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = CountDownLatch(1)
    private val closing = AtomicBoolean()
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null
    private var worker: EngineWorker? = null
    private var jobs: JobManager? = null
    private var http: ServiceHttp? = null
    private lateinit var measurements: ServiceMeasurements
    @Volatile private var engines: List<EngineDescriptor> = emptyList()
    @Volatile private var routes: Map<String, RouteView> = emptyMap()
    private var unreadableSetups: Map<String, String> = emptyMap()

    val port: Int get() = checkNotNull(http) { "The service host is not started" }.port

    /** Starts listening and returns the loopback port. Fails loudly: a host that cannot list its engines serves nothing. */
    fun start(): Int {
        service.create()
        val channel = FileChannel.open(service.lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        // Another process holding the lock yields null; a second host in this process throws instead.
        lock = (try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }) ?: run { channel.close(); throw ServiceAlreadyRunning() }
        lockChannel = channel
        try {
            measurements = ServiceMeasurements(service.measurements)
            val engineWorker = EngineWorker(service, launcher, log).also { worker = it }
            // The worker lists the engines so no native library is ever loaded in this process; it is then ended, so start-up holds nothing.
            engines = engineWorker.capabilities()
            engineWorker.release()
            runBlocking { refreshRoutes() }
            val manager = JobManager(service, engineWorker, { routes }, measurements, log, decode, idleUnloadSeconds).also { jobs = it }
            val server = ServiceHttp(ServiceClients(service.clients), manager, ::capabilities, log, ::requestStop).also { http = it }
            server.start()
            PrivateFiles.write(service.descriptor, buildJsonObject {
                put("protocol", ServiceLimits.PROTOCOL); put("port", server.port); put("pid", ProcessHandle.current().pid()); put("started", System.currentTimeMillis() / 1000)
                System.getProperty("jpackage.app-path")?.let { put("executable", it) }
            }.toString().toByteArray(Charsets.UTF_8))
            scope.launch {
                while (true) {
                    delay(routeRefreshMillis)
                    runCatching { refreshRoutes() }.onFailure { log.event("routes-refresh-failed", "kind" to it.javaClass.simpleName) }
                }
            }
            log.event("host-started", "port" to server.port, "routes" to routes.values.count { it.ready })
            return server.port
        } catch (failure: Throwable) {
            log.failure("host-start-failed", failure)
            close()
            throw failure
        }
    }

    /** Blocks until a stop was requested through the API or [close]. */
    fun awaitStop() = stopped.await()

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        runCatching { http?.close() }
        runCatching { jobs?.close() }
        runCatching { worker?.close() }
        scope.cancel()
        runCatching { Files.deleteIfExists(service.descriptor) }
        runCatching { lock?.release() }
        runCatching { lockChannel?.close() }
        log.event("host-stopped")
        stopped.countDown()
    }

    /** The answer is sent first; shutdown then runs off the request thread. */
    private fun requestStop() { Thread({ Thread.sleep(100); close() }, "hearth-service-stop").start() }

    private suspend fun refreshRoutes() {
        val views = SavedRoutes.from(savedConfiguration(), engines).toMutableList()
        val unreadable = mutableMapOf<String, String>()
        for ((name, setup) in ServiceSetups.load(service.setups)) {
            setup.onSuccess { views += SavedRoutes.from(it, engines, name) }.onFailure { unreadable[name] = it.javaClass.simpleName }
        }
        // Said once when it changes, not on every refresh.
        if (unreadable != unreadableSetups) {
            unreadable.forEach { (name, kind) -> log.event("setup-unreadable", "setup" to name, "kind" to kind) }
            unreadableSetups = unreadable
        }
        routes = views.associateBy { it.route.id }
    }

    /** Built from memory only: the engine list read at start, the last route refresh and the measurements. */
    private fun capabilities(): JsonObject {
        val manager = jobs
        val resident = manager?.runtime()?.resident.orEmpty().toSet()
        return buildJsonObject {
            put("protocol", ServiceLimits.PROTOCOL)
            putJsonArray("routes") {
                for (view in routes.values) {
                    if (!view.ready) continue
                    val route = view.route
                    val measured = measurements.get(route)
                    val selections = listOfNotNull(route.config.speech?.let { "speech" to it }, route.config.translation?.let { "translation" to it }, route.config.voice?.let { "voice" to it })
                    val cpuOnly = selections.all { (it.second.options["acceleration"] ?: "cpu") == "cpu" }
                    add(buildJsonObject {
                        put("id", route.id); put("operation", route.operation.wire)
                        put("setup", view.setup?.let(::JsonPrimitive) ?: JsonNull)
                        putJsonArray("stages") {
                            for ((stage, selection) in selections) add(buildJsonObject {
                                put("stage", stage); put("engine", selection.engineId); put("model", selection.modelId); put("acceleration", selection.options["acceleration"] ?: "cpu")
                            })
                        }
                        putJsonArray("sourceLanguages") { route.sourceLanguages.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("targetLanguages") { route.targetLanguages.forEach { add(JsonPrimitive(it)) } }
                        putJsonArray("voices") { route.voices.forEach { add(JsonPrimitive(it)) } }
                        putJsonObject("limits") { put("maxAudioSeconds", route.maxAudioSeconds); put("maxTextCharacters", route.maxTextCharacters) }
                        put("speed", measured?.secondsPerWorkSecond?.let { buildJsonObject { put("secondsPerAudioSecond", it); put("measured", measured.measured) } } ?: JsonNull)
                        put("memory", measured?.ramBytes?.let { bytes ->
                            // Video memory is zero on CPU; a GPU route reports null until Hearth can measure it.
                            buildJsonObject { put("ramBytes", bytes); put("vramBytes", if (cpuOnly) JsonPrimitive(0) else JsonNull); put("measured", measured.measured) }
                        } ?: JsonNull)
                        put("resident", route.id in resident)
                    })
                }
            }
            putJsonArray("inputTypes") { JobManager.INPUT_TYPES.keys.forEach { add(JsonPrimitive(it)) } }
            putJsonObject("limits") {
                put("maxInputBytes", ServiceLimits.MAX_INPUT_BYTES); put("maxTextCharacters", ServiceLimits.MAX_TEXT_CHARACTERS)
                put("maxAudioSeconds", ServiceLimits.MAX_AUDIO_SECONDS); put("maxOutputBytes", ServiceLimits.MAX_OUTPUT_BYTES); put("runningJobs", 1); put("queuedJobs", 0)
                put("jobLifetimeSeconds", ServiceLimits.JOB_LIFETIME_SECONDS); put("idleUnloadSeconds", idleUnloadSeconds)
            }
        }
    }

    companion object {
        /** Exit code of a second `--serve` for the same data folder. */
        const val EXIT_ALREADY_RUNNING = 3

        /** Reads what the owner saved in the app. Read-only, so it is safe while the app itself is open. */
        suspend fun readSavedConfiguration(dataDirectory: Path): PipelineConfig {
            val store = DesktopSettingsStore(dataDirectory)
            return try { store.load() } finally { store.closeAndJoin() }
        }

        /** `Hearth --serve`: runs until stopped. Returns the process exit code. */
        fun serve(dataDirectory: Path, launcher: WorkerLauncher): Int {
            val host = DesktopServiceHost(dataDirectory, launcher)
            try { host.start() } catch (_: ServiceAlreadyRunning) { return EXIT_ALREADY_RUNNING } catch (_: Exception) { return 1 }
            Runtime.getRuntime().addShutdownHook(Thread({ host.close() }, "hearth-service-shutdown"))
            host.awaitStop()
            return 0
        }

        /** The owner's explicit approval of a client: creates its token file if it has none. Returns the file. */
        fun approveClient(dataDirectory: Path, name: String): Path {
            val service = ServiceDirectory(dataDirectory).also { it.create() }
            return ServiceClients(service.clients).approve(name)
        }

        fun revokeClient(dataDirectory: Path, name: String): Boolean = ServiceClients(ServiceDirectory(dataDirectory).clients).revoke(name)

        /**
         * Keeps what the app is showing now as a named setup for callers, so the owner can serve
         * more than one language pair. A running host offers its routes at the next refresh. Returns the file.
         */
        suspend fun saveSetup(dataDirectory: Path, name: String): Path {
            val service = ServiceDirectory(dataDirectory).also { it.create() }
            return ServiceSetups.save(service.setups, name, readSavedConfiguration(dataDirectory))
        }

        fun removeSetup(dataDirectory: Path, name: String): Boolean = ServiceSetups.remove(ServiceDirectory(dataDirectory).setups, name)
    }
}

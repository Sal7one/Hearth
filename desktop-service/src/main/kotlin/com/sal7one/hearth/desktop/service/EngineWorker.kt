package com.sal7one.hearth.desktop.service

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.service.ServiceErrorCode
import com.sal7one.hearth.core.service.ServiceException
import com.sal7one.hearth.desktop.service.WorkerMessages.text
import kotlinx.serialization.json.jsonArray
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Starts the engine worker process for a socket it must connect to. */
fun interface WorkerLauncher { fun launch(socket: Path): Process }

/** Re-runs this program as the worker: the packaged launcher, or the same JVM and class path in development. */
class SelfWorkerLauncher(private val mainClass: String) : WorkerLauncher {
    override fun launch(socket: Path): Process {
        val packaged = System.getProperty("jpackage.app-path")
        val command = if (packaged != null) listOf(packaged, WORKER_ARGUMENT, socket.toString()) else buildList {
            add(Path.of(System.getProperty("java.home"), "bin", "java").toString())
            System.getProperty("hearth.native.dir")?.let { add("-Dhearth.native.dir=$it") }
            add("-cp"); add(System.getProperty("java.class.path")); add(mainClass); add(WORKER_ARGUMENT); add(socket.toString())
        }
        // Engine libraries write diagnostics to their standard streams; none of it may reach a log.
        return ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            .also { it.outputStream.close() }
    }

    companion object { const val WORKER_ARGUMENT = "--serve-worker" }
}

internal data class Released(val worker: Boolean, val ramBytes: Long?)

/**
 * The host's handle on the one engine worker process. Models live there, so [release] and a
 * timed-out [cancel] end that process: the only way memory returns to the computer at once
 * and an engine call that cannot be interrupted still stops.
 */
internal class EngineWorker(
    private val service: ServiceDirectory,
    private val launcher: WorkerLauncher,
    private val log: ServiceLog,
    private val startTimeoutMillis: Long = 30_000,
) : AutoCloseable {
    private class Live(val process: Process, val channel: WorkerChannel)
    private class Active(val key: String) { val finished = CountDownLatch(1); @Volatile var cancelRequested = false }

    /** Held by whoever is reading the worker's replies: one job, or one capabilities exchange. */
    private val use = ReentrantLock()
    @Volatile private var live: Live? = null
    @Volatile private var active: Active? = null
    /** The selections last run successfully per stage; a route is resident only when all of its stages match. */
    @Volatile private var resident: PipelineConfig = PipelineConfig()

    val running: Boolean get() = live?.process?.isAlive == true

    fun capabilities(): List<EngineDescriptor> {
        use.lock()
        try {
            val worker = ensure()
            worker.channel.send(WorkerMessages.simple("capabilities"))
            val reply = worker.channel.receive() ?: run { discard(worker); throw ServiceException(ServiceErrorCode.ENGINE_FAILED, "The engine worker stopped while listing engines") }
            return WorkerMessages.engines(reply.getValue("engines").jsonArray)
        } finally { use.unlock() }
    }

    /**
     * Runs one job to its end. Blocking; call from an I/O thread. [stopped] is the owner's own
     * record that the job was stopped: together with [cancel] it closes the window in which a
     * stop arrives before this call has announced the job or while the worker is still starting.
     */
    fun run(job: WorkerJob, stopped: () -> Boolean = { false }): WorkerOutcome {
        use.lock()
        val current = Active(job.key)
        active = current
        try {
            if (stopped()) return WorkerOutcome.Cancelled
            val worker = ensure()
            if (current.cancelRequested || stopped()) return WorkerOutcome.Cancelled
            worker.channel.send(WorkerMessages.run(job))
            while (true) {
                val message = worker.channel.receive()
                if (message == null) {
                    discard(worker)
                    return if (current.cancelRequested) WorkerOutcome.Cancelled else WorkerOutcome.Failed(ServiceErrorCode.ENGINE_FAILED, "The engine worker stopped unexpectedly")
                }
                if (message.text("type") != "done" || message.text("key") != job.key) continue
                val outcome = WorkerMessages.outcome(message)
                resident = if (outcome is WorkerOutcome.Completed) merge(resident, job.config) else forget(resident, job.config)
                return outcome
            }
        } catch (failure: IOException) {
            live?.let(::discard)
            return if (current.cancelRequested) WorkerOutcome.Cancelled else WorkerOutcome.Failed(ServiceErrorCode.ENGINE_FAILED, "The engine worker connection failed")
        } finally {
            active = null
            current.finished.countDown()
            use.unlock()
        }
    }

    /**
     * Stops the named job. The engine gets [graceMillis] to stop by itself; after that the worker
     * process is ended, so this returns within a bound whatever the engine is doing.
     */
    fun cancel(key: String, graceMillis: Long) {
        val current = active?.takeIf { it.key == key } ?: return
        current.cancelRequested = true
        live?.let { worker -> runCatching { worker.channel.send(WorkerMessages.simple("cancel", key)) } }
        if (!current.finished.await(graceMillis, TimeUnit.MILLISECONDS)) {
            log.event("worker-killed", "job" to key)
            live?.let(::discard)
            current.finished.await(2, TimeUnit.SECONDS)
        }
    }

    /** Ends the worker and reports what it held just before. Refused while a job is running. */
    fun release(graceMillis: Long = 5_000): Released {
        if (!use.tryLock(1, TimeUnit.SECONDS)) throw ServiceException(ServiceErrorCode.BUSY, "A job is running")
        try {
            val worker = live ?: return Released(worker = false, ramBytes = 0)
            val bytes = ProcessMemory.treeBytes(worker.process.toHandle())
            runCatching { worker.channel.send(WorkerMessages.simple("exit")) }
            if (!worker.process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) log.event("worker-killed", "reason" to "release")
            discard(worker)
            return Released(worker = true, ramBytes = bytes)
        } finally { use.unlock() }
    }

    fun memoryBytes(): Long? = live?.process?.takeIf { it.isAlive }?.let { ProcessMemory.treeBytes(it.toHandle()) }

    fun isResident(config: PipelineConfig): Boolean = running &&
        listOf(config.speech to resident.speech, config.translation to resident.translation, config.voice to resident.voice)
            .all { (wanted, loaded) -> wanted == null || wanted == loaded }

    override fun close() { live?.let(::discard) }

    private fun ensure(): Live {
        live?.takeIf { it.process.isAlive }?.let { return it }
        live?.let(::discard)
        return start().also { live = it }
    }

    private fun start(): Live {
        val socket = socketPath()
        Files.deleteIfExists(socket)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            server.configureBlocking(false)
            val process = launcher.launch(socket)
            try {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startTimeoutMillis)
                var connection: SocketChannel? = null
                while (connection == null) {
                    connection = server.accept()
                    if (connection != null) break
                    if (!process.isAlive) throw ServiceException(ServiceErrorCode.ENGINE_FAILED, "The engine worker exited during start-up (${process.exitValue()})")
                    if (System.nanoTime() > deadline) throw ServiceException(ServiceErrorCode.ENGINE_FAILED, "The engine worker did not start within ${startTimeoutMillis / 1000} s")
                    Thread.sleep(20)
                }
                connection!!.configureBlocking(true)
                val channel = WorkerChannel(connection)
                if (channel.receive()?.text("type") != "ready") throw ServiceException(ServiceErrorCode.ENGINE_FAILED, "The engine worker did not report ready")
                log.event("worker-started", "pid" to process.pid())
                return Live(process, channel)
            } catch (failure: Throwable) {
                destroyTree(process)
                throw failure
            } finally { Files.deleteIfExists(socket) }
        }
    }

    private fun discard(worker: Live) {
        worker.channel.close()
        destroyTree(worker.process)
        if (live === worker) live = null
        resident = PipelineConfig()
    }

    /** The packaged launcher starts the JVM as a child; both must go. */
    private fun destroyTree(process: Process) {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        runCatching { process.waitFor(5, TimeUnit.SECONDS) }
    }

    /** Socket paths are short by rule of the platform; a long data folder falls back to the user's temporary folder. */
    private fun socketPath(): Path {
        val name = "w-" + ByteArray(6).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) } + ".sock"
        val preferred = service.root.resolve(name)
        return if (preferred.toString().toByteArray(Charsets.UTF_8).size <= 96) preferred else Path.of(System.getProperty("java.io.tmpdir")).resolve("hearth-$name")
    }

    private fun merge(base: PipelineConfig, used: PipelineConfig) = base.copy(
        speech = used.speech ?: base.speech, translation = used.translation ?: base.translation, voice = used.voice ?: base.voice)

    private fun forget(base: PipelineConfig, used: PipelineConfig): PipelineConfig {
        fun keep(current: EngineSelection?, touched: EngineSelection?) = if (touched != null) null else current
        return base.copy(speech = keep(base.speech, used.speech), translation = keep(base.translation, used.translation), voice = keep(base.voice, used.voice))
    }
}

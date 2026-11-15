package com.sal7one.hearth.desktop.service

import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.service.ServiceContract
import com.sal7one.hearth.core.service.ServiceErrorCode
import com.sal7one.hearth.core.service.ServiceException
import com.sal7one.hearth.core.service.ServiceJobRequest
import com.sal7one.hearth.core.service.ServiceJobState
import com.sal7one.hearth.core.service.ServiceLimits
import com.sal7one.hearth.core.service.ServiceOperation
import com.sal7one.hearth.core.service.ServiceRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal data class JobRecord(
    val key: String,
    val route: String,
    val operation: ServiceOperation,
    val state: ServiceJobState,
    val created: Long,
    val started: Long? = null,
    val finished: Long? = null,
    val inputAudioSeconds: Double? = null,
    val estimatedSeconds: Int? = null,
    val summary: JsonObject? = null,
    val outputs: List<JobOutput> = emptyList(),
    val error: Pair<ServiceErrorCode, String>? = null,
)

internal data class RuntimeView(val busy: String?, val resident: List<String>, val ramBytes: Long?, val idleSeconds: Long, val idleUnloadSeconds: Long, val workerRunning: Boolean)

/**
 * One job at a time, keyed by the caller. Creation is idempotent, every state change is made
 * under one lock, and [forget] leaves nothing of a job on disk or in memory but its key.
 */
internal class JobManager(
    private val service: ServiceDirectory,
    private val worker: EngineWorker,
    private val routes: () -> Map<String, RouteView>,
    private val measurements: ServiceMeasurements,
    private val log: ServiceLog,
    private val decode: suspend (input: Path, directory: Path) -> Path,
    private val idleUnloadSeconds: Long = 120,
    private val inputTimeoutSeconds: Long = 120,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : AutoCloseable {
    private class Entry(var record: JobRecord, val request: ServiceJobRequest, val route: ServiceRoute, val config: PipelineConfig) {
        val fingerprint = request.fingerprint()
        var work: Job? = null
        var timers = mutableListOf<Job>()
        var inputDigest: String? = null
        var receiving = false
        var stopReason: String? = null
    }

    private val lock = Any()
    private val jobs = LinkedHashMap<String, Entry>()
    private val forgotten = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > 4096
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastActivity = clock()

    init {
        // Whatever a previous host left behind is not a job any more.
        deleteTree(service.jobs); PrivateFiles.directory(service.jobs)
        ServiceWorker.purgeGeneratedAudio(service.root.resolve("engine"))
        scope.launch {
            while (true) {
                delay(5_000)
                runCatching { expire(); unloadWhenIdle() }
            }
        }
    }

    /** Returns the job and whether this call created it. */
    fun create(key: String, request: ServiceJobRequest): Pair<JobRecord, Boolean> {
        ServiceContract.requireKey(key)
        val entry = synchronized(lock) {
            jobs[key]?.let { existing ->
                if (existing.fingerprint == request.fingerprint()) return existing.record to false
                throw ServiceException(ServiceErrorCode.KEY_CONFLICT, "Job $key exists with a different request")
            }
            if (key in forgotten) throw ServiceException(ServiceErrorCode.KEY_CONFLICT, "Job $key was already used and forgotten")
            val view = routes()[request.route] ?: throw ServiceException(ServiceErrorCode.UNKNOWN_ROUTE, "No route named ${request.route.take(64)}")
            view.unavailable?.let { throw ServiceException(ServiceErrorCode.ROUTE_NOT_READY, it) }
            val config = ServiceContract.resolve(request, view.route)
            jobs.values.firstOrNull { !it.record.state.terminal }?.let { throw ServiceException(ServiceErrorCode.BUSY, "Job ${it.record.key} is active") }
            if (request.requireResident && !worker.isResident(config)) throw ServiceException(ServiceErrorCode.NOT_RESIDENT, "Route ${view.route.id} is not loaded")
            val audio = view.route.operation.takesAudio
            Entry(JobRecord(key, view.route.id, view.route.operation, if (audio) ServiceJobState.AWAITING_INPUT else ServiceJobState.QUEUED, clock()), request, view.route, config).also { created ->
                jobs[key] = created
                request.deadlineSeconds?.let { seconds -> created.timers += scope.launch { delay(seconds * 1000L); stop(key, "The job passed its deadline") } }
                if (audio) created.timers += scope.launch {
                    delay(inputTimeoutSeconds * 1000)
                    if (synchronized(lock) { created.record.state == ServiceJobState.AWAITING_INPUT && !created.receiving }) stop(key, "No input was uploaded")
                } else created.work = scope.launch { execute(created, audio = null, workSeconds = null) }
            }
        }
        lastActivity = clock()
        log.event("job-created", "job" to key, "route" to entry.route.id)
        return entry.record to true
    }

    /** Stores the job's audio. A repeat with the same digest is a no-op; the caller always sends the whole body. */
    fun input(key: String, contentType: String?, digest: String?, length: Long, body: InputStream): JobRecord {
        ServiceContract.requireKey(key)
        val extension = INPUT_TYPES[contentType?.substringBefore(';')?.trim()?.lowercase()]
            ?: throw ServiceException(ServiceErrorCode.UNSUPPORTED_MEDIA, "Unsupported input type; send one of ${INPUT_TYPES.keys.joinToString()}")
        if (digest == null || !digest.matches(SHA256)) throw ServiceException(ServiceErrorCode.INVALID_REQUEST, "X-Content-SHA256 must be the upload's lower-case SHA-256")
        if (length < 1) throw ServiceException(ServiceErrorCode.INVALID_REQUEST, "Content-Length is required")
        if (length > ServiceLimits.MAX_INPUT_BYTES) throw ServiceException(ServiceErrorCode.INPUT_TOO_LARGE, "An upload is at most ${ServiceLimits.MAX_INPUT_BYTES} bytes")
        val entry = synchronized(lock) {
            val found = jobs[key] ?: throw ServiceException(ServiceErrorCode.UNKNOWN_JOB, "No job $key")
            if (!found.route.operation.takesAudio) throw ServiceException(ServiceErrorCode.INVALID_REQUEST, "Job $key takes text, not an upload")
            found.inputDigest?.let { stored ->
                if (stored == digest) return found.record
                throw ServiceException(ServiceErrorCode.KEY_CONFLICT, "Job $key already has a different input")
            }
            if (found.record.state != ServiceJobState.AWAITING_INPUT || found.receiving) throw ServiceException(ServiceErrorCode.INVALID_REQUEST, "Job $key is not waiting for input")
            found.receiving = true
            found
        }
        val directory = PrivateFiles.directory(service.jobs.resolve(key))
        val target = directory.resolve("input.$extension")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = body.read(buffer); if (count < 0) break
                    total += count
                    if (total > length) throw ServiceException(ServiceErrorCode.INVALID_REQUEST, "The upload is longer than its Content-Length")
                    hash.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            }
            PrivateFiles.restrict(target, directory = false)
            if (total != length) throw ServiceException(ServiceErrorCode.INVALID_REQUEST, "The upload ended before its Content-Length")
            if (hash.digest().joinToString("") { "%02x".format(it) } != digest) throw ServiceException(ServiceErrorCode.INVALID_REQUEST, "The upload does not match X-Content-SHA256")
        } catch (failure: Throwable) {
            Files.deleteIfExists(target)
            synchronized(lock) { entry.receiving = false }
            throw failure
        }
        synchronized(lock) {
            entry.receiving = false
            if (jobs[key] !== entry || entry.record.state != ServiceJobState.AWAITING_INPUT) {
                deleteTree(directory)
                throw ServiceException(ServiceErrorCode.UNKNOWN_JOB, "Job $key ended while its input was uploading")
            }
            entry.inputDigest = digest
            entry.record = entry.record.copy(state = ServiceJobState.QUEUED)
            entry.work = scope.launch { prepare(entry, target, directory) }
        }
        lastActivity = clock()
        log.event("job-input", "job" to key, "bytes" to length, "type" to extension)
        return entry.record
    }

    fun get(key: String): JobRecord {
        ServiceContract.requireKey(key)
        return synchronized(lock) { jobs[key]?.record } ?: throw ServiceException(ServiceErrorCode.UNKNOWN_JOB, "No job $key")
    }

    /** The file behind one of a completed job's outputs. Only names the record lists resolve. */
    fun output(key: String, name: String): Pair<Path, JobOutput> {
        val output = get(key).outputs.firstOrNull { it.name == name } ?: throw ServiceException(ServiceErrorCode.UNKNOWN_JOB, "Job $key has no output named ${name.take(64)}")
        return service.jobs.resolve(key).resolve("out").resolve(output.name) to output
    }

    /** Cancels, waits for the engine to stop (ending the worker if it will not), and deletes everything. Idempotent. */
    fun forget(key: String) {
        ServiceContract.requireKey(key)
        val entry = synchronized(lock) { jobs[key]?.also { it.stopReason = it.stopReason ?: "The job was forgotten" } }
        if (entry != null) {
            worker.cancel(key, CANCEL_GRACE_MILLIS)
            entry.work?.cancel(); entry.timers.forEach(Job::cancel)
            synchronized(lock) { jobs.remove(key); forgotten[key] = clock() }
            log.event("job-forgotten", "job" to key, "state" to entry.record.state.wire)
        }
        purge(service.jobs.resolve(key))
        lastActivity = clock()
    }

    fun runtime(): RuntimeView {
        val busy = synchronized(lock) { jobs.values.firstOrNull { !it.record.state.terminal }?.record?.key }
        return RuntimeView(busy, residentRoutes(), worker.memoryBytes(), (clock() - lastActivity).coerceAtLeast(0), idleUnloadSeconds, worker.running)
    }

    /** Ends the worker and reports the routes that were loaded and the memory it held. Refused while a job is active. */
    fun release(): Pair<List<String>, Released> {
        synchronized(lock) { jobs.values.firstOrNull { !it.record.state.terminal } }?.let { throw ServiceException(ServiceErrorCode.BUSY, "Job ${it.record.key} is active") }
        val loaded = residentRoutes()
        val released = worker.release()
        log.event("released", "routes" to loaded.size, "ramBytes" to released.ramBytes)
        return loaded to released
    }

    override fun close() {
        val active = synchronized(lock) { jobs.keys.toList() }
        active.forEach { key -> runCatching { forget(key) } }
        scope.cancel()
        runCatching { worker.release(graceMillis = 3_000) }
    }

    private fun residentRoutes(): List<String> = routes().values.filter { it.ready && worker.isResident(it.route.config) }.map { it.route.id }

    private suspend fun prepare(entry: Entry, input: Path, directory: Path) {
        val wave = try { decode(input, directory) } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
            finish(entry, ServiceJobState.FAILED, error = ServiceErrorCode.UNSUPPORTED_MEDIA to (failure.message ?: "The audio could not be decoded"))
            return
        }
        // The decoder writes 16 kHz mono PCM16; its length is the audio's duration.
        val seconds = (Files.size(wave) - 44).coerceAtLeast(0) / 32_000.0
        try {
            ServiceContract.requireAudioLength(seconds, entry.route)
            val estimate = ServiceContract.estimateSeconds(seconds, measurements.get(entry.route)?.secondsPerWorkSecond)
            synchronized(lock) { entry.record = entry.record.copy(inputAudioSeconds = seconds, estimatedSeconds = estimate) }
            ServiceContract.requireWithinDeadline(estimate, entry.request.deadlineSeconds?.let { (it - (clock() - entry.record.created)).toInt().coerceAtLeast(0) })
        } catch (refusal: ServiceException) {
            finish(entry, ServiceJobState.FAILED, error = refusal.code to (refusal.message ?: refusal.code.wire))
            return
        }
        execute(entry, wave.toString(), seconds)
    }

    private fun execute(entry: Entry, audio: String?, workSeconds: Double?) {
        val key = entry.record.key
        val proceed = synchronized(lock) {
            if (entry.stopReason != null || jobs[key] !== entry) false
            else { entry.record = entry.record.copy(state = ServiceJobState.RUNNING, started = clock()); true }
        }
        if (!proceed) return
        val begun = System.nanoTime()
        val outcome = try {
            worker.run(WorkerJob(key, entry.route.operation, entry.config, audio, entry.request.text, service.jobs.resolve(key).resolve("out").toString())) {
                synchronized(lock) { entry.stopReason != null || jobs[key] !== entry }
            }
        } catch (failure: ServiceException) { WorkerOutcome.Failed(failure.code, failure.message ?: failure.code.wire) }
        val elapsed = (System.nanoTime() - begun) / 1e9
        when (outcome) {
            is WorkerOutcome.Completed -> {
                val spoken = outcome.summary["audioSeconds"]?.jsonPrimitive?.doubleOrNull
                val work = workSeconds ?: spoken.takeIf { entry.route.operation == ServiceOperation.SPEAK }
                measurements.record(entry.route, work?.takeIf { it > 0 }?.let { elapsed / it }, worker.memoryBytes(), clock())
                val summary = if (workSeconds != null) JsonObject(outcome.summary + ("audioSeconds" to JsonPrimitive(workSeconds))) else outcome.summary
                finish(entry, ServiceJobState.COMPLETED, summary = summary, outputs = outcome.outputs)
            }
            is WorkerOutcome.Failed -> finish(entry, ServiceJobState.FAILED, error = outcome.code to outcome.message)
            WorkerOutcome.Cancelled -> finish(entry, ServiceJobState.CANCELLED, error = ServiceErrorCode.CANCELLED to (entry.stopReason ?: "The job was cancelled"))
        }
    }

    private fun finish(entry: Entry, state: ServiceJobState, summary: JsonObject? = null, outputs: List<JobOutput> = emptyList(), error: Pair<ServiceErrorCode, String>? = null) {
        val key = entry.record.key
        synchronized(lock) {
            if (jobs[key] !== entry || entry.record.state.terminal) return
            entry.record = entry.record.copy(state = state, finished = clock(), summary = summary, outputs = outputs, error = error?.let { it.first to it.second.take(2_000) })
            entry.timers.forEach(Job::cancel)
        }
        // Inputs are needed only while the job runs; outputs stay until the caller forgets the job.
        runCatching {
            Files.newDirectoryStream(service.jobs.resolve(key)).use { files -> files.filter { Files.isRegularFile(it) }.forEach { Files.deleteIfExists(it) } }
        }
        lastActivity = clock()
        log.event("job-finished", "job" to key, "state" to state.wire, "code" to error?.first?.wire)
    }

    /** A deadline or a missing upload stops a job the same way a caller's forget does, but keeps its record. */
    private fun stop(key: String, reason: String) {
        val entry = synchronized(lock) { jobs[key]?.takeIf { !it.record.state.terminal }?.also { it.stopReason = it.stopReason ?: reason } } ?: return
        worker.cancel(key, CANCEL_GRACE_MILLIS)
        entry.work?.cancel()
        finish(entry, ServiceJobState.CANCELLED, error = ServiceErrorCode.CANCELLED to (entry.stopReason ?: reason))
    }

    private fun expire() {
        val now = clock()
        val old = synchronized(lock) { jobs.values.filter { it.record.state.terminal && now - (it.record.finished ?: now) >= ServiceLimits.JOB_LIFETIME_SECONDS }.map { it.record.key } }
        old.forEach(::forget)
    }

    private fun unloadWhenIdle() {
        val idle = synchronized(lock) { jobs.values.none { !it.record.state.terminal } }
        if (idle && worker.running && clock() - lastActivity >= idleUnloadSeconds) {
            val released = runCatching { worker.release() }.getOrNull() ?: return
            log.event("idle-unload", "ramBytes" to released.ramBytes)
        }
    }

    /** A killed worker or decoder releases its files a moment later on Windows. */
    private fun purge(directory: Path) {
        repeat(20) {
            if (runCatching { deleteTree(directory) }.isSuccess && !Files.exists(directory)) return
            Thread.sleep(50)
        }
        deleteTree(directory)
    }

    companion object {
        const val CANCEL_GRACE_MILLIS = 3_000L
        private val SHA256 = Regex("[0-9a-f]{64}")
        /** Containers the operating system decodes; the extension tells the decoder what it is reading. */
        val INPUT_TYPES = linkedMapOf(
            "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/aac" to "aac",
            "audio/mpeg" to "mp3", "video/mp4" to "mp4", "video/quicktime" to "mov")
    }
}

package com.sal7one.hearth.desktop.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ScreenCaptureKit system audio only. A single collector owns the helper process and joins it on
 * cancellation. Native buffering is sixteen 100 ms frames; the JVM boundary is a rendezvous,
 * plus one in-flight frame and the operating system's bounded pipe. No microphone is opened.
 */
class DesktopSystemAudioRecorder private constructor(private val launch: () -> Process) : Closeable {
    constructor(dataDirectory: Path) : this({
        check(available) { availabilityReason ?: "Native macOS system audio capture is unavailable" }
        ProcessBuilder(SystemAudioHelper.executable(dataDirectory).toString(), "capture", ProcessHandle.current().pid().toString()).start()
    })
    internal constructor(launch: () -> Process, @Suppress("UNUSED_PARAMETER") test: Unit) : this(launch)

    private enum class Stop { NONE, FINISH, CANCEL }
    private val started = AtomicBoolean()
    private val stop = AtomicReference(Stop.NONE)
    private val process = AtomicReference<Process?>()
    private val producer = AtomicReference<Thread?>()
    private val events = Channel<ByteArray>(Channel.RENDEZVOUS)
    private val done = CompletableDeferred<Unit>()
    private val failure = AtomicReference<Throwable?>()

    fun start(): Flow<ByteArray> = flow {
        check(started.compareAndSet(false, true)) { "System audio capture can only be started once" }
        try {
            if (stop.get() == Stop.CANCEL) throw CancellationException("System audio capture cancelled")
            if (stop.get() == Stop.FINISH) return@flow
            val worker = Thread(::produce, "hearth-system-audio-reader").apply { isDaemon = true }
            producer.set(worker); worker.start()
            for (frame in events) emit(frame)
        } finally {
            // Abort the rendezvous before joining: a producer blocked sending to a cancelled
            // consumer must be released, as well as a producer blocked reading a live process.
            events.cancel()
            process.get()?.destroy()
            withContext(NonCancellable + Dispatchers.IO) { producer.get()?.join() }
            done.complete(Unit)
        }
    }.flowOn(Dispatchers.IO).buffer(0)

    /** Stops capture and hands off queued PCM normally; the pipeline may still be finishing inference. */
    suspend fun finish() {
        stop.compareAndSet(Stop.NONE, Stop.FINISH)
        withContext(Dispatchers.IO) { process.get()?.let(::requestFinish) }
        if (started.get()) {
            done.await()
            withContext(NonCancellable + Dispatchers.IO) { producer.get()?.join() }
            failure.get()?.let { throw it }
        }
    }

    /** Signal cancellation immediately; the collecting pipeline joins the process before it returns. */
    fun cancel() {
        stop.set(Stop.CANCEL)
        events.cancel(CancellationException("System audio capture cancelled"))
        process.get()?.destroy()
        if (!started.get()) done.complete(Unit)
    }
    override fun close() = cancel()

    private fun produce() {
        var child: Process? = null
        var diagnostics: Thread? = null
        var terminalFailure: Throwable? = null
        val diagnostic = StringBuilder()
        try {
            if (stop.get() != Stop.NONE) {
                if (stop.get() == Stop.CANCEL) throw CancellationException("System audio capture cancelled")
                return
            }
            child = launch(); process.set(child)
            val current = child
            diagnostics = Thread({
                runCatching {
                    current.errorStream.use { stream ->
                        val buffer = ByteArray(1024)
                        while (true) {
                            val count = stream.read(buffer); if (count < 0) break
                            if (diagnostic.length < 4096) diagnostic.append(String(buffer, 0, minOf(count, 4096 - diagnostic.length), Charsets.UTF_8))
                        }
                    }
                }
            }, "hearth-system-audio-errors").apply { isDaemon = true; start() }
            when (stop.get()) {
                Stop.CANCEL -> child.destroy()
                Stop.FINISH -> requestFinish(child)
                else -> Unit
            }
            var formatFailure: Throwable? = null
            try {
                child.inputStream.use { input ->
                    val header = input.readNBytes(4)
                    if (header.isEmpty() && stop.get() == Stop.FINISH) return@use
                    check(header.contentEquals(byteArrayOf(72, 83, 65, 49))) { "Native system audio returned an unsupported stream protocol" }
                    while (true) {
                        val frame = readSystemAudioFrame(input) ?: break
                        events.trySendBlocking(frame).getOrThrow()
                    }
                    check(input.read() < 0) { "Native system audio returned data after its final frame" }
                }
            } catch (error: Throwable) { formatFailure = error; if (child.isAlive) child.destroy() }
            check(child.waitFor(5, TimeUnit.SECONDS)) { "Native system audio process did not stop after its final frame" }
            diagnostics.join()
            if (stop.get() == Stop.CANCEL) throw CancellationException("System audio capture cancelled")
            val message = diagnostic.toString().trim()
            if (child.exitValue() != 0 && message.isNotEmpty()) error("System audio capture failed: $message")
            formatFailure?.let { throw it }
            check(child.exitValue() == 0) { "Native system audio capture failed (${child.exitValue()})" }
            check(stop.get() == Stop.FINISH) { "System audio capture ended unexpectedly" }
        } catch (error: Throwable) {
            terminalFailure = if (stop.get() == Stop.CANCEL) CancellationException("System audio capture cancelled") else error
        } finally {
            fun release(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    if (terminalFailure == null) terminalFailure = error
                    else if (terminalFailure !== error) terminalFailure!!.addSuppressed(error)
                }
            }
            try {
                child?.let {
                    release { if (it.isAlive) { it.destroy(); if (!it.waitFor(2, TimeUnit.SECONDS)) { it.destroyForcibly(); it.waitFor() } } }
                    release { diagnostics?.join() }
                    release { it.outputStream.close() }; release { it.inputStream.close() }; release { it.errorStream.close() }
                    process.compareAndSet(it, null)
                }
                terminalFailure?.let { failure.set(it) }
                events.close(terminalFailure)
            } finally {
                // A broken control pipe must never strand a caller awaiting finish().
                done.complete(Unit)
            }
        }
    }
    private fun requestFinish(child: Process) {
        if (child.isAlive) runCatching { child.outputStream.write("STOP\n".toByteArray(Charsets.US_ASCII)); child.outputStream.flush() }
            .onFailure { if (child.isAlive) { failure.compareAndSet(null, it); child.destroy() } }
    }

    companion object {
        val available: Boolean get() = availabilityReason == null
        val availabilityReason: String? get() = SystemAudioHelper.availabilityReason
        /** Framework/helper readiness only: no permission request, content enumeration or live audio. */
        suspend fun probe(dataDirectory: Path): String = withContext(Dispatchers.IO) {
            check(available) { availabilityReason ?: "System audio capture is unavailable" }
            val process = ProcessBuilder(SystemAudioHelper.executable(dataDirectory).toString(), "--probe").redirectErrorStream(true).start()
            try {
                check(process.waitFor(10, TimeUnit.SECONDS)) { "System audio helper probe timed out" }
                val report = process.inputStream.readNBytes(4097)
                check(report.size <= 4096 && process.exitValue() == 0) { "System audio helper probe failed: ${report.toString(Charsets.UTF_8).take(4096)}" }
                report.toString(Charsets.UTF_8).also {
                    check(it.lineSequence().any { line -> line == "HEARTH_SYSTEM_AUDIO_PROTOCOL=1" } &&
                        it.lineSequence().any { line -> line == "PERMISSION=not-requested" }) { "Unsupported system audio helper probe" }
                }
            } finally {
                if (process.isAlive) { process.destroyForcibly(); process.waitFor() }
                process.inputStream.close(); process.outputStream.close()
            }
        }
    }
}

internal fun readSystemAudioFrame(input: InputStream): ByteArray? {
    val bytes = input.readNBytes(4)
    check(bytes.size == 4) { "Native system audio ended before its final frame" }
    val size = bytes.indices.fold(0L) { value, index -> value or ((bytes[index].toLong() and 255) shl (index * 8)) }
    if (size == 0L) return null
    check(size in 2..3200 && size % 2 == 0L) { "Native system audio returned an invalid PCM16 frame length" }
    return input.readNBytes(size.toInt()).also { check(it.size == size.toInt()) { "Native system audio returned truncated PCM16" } }
}

private object SystemAudioHelper {
    private const val filename = "hearth-system-audio"
    private val arch = when (System.getProperty("os.arch").lowercase()) { "arm64", "aarch64" -> "arm64"; "x86_64", "amd64" -> "x64"; else -> "unsupported" }
    private val resource = "/media/macos-$arch/$filename"
    private fun external() = (System.getProperty("hearth.native.dir") ?: System.getenv("HEARTH_DESKTOP_NATIVE_DIR"))
        ?.let { Path.of(it).resolve(filename) }?.takeIf { Files.isRegularFile(it) }
    val availabilityReason: String? get() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return "Native system audio capture currently requires macOS 13.3 or newer"
        val version = System.getProperty("os.version").split('.').map { it.toIntOrNull() ?: 0 }
        if (version.firstOrNull() == null || version[0] < 13 || version[0] == 13 && version.getOrElse(1) { 0 } < 3) return "Native system audio capture requires macOS 13.3 or newer"
        if (arch == "unsupported") return "This Mac architecture does not support Hearth system audio capture"
        return if (external() == null && SystemAudioHelper::class.java.getResource(resource) == null) "The native macOS system audio helper is not packaged; build the desktop media helpers" else null
    }
    @Synchronized fun executable(directory: Path): Path {
        external()?.let { return it.toAbsolutePath() }
        val bytes = SystemAudioHelper::class.java.getResourceAsStream(resource)?.use { it.readNBytes(16 * 1024 * 1024 + 1) }
            ?: error("The native macOS system audio helper is not packaged")
        check(bytes.size in 1..(16 * 1024 * 1024)) { "Invalid packaged system audio helper" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val parent = directory.resolve("helpers/macos-$arch/$hash")
        Files.createDirectories(parent); Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
        val file = parent.resolve(filename)
        if (!Files.isRegularFile(file) || Files.size(file) != bytes.size.toLong() || !MessageDigest.isEqual(Files.readAllBytes(file), bytes)) {
            val temporary = Files.createTempFile(parent, ".system-audio-", ".tmp")
            try {
                Files.write(temporary, bytes); Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwx------"))
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        return file.toAbsolutePath()
    }
}

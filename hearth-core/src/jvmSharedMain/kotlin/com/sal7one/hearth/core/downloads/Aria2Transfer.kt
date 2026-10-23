package com.sal7one.hearth.core.downloads

import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

data class Aria2Request(
    val executable: File, val target: File, val url: String, val expectedBytes: Long,
    val connections: Int = 4, val bandwidthBytesPerSecond: Long = 0, val retries: Int = 3,
    val environment: Map<String, String> = emptyMap(),
    val httpOnlyBuild: Boolean = false,
)
data class Aria2Progress(val downloadedBytes: Long, val totalBytes: Long, val bytesPerSecond: Long)
data class Aria2Result(val elapsedMillis: Long, val resumed: Boolean, val downloadedBytes: Long,
                       val transferredBytes: Long? = if (resumed) null else downloadedBytes)

/** A failed executable/HTTPS capability check; safe to distinguish from a failed network or integrity check. */
class Aria2ProbeException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Independent subprocess transport. Callers own policy, pinned hashing and atomic publication.
 * The .aria2 sidecar owns sparse-piece state; file length is never download progress.
 */
object Aria2Transfer {
    private const val VERSION_LIMIT = 16_384

    /** A selected path must actually identify an HTTPS-capable aria2 runtime. */
    suspend fun probe(executable: File, environment: Map<String,String> = emptyMap()): String = withContext(Dispatchers.IO) {
        require(executable.isAbsolute && executable.isFile && executable.canExecute()) { "aria2 executable is unavailable: $executable" }
        require(environment.keys.all { it in setOf("LD_LIBRARY_PATH", "SSL_CERT_FILE", "SSL_CERT_DIR") })
        val builder = ProcessBuilder(executable.path, "--version").redirectErrorStream(true)
        builder.environment().putAll(environment)
        // Desktop builds may localize --version. Do not mutate the application's locale.
        builder.environment()["LC_ALL"] = "C"
        builder.environment()["LANG"] = "C"
        val process = try { builder.start() }
        catch (error: IOException) { throw Aria2ProbeException("Cannot start aria2 --version: ${error.message}", error) }
        coroutineScope {
            val output = async(Dispatchers.IO) { readVersionOutput(process.inputStream) }
            try {
                val text = withTimeoutOrNull(5_000) { while (process.isAlive) delay(25); output.await() }
                    ?: throw Aria2ProbeException("aria2 --version did not finish within 5 seconds")
                validateVersion(text, process.exitValue())
            } finally {
                // A blocking pipe reader is unblocked by terminating its owned child before joining it.
                withContext(NonCancellable + Dispatchers.IO) {
                    try { stopProcess(process, 500) } finally { process.inputStream.close() }
                }
            }
        }
    }

    /** available() is only a hint, not EOF, including after Android reports that a child has exited. */
    internal suspend fun readVersionOutput(input: InputStream): String {
        val output = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(2_048)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = it.read(buffer)
                if (count < 0) break
                if (count == 0) { yield(); continue }
                if (output.size() + count > VERSION_LIMIT) throw Aria2ProbeException("aria2 version response exceeds its 16 KiB limit")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    internal fun validateVersion(text: String, exitCode: Int): String {
        val diagnostic = text.trim().takeLast(4_096).ifBlank { "no output" }
        if (exitCode != 0) throw Aria2ProbeException("aria2 --version exited $exitCode: $diagnostic")
        val lines = text.lineSequence().map(String::trim).toList()
        val version = lines.firstOrNull { Regex("^aria2 version [0-9]+\\.[0-9]+(?:\\.[0-9]+)?[A-Za-z0-9.+~_-]*$").matches(it) }
            ?: throw Aria2ProbeException("Selected executable did not report a valid aria2 version: $diagnostic")
        val features = lines.firstOrNull { it.startsWith("Enabled Features:") }
            ?.substringAfter(':')?.split(',')?.map(String::trim).orEmpty()
        if ("HTTPS" !in features) throw Aria2ProbeException("aria2 runtime does not advertise HTTPS in Enabled Features: $diagnostic")
        return version.take(120)
    }

    /** Bounded UTF-8 lines, including a final diagnostic without a newline. */
    internal suspend fun readDownloadOutput(input: InputStream, line: (String) -> Unit) {
        input.reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(2_048)
            val pending = StringBuilder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = reader.read(buffer)
                if (count < 0) break
                if (count == 0) { yield(); continue }
                for (index in 0 until count) {
                    val character = buffer[index]
                    if (character == '\n' || character == '\r') { line(pending.toString()); pending.setLength(0) }
                    else if (pending.length < 8_192) pending.append(character)
                }
            }
            if (pending.isNotEmpty()) line(pending.toString())
        }
    }

    private fun stopProcess(process: Process, graceMillis: Long) {
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) { "aria2 process termination could not be confirmed" }
            }
        }
    }

    internal fun arguments(request: Aria2Request): List<String> {
        val uri = URI(request.url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null) {
            "aria2 model downloads require an HTTPS URL without credentials or fragments"
        }
        require(request.connections in 1..16 && request.retries in 0..20 && request.bandwidthBytesPerSecond >= 0 && request.expectedBytes > 0)
        require(request.target.isAbsolute && request.executable.isAbsolute) { "aria2 requires absolute local paths" }
        require(request.environment.keys.all { it in setOf("LD_LIBRARY_PATH", "SSL_CERT_FILE", "SSL_CERT_DIR") }) { "Unsupported aria2 environment override" }
        val optional = if (request.httpOnlyBuild) emptyList() else listOf("--follow-metalink=false", "--follow-torrent=false", "--enable-dht=false", "--enable-dht6=false", "--enable-peer-exchange=false")
        return listOf(request.executable.path,
            "--no-conf=true", "--no-netrc=true", "--enable-rpc=false", "--check-certificate=true",
            "--max-concurrent-downloads=1", "--auto-file-renaming=false",
            "--allow-overwrite=true", "--continue=true", "--always-resume=true", "--file-allocation=none",
            "--auto-save-interval=1", "--split=${request.connections}", "--max-connection-per-server=${request.connections}",
            "--min-split-size=1M", "--max-download-limit=${request.bandwidthBytesPerSecond}",
            "--max-tries=${request.retries + 1}", "--retry-wait=2", "--connect-timeout=15", "--timeout=30",
            "--console-log-level=warn", "--enable-color=false", "--human-readable=false", "--summary-interval=1",
            "--show-console-readout=true", "--truncate-console-readout=false", "--download-result=full",
            "--dir=${requireNotNull(request.target.parentFile).path}", "--out=${request.target.name}") + optional +
            request.environment["SSL_CERT_FILE"]?.let { listOf("--ca-certificate=$it") }.orEmpty() + request.url
    }

    internal fun parseProgress(line: String): Aria2Progress? {
        val match = Regex("\\[#[0-9a-f]+\\s+(\\d+)B/(\\d+)B.*?DL:(\\d+)B").find(line) ?: return null
        val (done, total, speed) = match.destructured
        val downloaded = done.toLongOrNull() ?: return null
        val bytes = total.toLongOrNull() ?: return null
        if (bytes <= 0 || downloaded !in 0..bytes) return null
        return Aria2Progress(downloaded, bytes, speed.toLongOrNull() ?: 0)
    }

    suspend fun download(request: Aria2Request, onProgress: (Aria2Progress) -> Unit = {}): Aria2Result = withContext(Dispatchers.IO) {
        val args = arguments(request)
        require(request.executable.isFile && request.executable.canExecute()) { "aria2 executable is unavailable: ${request.executable}" }
        probe(request.executable, request.environment)
        require(!java.nio.file.Files.isSymbolicLink(request.target.toPath())) { "aria2 staging target must not be a symbolic link" }
        val parent = requireNotNull(request.target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create aria2 staging directory" }
        val resumed = request.target.exists() || File(request.target.path + ".aria2").exists()
        val started = System.nanoTime()
        val builder = ProcessBuilder(args).redirectErrorStream(true)
        builder.environment().putAll(request.environment)
        // Never inherit provider-specific configuration through aria's config or .netrc.
        val process = builder.start()
        val tail = StringBuilder()
        try {
            coroutineScope {
                val reader = async(Dispatchers.IO) {
                    readDownloadOutput(process.inputStream) { text ->
                        parseProgress(text)?.let {
                            check(it.totalBytes == request.expectedBytes) { "aria2 server size differs from pinned model size" }
                            onProgress(it)
                        }
                        if (text.isNotBlank()) synchronized(tail) {
                            tail.append(text.replace(request.url, "[model URL]")).append('\n')
                            if (tail.length > 8192) tail.delete(0, tail.length - 8192)
                        }
                    }
                }
                try {
                    while (process.isAlive) {
                        ensureActive()
                        // A callback can cancel only the reader. Propagate that cancellation
                        // here as well, so the child process cannot continue without a drain.
                        if (reader.isCompleted) reader.await()
                        delay(100)
                    }
                    reader.await()
                    check(process.exitValue() == 0) { "aria2 exited ${process.exitValue()}: ${synchronized(tail) { tail.toString().trim().takeLast(4096) }}" }
                } finally {
                    // Stop before coroutineScope joins a reader blocked in native pipe I/O.
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { stopProcess(process, 1500) } finally { process.inputStream.close() }
                    }
                }
            }
            check(request.target.isFile && request.target.length() == request.expectedBytes) { "aria2 model size differs from pinned size" }
            check(!File(request.target.path + ".aria2").exists()) { "aria2 left an incomplete piece map" }
            onProgress(Aria2Progress(request.expectedBytes, request.expectedBytes, 0))
            Aria2Result((System.nanoTime() - started) / 1_000_000, resumed, request.expectedBytes)
        } finally {
            // SIGTERM lets aria persist its piece map; keep it for the next queue attempt.
            withContext(NonCancellable + Dispatchers.IO) {
                try { stopProcess(process, 1500) } finally { process.inputStream.close() }
            }
        }
    }
}

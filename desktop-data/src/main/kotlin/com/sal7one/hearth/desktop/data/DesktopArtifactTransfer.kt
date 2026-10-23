package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.downloads.Aria2Request
import com.sal7one.hearth.core.downloads.Aria2Transfer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.io.File
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A permit is held only for network I/O. Hashing and extraction use their own lane. */
internal class DesktopTransferSlots(initial: Int = 2) {
    private val mutex = Mutex()
    private val changed = MutableStateFlow(0L)
    private var active = 0
    @Volatile private var limit = initial
    fun resize(value: Int) { require(value in 1..8); limit = value; changed.update { it + 1 } }
    suspend fun <T> withSlot(waiting: (Boolean) -> Unit = {}, block: suspend () -> T): T {
        waiting(true)
        while (true) {
            val revision = changed.value
            if (mutex.withLock { if (active < limit) { active++; true } else false }) break
            changed.first { it != revision }
        }
        try { waiting(false); return block() }
        finally { withContext(NonCancellable) { mutex.withLock { active-- }; changed.update { it + 1 } } }
    }
}

internal data class ArtifactTransferResult(val backend: String, val networkBytes: Long, val resumedBytes: Long,
    val elapsedMillis: Long, val connections: Int)

/** Discovery only locates a regular executable; the shared runner authenticates the aria2 version before use. */
fun desktopAria2Executable(): Path? {
    val name = if (System.getProperty("os.name").lowercase().contains("win")) "aria2c.exe" else "aria2c"
    val candidates = (System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() } +
        listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")).distinct()
    return candidates.asSequence().mapNotNull { runCatching { Path.of(it).toAbsolutePath().resolve(name) }.getOrNull() }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}

/** Downloads to a private staging file. The caller must authenticate it before publication. */
internal class DesktopArtifactTransfer(private val allowLoopbackForTests: Boolean = false) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    suspend fun download(artifact: DesktopModelArtifact, target: Path, options: DesktopDownloadOptions,
        progress: (Long) -> Unit): ArtifactTransferResult = withContext(Dispatchers.IO) {
        val uri = URI(artifact.url)
        check(validUri(uri)) { "Model download requires HTTPS without credentials" }
        require(!Files.isSymbolicLink(target) && !Files.isSymbolicLink(target.parent)) { "Unsafe download staging path" }
        val aria = options.aria2Executable.takeIf { it.isNotBlank() }?.let(Path::of) ?: desktopAria2Executable()
        val backend = when (options.backend) {
            DesktopDownloadBackend.HTTP -> DesktopDownloadBackend.HTTP
            DesktopDownloadBackend.ARIA2 -> { require(aria != null) { "Choose an aria2 executable in Download options" }; DesktopDownloadBackend.ARIA2 }
            DesktopDownloadBackend.AUTO -> if (aria != null) DesktopDownloadBackend.ARIA2 else DesktopDownloadBackend.HTTP
        }
        if (backend == DesktopDownloadBackend.ARIA2) {
            require(!allowLoopbackForTests || uri.scheme == "https") { "aria2 model requests require HTTPS" }
            require(aria != null && Files.isRegularFile(aria) && Files.isExecutable(aria)) { "Chosen aria2 executable is missing or cannot run: $aria" }
            val result = Aria2Transfer.download(Aria2Request(executable = requireNotNull(aria).toFile(), target = target.toFile(),
                url = artifact.url, expectedBytes = artifact.bytes, connections = options.connectionsPerFile,
                bandwidthBytesPerSecond = options.bandwidthBytesPerSecond, retries = options.retries)) {
                progress(it.downloadedBytes)
            }
            ArtifactTransferResult("aria2", if (result.resumed) 0 else result.downloadedBytes, if (result.resumed) -1 else 0,
                result.elapsedMillis, options.connectionsPerFile)
        } else http(artifact, target, options, progress)
    }

    private suspend fun http(artifact: DesktopModelArtifact, target: Path, options: DesktopDownloadOptions,
        progress: (Long) -> Unit): ArtifactTransferResult {
        // aria2 sparse files cannot be resumed by treating their allocated file length as a contiguous prefix.
        require(!Files.exists(Path.of(target.toString() + ".aria2"))) { "This partial file belongs to aria2. Resume with aria2 or restart this download." }
        val metadata = Path.of(target.toString() + ".resume.json")
        var validators = readMetadata(metadata, artifact)
        if (Files.exists(target) && (validators == null || Files.size(target) > artifact.bytes)) Files.delete(target)
        var offset = if (Files.isRegularFile(target)) Files.size(target) else 0
        val resumed = offset
        var network = 0L
        val started = System.nanoTime()
        progress(offset)
        if (offset == artifact.bytes) return ArtifactTransferResult("http", 0, resumed, 0, 1)
        for (attempt in 0..options.retries) {
            currentCoroutineContext().ensureActive()
            try {
                val response = open(URI(artifact.url), offset, validators)
                response.body().use { input ->
                    val code = response.statusCode()
                    check(code == 200 || code == 206) { "Model download returned HTTP $code" }
                    val nextValidator = response.headers().firstValue("ETag").orElse(null)
                        ?: response.headers().firstValue("Last-Modified").orElse(null)
                    val previousValidator = validators?.get("validator")?.jsonPrimitive?.contentOrNull
                    val append = code == 206 && offset > 0
                    if (code == 206) {
                        val range = response.headers().firstValue("Content-Range").orElse("")
                        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range)
                        check(match != null && match.groupValues[1].toLong() == offset &&
                            match.groupValues[2].toLong() == artifact.bytes - 1 && match.groupValues[3].toLong() == artifact.bytes) {
                            "Model server returned an invalid resume range"
                        }
                        check(previousValidator == null || nextValidator == null || previousValidator == nextValidator) {
                            "Model server changed the partial download. Restart this item to discard its old bytes."
                        }
                    }
                    if (!append) offset = 0
                    val declared = response.headers().firstValueAsLong("Content-Length")
                    check(!declared.isPresent || declared.asLong == artifact.bytes - offset) { "Model server length differs from pinned size" }
                    validators = buildJsonObject {
                        put("url", artifact.url); put("bytes", artifact.bytes); put("sha256", artifact.sha256)
                        nextValidator?.let { put("validator", it) }
                    }
                    atomicWrite(metadata, validators.toString())
                    val flags = if (append) arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND) else
                        arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                    coroutineScope {
                        val closer = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { input.close() } }
                        try {
                            Files.newOutputStream(target, *flags).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val size = runInterruptible { input.read(buffer) }
                                    if (size < 0) break
                                    check(size.toLong() <= artifact.bytes - offset) { "Model download exceeds pinned size" }
                                    output.write(buffer, 0, size); offset += size; network += size
                                    progress(offset)
                                    if (options.bandwidthBytesPerSecond > 0) {
                                        val expectedMillis = (network * 1000.0 / options.bandwidthBytesPerSecond).toLong()
                                        val elapsed = (System.nanoTime() - started) / 1_000_000
                                        if (expectedMillis > elapsed) delay(expectedMillis - elapsed)
                                    }
                                }
                            }
                        } finally { closer.cancelAndJoin() }
                    }
                    check(offset == artifact.bytes) { "Model download ended before its pinned length ($offset/${artifact.bytes})" }
                }
                return ArtifactTransferResult("http", network, resumed, (System.nanoTime() - started) / 1_000_000, 1)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == options.retries || e is IllegalArgumentException || e is IllegalStateException &&
                    (e.message?.contains("changed the partial") == true || e.message?.contains("invalid resume") == true)) throw e
                delay(minOf(500L shl attempt, 8000))
                offset = if (Files.isRegularFile(target)) Files.size(target) else 0
            }
        }
        error("Download retry loop exhausted")
    }

    private fun readMetadata(path: Path, artifact: DesktopModelArtifact): JsonObject? = try {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path) || Files.size(path) > 8192) null else {
            Json.parseToJsonElement(Files.readString(path)).jsonObject.takeIf {
                it["url"]?.jsonPrimitive?.content == artifact.url && it["bytes"]?.jsonPrimitive?.longOrNull == artifact.bytes &&
                    it["sha256"]?.jsonPrimitive?.content == artifact.sha256
            }
        }
    } catch (_: Exception) { null }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun open(initial: URI, offset: Long, validators: JsonObject?): HttpResponse<java.io.InputStream> {
        var current = initial
        repeat(6) { redirects ->
            check(validUri(current)) { "Model download redirect requires HTTPS without credentials" }
            val builder = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(45))
                .header("User-Agent", "Hearth-Desktop/2").header("Accept-Encoding", "identity")
            if (offset > 0) {
                builder.header("Range", "bytes=$offset-")
                validators?.get("validator")?.jsonPrimitive?.contentOrNull?.let { builder.header("If-Range", it) }
            }
            val request = builder.GET().build()
            val response = suspendCancellableCoroutine<HttpResponse<java.io.InputStream>> { continuation ->
                val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                continuation.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { value, error ->
                    if (error != null) continuation.resumeWithException(error)
                    else if (continuation.isActive) continuation.resume(value) { value.body().close() }
                    else value.body().close()
                }
            }
            if (response.statusCode() !in setOf(301, 302, 303, 307, 308)) return response
            response.body().close()
            check(redirects < 5) { "Too many model download redirects" }
            current = current.resolve(response.headers().firstValue("Location").orElseThrow { IllegalStateException("Model redirect has no destination") })
        }
        error("Too many model redirects")
    }
    private fun validUri(uri: URI): Boolean = uri.userInfo == null && uri.fragment == null && uri.host != null &&
        (uri.scheme == "https" || allowLoopbackForTests && uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "::1"))
}

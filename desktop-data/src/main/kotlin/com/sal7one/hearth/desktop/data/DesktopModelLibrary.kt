package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineStage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DesktopModelArtifact(val filename: String, val url: String, val bytes: Long, val sha256: String)
data class DesktopModelArchiveFile(val path: String, val bytes: Long, val sha256: String)
data class DesktopModelArchive(
    val format: String, val root: String, val expandedBytes: Long, val files: List<DesktopModelArchiveFile>,
    val byPath: Map<String, DesktopModelArchiveFile> = files.associateBy { it.path },
)
data class DesktopModelCard(
    val id: String, val name: String, val family: String, val stage: PipelineStage,
    val engineId: String, val languages: Set<String>, val source: String, val license: String,
    val note: String, val options: Map<String, String>, val artifacts: List<DesktopModelArtifact>,
    val archive: DesktopModelArchive? = null,
    val platforms: Set<String>? = null,
) {
    /** Bytes transferred from the model host. */
    val bytes: Long get() = artifacts.sumOf { it.bytes }

    /** Bytes on disk after a pinned archive has been unpacked. */
    val installedBytes: Long get() = archive?.expandedBytes ?: bytes
}
data class DesktopModelStatus(val ready: Boolean, val path: Path?, val issue: String? = null)

/** Downloads only pinned catalog artifacts. Requests contain no provider headers or credentials. */
class DesktopModelLibrary(private val directory: Path) {
    val catalog: List<DesktopModelCard> = readCatalog().filter { it.platforms == null || desktopPlatform() in it.platforms }
    private val mutableInstallations = MutableStateFlow<Map<String, DesktopModelStatus>>(emptyMap())
    val installations = mutableInstallations.asStateFlow()
    private val installer = Mutex()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    /** Full content verification is repeated at launch; filename/existence alone never marks a model ready. */
    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, DesktopModelStatus>()
        for (card in catalog) {
            currentCoroutineContext().ensureActive()
            val root = modelDirectory(card)
            if (Files.isDirectory(root)) {
                try { verifyFiles(card, root); result[card.id] = DesktopModelStatus(true, runtimePath(card, root)) }
                catch (failure: Exception) { currentCoroutineContext().ensureActive(); result[card.id] = DesktopModelStatus(false, null, failure.message) }
            } else result[card.id] = DesktopModelStatus(false, null)
        }
        mutableInstallations.value = result
    }
    suspend fun download(id: String, progress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }): EngineSelection = installer.withLock {
        withContext(Dispatchers.IO) {
            val card = find(id)
            val root = modelDirectory(card)
            Files.createDirectories(root)
            var completed = 0L
            for (artifact in card.artifacts) {
                currentCoroutineContext().ensureActive()
                val target = root.resolve(artifact.filename)
                if (Files.isRegularFile(target) && Files.size(target) == artifact.bytes && digest(target) == artifact.sha256) {
                    completed += artifact.bytes; progress(completed, card.bytes); continue
                }
                val temporary = Files.createTempFile(root, ".download-", ".part")
                try {
                    val response = openArtifact(URI(artifact.url))
                    response.body().use { input ->
                        check(response.statusCode() == 200) { "Model download returned HTTP ${response.statusCode()}" }
                        val declared = response.headers().firstValueAsLong("Content-Length")
                        check(!declared.isPresent || declared.asLong == artifact.bytes) { "Model server length differs from pinned size" }
                        coroutineScope {
                            val closer = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { input.close() } }
                            try {
                                Files.newOutputStream(temporary).use { output ->
                                    val hash = MessageDigest.getInstance("SHA-256")
                                    var count = 0L; val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        ensureActive(); val size = runInterruptible { input.read(buffer) }; if (size < 0) break
                                        count += size; check(count <= artifact.bytes) { "Model download exceeds pinned size" }
                                        output.write(buffer, 0, size); hash.update(buffer, 0, size); progress(completed + count, card.bytes)
                                    }
                                    check(count == artifact.bytes && hash.digest().hex() == artifact.sha256) { "Model integrity verification failed" }
                                }
                            } finally { closer.cancelAndJoin() }
                        }
                    }
                    try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
                    completed += artifact.bytes
                } finally { Files.deleteIfExists(temporary) }
            }
            if (card.archive != null) installArchive(card, root)
            verifyFiles(card, root)
            val selected = selection(card, runtimePath(card, root))
            mutableInstallations.value = mutableInstallations.value + (card.id to DesktopModelStatus(true, runtimePath(card, root)))
            selected
        }
    }
    suspend fun verify(id: String, path: Path): EngineSelection = withContext(Dispatchers.IO) {
        val card = find(id)
        val single = card.artifacts.size == 1 && card.archive == null
        val root = if (single) path.toAbsolutePath().parent else path.toAbsolutePath()
        if (single) {
            val artifact = card.artifacts.single()
            check(Files.isRegularFile(path) && Files.size(path) == artifact.bytes && digest(path) == artifact.sha256) { "Selected file does not match the pinned model" }
        } else verifyFiles(card, root)
        selection(card, if (single) path.toAbsolutePath() else root)
    }
    fun installedSelection(id: String): EngineSelection? = mutableInstallations.value[id]?.takeIf { it.ready }?.path?.let { selection(find(id), it) }
    fun find(id: String): DesktopModelCard = catalog.singleOrNull { it.id == id } ?: error("Unknown desktop model: $id")
    private fun selection(card: DesktopModelCard, path: Path): EngineSelection {
        val single = if (card.artifacts.size == 1 && card.archive == null) mapOf("sha256" to card.artifacts.single().sha256) else emptyMap()
        // Multi-component engines verify each file; single-file engines keep the one sha256 pin.
        val components = if (card.artifacts.size > 1) card.artifacts.associate { "sha256.${it.filename}" to it.sha256 } else emptyMap()
        return EngineSelection(card.engineId, card.id, path.toString(), options = card.options + single + components)
    }
    private fun modelDirectory(card: DesktopModelCard): Path = directory.resolve("models").resolve(card.id)
    private fun runtimePath(card: DesktopModelCard, root: Path) =
        if (card.artifacts.size == 1 && card.archive == null) root.resolve(card.artifacts.single().filename) else root
    private suspend fun verifyFiles(card: DesktopModelCard, root: Path) {
        val archive = card.archive
        if (archive != null) {
            for (file in archive.files) {
                val target = root.resolve(file.path).normalize()
                check(target.startsWith(root.normalize())) { "Model component escapes its directory: ${file.path}" }
                check(Files.isRegularFile(target) && Files.size(target) == file.bytes && digest(target) == file.sha256) {
                    "Missing or modified model component: ${file.path}"
                }
            }
            return
        }
        for (asset in card.artifacts) {
            val file = root.resolve(asset.filename)
            check(Files.isRegularFile(file) && Files.size(file) == asset.bytes && digest(file) == asset.sha256) { "Missing or modified model component: ${asset.filename}" }
        }
    }

    /** Unpack the verified archive into the model directory, keeping only pinned components. */
    internal fun installArchive(card: DesktopModelCard, root: Path) {
        val archive = card.archive ?: return
        val tarball = root.resolve(card.artifacts.single().filename)
        check(Files.isRegularFile(tarball)) { "Model archive is missing: ${tarball.fileName}" }
        val staging = Files.createTempDirectory(root, ".extract-")
        val expected = archive.byPath
        val seen = mutableSetOf<String>()
        var expanded = 0L
        try {
            BZip2CompressorInputStream(BufferedInputStream(Files.newInputStream(tarball), 1 shl 16)).use { compressed ->
                TarArchiveInputStream(compressed).use { tar ->
                    var entries = 0
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        check(++entries <= 2000) { "Model archive exceeds 2000 entries" }
                        require(!entry.isSymbolicLink && !entry.isLink && !entry.isSparse && (entry.isDirectory || entry.isFile)) {
                            "Unsupported model archive entry: ${entry.name}"
                        }
                        val full = entry.name.trimEnd('/')
                        require(full.isNotEmpty() && full.length <= 1024 && safeArchivePath(full)) { "Invalid model archive path: ${entry.name}" }
                        require(full == archive.root || full.startsWith(archive.root + "/")) { "Unexpected model archive root: $full" }
                        if (full == archive.root) { require(entry.isDirectory); continue }
                        if (entry.isDirectory) continue
                        val relative = full.removePrefix(archive.root + "/")
                        val file = expected[relative] ?: continue
                        require(seen.add(relative)) { "Duplicate model archive component: $relative" }
                        require(entry.size == file.bytes) { "Model archive component has the wrong declared size: $relative" }
                        expanded = Math.addExact(expanded, file.bytes)
                        require(expanded <= archive.expandedBytes) { "Model archive exceeds its pinned expanded size" }
                        val target = staging.resolve(relative)
                        Files.createDirectories(target.parent)
                        val hash = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        Files.newOutputStream(target).use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                val read = tar.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= file.bytes) { "Model archive component exceeds its pinned size: $relative" }
                                hash.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                        require(count == file.bytes && hash.digest().hex() == file.sha256) { "Model archive component failed verification: $relative" }
                    }
                }
            }
            require(seen == expected.keys) { "Model archive is missing components: ${expected.keys - seen}" }
            require(expanded == archive.expandedBytes) { "Model archive expanded size differs from its pin" }
            for (relative in expected.keys) {
                val target = root.resolve(relative)
                Files.createDirectories(target.parent)
                Files.move(staging.resolve(relative), target, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.deleteIfExists(tarball)
        } catch (error: Throwable) {
            // Keep the verified archive so a retry does not download 900 MB again.
            staging.toFile().deleteRecursively()
            throw error
        }
        staging.toFile().deleteRecursively()
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun openArtifact(initial: URI): HttpResponse<java.io.InputStream> {
        var current = initial
        repeat(6) { redirects ->
            check(current.scheme == "https" && current.userInfo == null) { "Model download requires HTTPS" }
            val request = HttpRequest.newBuilder(current).timeout(Duration.ofHours(2)).header("User-Agent", "Hearth-Desktop/1").GET().build()
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
    companion object {
        private fun readCatalog(): List<DesktopModelCard> {
            val content = requireNotNull(DesktopModelLibrary::class.java.getResourceAsStream("/desktop-models.json")).bufferedReader().use { it.readText() }
            return Json.parseToJsonElement(content).jsonObject.getValue("models").jsonArray.map { element ->
                val m = element.jsonObject
                fun text(key: String) = m.getValue(key).jsonPrimitive.content
                val assets = m.getValue("artifacts").jsonArray.map { a ->
                    val f = a.jsonObject
                    val filename = f.getValue("filename").jsonPrimitive.content
                    val digest = f.getValue("sha256").jsonPrimitive.content
                    val bytes = f.getValue("bytes").jsonPrimitive.long
                    val url = f.getValue("url").jsonPrimitive.content
                    require(filename.matches(Regex("[A-Za-z0-9_.-]{1,180}")) && filename != ".." && digest.matches(Regex("[a-f0-9]{64}")) && bytes > 0)
                    require(URI(url).scheme == "https")
                    DesktopModelArtifact(filename, url, bytes, digest)
                }
                val id = text("id"); require(id.matches(Regex("[a-zA-Z0-9_.-]{1,100}")))
                val archive = m["archive"]?.jsonObject?.let { a ->
                    val format = a.getValue("format").jsonPrimitive.content
                    require(format == "tar.bz2") { "Unsupported desktop model archive format: $format" }
                    val root = a.getValue("root").jsonPrimitive.content
                    require(safeArchivePath(root) && '/' !in root) { "Invalid desktop model archive root: $root" }
                    val expanded = a.getValue("expandedBytes").jsonPrimitive.long
                    val files = a.getValue("files").jsonArray.map { f ->
                        val o = f.jsonObject
                        val path = o.getValue("path").jsonPrimitive.content
                        val bytes = o.getValue("bytes").jsonPrimitive.long
                        val sha = o.getValue("sha256").jsonPrimitive.content
                        require(safeArchivePath(path) && bytes > 0 && sha.matches(Regex("[a-f0-9]{64}"))) { "Invalid desktop model archive component: $path" }
                        DesktopModelArchiveFile(path, bytes, sha)
                    }
                    require(files.size in 1..1000 && files.map { it.path }.toSet().size == files.size) { "Desktop model archive components are missing or duplicated" }
                    require(files.sumOf { it.bytes } == expanded) { "Desktop model archive expanded size differs from its components" }
                    DesktopModelArchive(format, root, expanded, files)
                }
                require(archive == null || (assets.size == 1 && assets.single().filename.endsWith(".tar.bz2"))) {
                    "An archive model must have exactly one .tar.bz2 artifact"
                }
                val platforms = m["platforms"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
                require(platforms == null || platforms.isNotEmpty() && platforms.all { it.matches(Regex("[a-z0-9]+-[a-z0-9]+")) }) {
                    "Invalid desktop model platforms"
                }
                DesktopModelCard(id, text("name"), text("family"), PipelineStage.valueOf(text("stage")), text("engineId"),
                    m.getValue("languages").jsonArray.map { it.jsonPrimitive.content }.toSet(), text("source"), text("license"), text("note"),
                    m.getValue("options").jsonObject.mapValues { it.value.jsonPrimitive.content }, assets, archive, platforms)
            }
        }
        internal suspend fun digest(path: Path): String {
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
            }
            return hash.digest().hex()
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}

private fun safeArchivePath(path: String): Boolean =
    path.isNotEmpty() && path.length <= 1024 &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
        '\\' !in path && ':' !in path && path.none { it.isISOControl() }

/** Must match the native bundle target platform names (native/macos-arm64/...). */
internal fun desktopPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val system = when {
        os.startsWith("windows") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }
    val cpu = when (arch) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x64"
        else -> arch
    }
    return "$system-$cpu"
}

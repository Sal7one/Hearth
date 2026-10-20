package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineStage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
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
data class DesktopModelCard(
    val id: String, val name: String, val family: String, val stage: PipelineStage,
    val engineId: String, val languages: Set<String>, val source: String, val license: String,
    val note: String, val options: Map<String, String>, val artifacts: List<DesktopModelArtifact>,
) { val bytes: Long get() = artifacts.sumOf { it.bytes } }
data class DesktopModelStatus(val ready: Boolean, val path: Path?, val issue: String? = null)

/** Downloads only pinned catalog artifacts. Requests contain no provider headers or credentials. */
class DesktopModelLibrary(private val directory: Path) {
    val catalog: List<DesktopModelCard> = readCatalog()
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
            verifyFiles(card, root)
            val selected = selection(card, runtimePath(card, root))
            mutableInstallations.value = mutableInstallations.value + (card.id to DesktopModelStatus(true, runtimePath(card, root)))
            selected
        }
    }
    suspend fun verify(id: String, path: Path): EngineSelection = withContext(Dispatchers.IO) {
        val card = find(id)
        val root = if (card.artifacts.size == 1) path.toAbsolutePath().parent else path.toAbsolutePath()
        if (card.artifacts.size == 1) {
            val artifact = card.artifacts.single()
            check(Files.isRegularFile(path) && Files.size(path) == artifact.bytes && digest(path) == artifact.sha256) { "Selected file does not match the pinned model" }
        } else verifyFiles(card, root)
        selection(card, path.toAbsolutePath())
    }
    fun installedSelection(id: String): EngineSelection? = mutableInstallations.value[id]?.takeIf { it.ready }?.path?.let { selection(find(id), it) }
    fun find(id: String): DesktopModelCard = catalog.singleOrNull { it.id == id } ?: error("Unknown desktop model: $id")
    private fun selection(card: DesktopModelCard, path: Path) = EngineSelection(card.engineId, card.id, path.toString(),
        options = card.options + if (card.artifacts.size == 1) mapOf("sha256" to card.artifacts.single().sha256) else emptyMap())
    private fun modelDirectory(card: DesktopModelCard): Path = directory.resolve("models").resolve(card.id)
    private fun runtimePath(card: DesktopModelCard, root: Path) = if (card.artifacts.size == 1) root.resolve(card.artifacts.single().filename) else root
    private suspend fun verifyFiles(card: DesktopModelCard, root: Path) {
        for (asset in card.artifacts) {
            val file = root.resolve(asset.filename)
            check(Files.isRegularFile(file) && Files.size(file) == asset.bytes && digest(file) == asset.sha256) { "Missing or modified model component: ${asset.filename}" }
        }
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
                DesktopModelCard(id, text("name"), text("family"), PipelineStage.valueOf(text("stage")), text("engineId"),
                    m.getValue("languages").jsonArray.map { it.jsonPrimitive.content }.toSet(), text("source"), text("license"), text("note"),
                    m.getValue("options").jsonObject.mapValues { it.value.jsonPrimitive.content }, assets)
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

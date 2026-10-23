package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineStage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream
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
private val ARTIFACT_SEGMENT = Regex("[A-Za-z0-9_.-]{1,180}")

/** Direct-download components may live in a subdirectory, but never escape the model root. */
internal fun validArtifactPath(filename: String): Boolean =
    filename.length in 1..1024 && filename.split('/').let { parts -> parts.isNotEmpty() && parts.all { it != "." && it != ".." && ARTIFACT_SEGMENT.matches(it) } }
data class DesktopModelArchiveFile(val path: String, val bytes: Long, val sha256: String)
data class DesktopModelArchive(
    val format: String, val root: String, val expandedBytes: Long, val files: List<DesktopModelArchiveFile> = emptyList(),
    val treeSha256: String? = null,
    val byPath: Map<String, DesktopModelArchiveFile> = files.associateBy { it.path },
) {
    /** Archive that pins every component; otherwise the expanded tree digest is the pin. */
    val hasComponentPins: Boolean get() = files.isNotEmpty()
}

/** Authenticated append-only HTTP range segments extracted from a pinned upstream archive. */
data class DesktopRangePackage(
    val sourceUrl: String, val sourceBytes: Long, val sourceSha256: String,
    val bytes: Long, val sha256: String,
    val ranges: List<LongRange>, val members: List<DesktopModelArchiveFile>,
    val memberByPath: Map<String, DesktopModelArchiveFile> = members.associateBy { it.path },
)
data class DesktopModelCard(
    val id: String, val name: String, val family: String, val stage: PipelineStage?,
    val engineId: String, val languages: Set<String>, val source: String, val license: String,
    val note: String, val options: Map<String, String>, val artifacts: List<DesktopModelArtifact>,
    val archive: DesktopModelArchive? = null,
    val rangePackage: DesktopRangePackage? = null,
    val platforms: Set<String>? = null,
) {
    /** Bytes transferred from the model host. */
    val bytes: Long get() = artifacts.sumOf { it.bytes } + (rangePackage?.bytes ?: 0L)

    /** Bytes on disk after pinned components have been unpacked. */
    val installedBytes: Long get() = archive?.expandedBytes
        ?: (artifacts.sumOf { it.bytes } + (rangePackage?.members?.sumOf { it.bytes } ?: 0L))
}
data class DesktopModelStatus(val ready: Boolean, val path: Path?, val issue: String? = null)
enum class ModelInstallPhase { CHECKING, DOWNLOADING, EXTRACTING, VERIFYING }
data class ModelInstallProgress(val phase: ModelInstallPhase, val completed: Long, val total: Long, val component: String = "")

/** Downloads only pinned catalog artifacts. Requests contain no provider headers or credentials. */
class DesktopModelLibrary internal constructor(val directory: Path, val catalog: List<DesktopModelCard>, private val transferEngine: DesktopArtifactTransfer = DesktopArtifactTransfer()) {
    constructor(directory: Path) : this(directory, (readCatalog()+mangaPageCard()+koharuMangaPageCard()+koharuCleanupMangaPageCard()).filter { it.platforms == null || desktopPlatform() in it.platforms })
    private val mutableInstallations = MutableStateFlow<Map<String, DesktopModelStatus>>(emptyMap())
    val installations = mutableInstallations.asStateFlow()
    val downloads = DesktopDownloadStore(directory)
    private val transfers = DesktopTransferSlots(downloads.state.value.options.simultaneousFiles)
    private val verification = kotlinx.coroutines.sync.Semaphore(2)
    fun configureDownloads(options: DesktopDownloadOptions) { downloads.options(options); transfers.resize(options.simultaneousFiles) }
    private val installers = catalog.associate { it.id to Mutex() }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    /** Full content verification is repeated at launch; filename/existence alone never marks a model ready. */
    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        for (card in catalog) {
            currentCoroutineContext().ensureActive()
            installers.getValue(card.id).withLock {
                val root = modelDirectory(card)
                val result = if (Files.isDirectory(root)) {
                    try { verifyFiles(card, root); DesktopModelStatus(true, runtimePath(card, root)) }
                    catch (failure: Exception) { currentCoroutineContext().ensureActive(); DesktopModelStatus(false, null, failure.message) }
                } else DesktopModelStatus(false, null)
                mutableInstallations.update { it + (card.id to result) }
            }
        }
    }
    suspend fun download(id: String, progress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        activity: (ModelInstallProgress) -> Unit = {}, options: DesktopDownloadOptions = downloads.state.value.options,
        waiting: (Boolean) -> Unit = {}): EngineSelection = installers.getValue(find(id).id).withLock {
        withContext(Dispatchers.IO) {
            val card = find(id)
            val root = modelDirectory(card)
            Files.createDirectories(root)
            require(!Files.isSymbolicLink(root)) { "Model install directory must not be a symbolic link" }
            // Existing healthy files need no HTTP, including archives whose transport was removed after extraction.
            try {
                check(hasRuntimeInventory(card, root)) { "Model files are not installed" }
                verification.withPermit { verifyFiles(card, root, activity, ModelInstallPhase.CHECKING) }
                mutableInstallations.update { it + (id to DesktopModelStatus(true, runtimePath(card, root))) }
                return@withContext selection(card, runtimePath(card, root))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableInstallations.update { it + (id to DesktopModelStatus(false, null, e.message ?: e.toString())) }
            }
            fun transfer(done: Long) {
                progress(done, card.bytes)
                activity(ModelInstallProgress(ModelInstallPhase.DOWNLOADING, done, card.bytes))
            }
            transfer(0)
            var completed = 0L
            for (artifact in card.artifacts) {
                currentCoroutineContext().ensureActive()
                val target = root.resolve(artifact.filename).normalize()
                require(target.startsWith(root.normalize())) { "Model component escapes its directory: ${artifact.filename}" }
                val healthy = Files.isRegularFile(target) && !Files.isSymbolicLink(target) && Files.size(target) == artifact.bytes &&
                    verification.withPermit { digest(target) == artifact.sha256 }
                if (healthy) { completed += artifact.bytes; transfer(completed); continue }
                target.parent?.let { Files.createDirectories(it) }
                var ancestor = target.parent
                while (ancestor != null && ancestor.startsWith(root)) {
                    require(!Files.isSymbolicLink(ancestor)) { "Model component directory is a symbolic link" }
                    ancestor = ancestor.parent
                }
                val temporary = target.resolveSibling(".${target.fileName}.${artifact.sha256.take(16)}.part")
                // Reserve only work that is starting, not the entire optional catalogue queue.
                val partial = if (!Files.exists(Path.of(temporary.toString() + ".aria2")) && Files.isRegularFile(temporary) && !Files.isSymbolicLink(temporary)) Files.size(temporary).coerceAtMost(artifact.bytes) else 0
                val remaining = artifact.bytes - partial
                val expansion = card.archive?.expandedBytes ?: 0L
                val reserve = 64L * 1024 * 1024
                val available = Files.getFileStore(root).usableSpace
                val required = Math.addExact(Math.addExact(remaining, expansion), reserve)
                check(available >= required) { "Not enough disk space to download and install ${card.name}: need $required bytes, available $available" }
                // Deterministic private staging survives pause, process exit and a failed network request.
                val measurement = transfers.withSlot(waiting) {
                    transferEngine.download(artifact, temporary, options) { done -> transfer(completed + done) }
                }
                verification.withPermit {
                    activity(ModelInstallProgress(ModelInstallPhase.VERIFYING, 0, artifact.bytes, artifact.filename))
                    val matches = Files.size(temporary) == artifact.bytes && digest(temporary) { done, total ->
                        activity(ModelInstallProgress(ModelInstallPhase.VERIFYING, done, total, artifact.filename))
                    } == artifact.sha256
                    if (!matches) {
                        Files.deleteIfExists(temporary)
                        Files.deleteIfExists(Path.of(temporary.toString() + ".resume.json"))
                        Files.deleteIfExists(Path.of(temporary.toString() + ".aria2"))
                        error("Model integrity verification failed: ${artifact.filename}")
                    }
                    try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
                    Files.deleteIfExists(Path.of(temporary.toString() + ".resume.json"))
                    Files.deleteIfExists(Path.of(temporary.toString() + ".aria2"))
                    if (measurement.networkBytes > 0) downloads.measured(DesktopTransferMeasurement(id, artifact.filename,
                        measurement.backend, measurement.networkBytes, measurement.elapsedMillis, measurement.resumedBytes, measurement.connections))
                }
                completed += artifact.bytes
            }
            val context = currentCoroutineContext()
            if (card.rangePackage != null) transfers.withSlot(waiting) { installRanges(card, root) { done -> transfer(completed + done) } }
            verification.withPermit {
                if (card.archive != null) installArchive(card, root, activity) { context.ensureActive() }
                verifyFiles(card, root, activity)
            }
            val selected = selection(card, runtimePath(card, root))
            mutableInstallations.update { it + (card.id to DesktopModelStatus(true, runtimePath(card, root))) }
            selected
        }
    }
    suspend fun verify(id: String, path: Path, activity: (ModelInstallProgress) -> Unit = {}): EngineSelection = installers.getValue(find(id).id).withLock { withContext(Dispatchers.IO) { verification.withPermit {
        val card = find(id)
        val single = card.artifacts.size == 1 && card.archive == null && card.rangePackage == null
        val root = if (single) path.toAbsolutePath().parent else path.toAbsolutePath()
        if (single) {
            val artifact = card.artifacts.single()
            check(Files.isRegularFile(path) && Files.size(path) == artifact.bytes && digest(path) { done, total ->
                activity(ModelInstallProgress(ModelInstallPhase.VERIFYING, done, total, artifact.filename))
            } == artifact.sha256) { "Selected file does not match the pinned model" }
        } else verifyFiles(card, root, activity)
        selection(card, if (single) path.toAbsolutePath() else root)
    } } }
    suspend fun discardPartial(id: String) = installers.getValue(find(id).id).withLock {
        withContext(Dispatchers.IO) {
            val card = find(id)
            val root = modelDirectory(card).normalize()
            if (!Files.exists(root)) return@withContext
            require(!Files.isSymbolicLink(root)) { "Unsafe model install directory" }
            for (artifact in card.artifacts) {
                val target = root.resolve(artifact.filename).normalize()
                require(target.startsWith(root)) { "Model component escapes its directory" }
                var ancestor = target.parent
                while (ancestor != null && ancestor.startsWith(root)) {
                    require(!Files.isSymbolicLink(ancestor)) { "Unsafe model component directory" }
                    ancestor = ancestor.parent
                }
                val temporary = target.resolveSibling(".${target.fileName}.${artifact.sha256.take(16)}.part")
                Files.deleteIfExists(temporary)
                Files.deleteIfExists(Path.of(temporary.toString() + ".resume.json"))
                Files.deleteIfExists(Path.of(temporary.toString() + ".aria2"))
            }
        }
    }
    fun installedSelection(id: String): EngineSelection? = mutableInstallations.value[id]?.takeIf { it.ready }?.path?.let { selection(find(id), it) }
    fun find(id: String): DesktopModelCard = catalog.singleOrNull { it.id == id } ?: error("Unknown desktop model: $id")
    private fun selection(card: DesktopModelCard, path: Path): EngineSelection {
        val single = if (card.artifacts.size == 1 && card.archive == null && card.rangePackage == null) mapOf("sha256" to card.artifacts.single().sha256) else emptyMap()
        // Multi-component engines verify each file; single-file engines keep the one sha256 pin.
        val components = if (card.artifacts.size > 1) card.artifacts.associate { "sha256.${it.filename}" to it.sha256 } else emptyMap()
        return EngineSelection(card.engineId, card.id, path.toString(), options = card.options + single + components)
    }
    private fun hasRuntimeInventory(card: DesktopModelCard, root: Path): Boolean {
        val archive = card.archive
        if (archive != null) {
            if (archive.hasComponentPins) return archive.files.all { Files.isRegularFile(root.resolve(it.path)) && Files.size(root.resolve(it.path)) == it.bytes }
            return Files.list(root).use { files -> files.anyMatch { !it.fileName.toString().startsWith(".") &&
                it.fileName.toString() !in card.artifacts.map { artifact -> artifact.filename } } }
        }
        return card.artifacts.all { Files.isRegularFile(root.resolve(it.filename)) && Files.size(root.resolve(it.filename)) == it.bytes } &&
            card.rangePackage?.members.orEmpty().all { Files.isRegularFile(root.resolve("tokenizer").resolve(it.path)) }
    }
    private fun modelDirectory(card: DesktopModelCard): Path = directory.resolve("models").resolve(card.id)
    private fun runtimePath(card: DesktopModelCard, root: Path) =
        if (card.artifacts.size == 1 && card.archive == null && card.rangePackage == null) root.resolve(card.artifacts.single().filename) else root
    private suspend fun verifyFiles(card: DesktopModelCard, root: Path, activity: (ModelInstallProgress) -> Unit = {},
        phase: ModelInstallPhase = ModelInstallPhase.VERIFYING) {
        val archive = card.archive
        if (archive != null) {
            if (!archive.hasComponentPins) {
                requireNotNull(archive.treeSha256) { "Model archive has no tree pin" }
                val context = currentCoroutineContext()
                check(modelTreeSha256(root, { done, total, file -> activity(ModelInstallProgress(phase, done, total, file)) },
                    { context.ensureActive() }) == archive.treeSha256) { "Missing or modified model tree: ${card.id}" }
                return
            }
            var completed = 0L
            for (file in archive.files) {
                val target = root.resolve(file.path).normalize()
                check(target.startsWith(root.normalize())) { "Model component escapes its directory: ${file.path}" }
                check(Files.isRegularFile(target) && Files.size(target) == file.bytes && digest(target) { done, _ ->
                    activity(ModelInstallProgress(phase, completed + done, archive.expandedBytes, file.path))
                } == file.sha256) {
                    "Missing or modified model component: ${file.path}"
                }
                completed += file.bytes
            }
            return
        }
        var completed = 0L
        val total = card.installedBytes
        for (asset in card.artifacts) {
            val file = root.resolve(asset.filename).normalize()
            check(file.startsWith(root.normalize()) && Files.isRegularFile(file) && Files.size(file) == asset.bytes && digest(file) { done, _ ->
                activity(ModelInstallProgress(phase, completed + done, total, asset.filename))
            } == asset.sha256) { "Missing or modified model component: ${asset.filename}" }
            completed += asset.bytes
        }
        val rangePackage = card.rangePackage
        if (rangePackage != null) {
            for (member in rangePackage.members) {
                val file = root.resolve("tokenizer").resolve(member.path)
                check(Files.isRegularFile(file) && Files.size(file) == member.bytes && digest(file) { done, _ ->
                    activity(ModelInstallProgress(phase, completed + done, total, "tokenizer/${member.path}"))
                } == member.sha256) {
                    "Missing or modified model component: tokenizer/${member.path}"
                }
                completed += member.bytes
            }
        }
    }

    /** Unpack the verified archive into the model directory, keeping only pinned components. */
    internal fun installArchive(card: DesktopModelCard, root: Path, activity: (ModelInstallProgress) -> Unit = {}, checkActive: () -> Unit = {}) {
        val archive = card.archive ?: return
        if (archive.format == "zip") { installZip(card, root, activity, checkActive); return }
        activity(ModelInstallProgress(ModelInstallPhase.EXTRACTING, 0, archive.expandedBytes))
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
                        checkActive()
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
                                checkActive()
                                val read = tar.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= file.bytes) { "Model archive component exceeds its pinned size: $relative" }
                                hash.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                activity(ModelInstallProgress(ModelInstallPhase.EXTRACTING, expanded - file.bytes + count, archive.expandedBytes, relative))
                            }
                        }
                        require(count == file.bytes && hash.digest().hex() == file.sha256) { "Model archive component failed verification: $relative" }
                    }
                }
            }
            require(seen == expected.keys) { "Model archive is missing components: ${expected.keys - seen}" }
            require(expanded == archive.expandedBytes) { "Model archive expanded size differs from its pin" }
            for (relative in expected.keys) {
                checkActive()
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

    /**
     * Download the pinned append-only byte ranges from an upstream archive, verify the
     * concatenated payload, and publish only the authenticated tokenizer members. The
     * embedded model_weights.ckpt is skipped and never installed. Superseded members are
     * authenticated and discarded; the final inventory is verified by SHA-256 on disk.
     */
    private suspend fun installRanges(card: DesktopModelCard, root: Path, progress: (Long) -> Unit = {}) {
        val packageInfo = card.rangePackage ?: return
        val tokenizer = root.resolve("tokenizer")
        if (packageInfo.members.all { member ->
                val file = tokenizer.resolve(member.path)
                Files.isRegularFile(file) && Files.size(file) == member.bytes && digest(file) == member.sha256
            }) { progress(packageInfo.bytes); return }
        val payload = Files.createTempFile(root, ".tokenizer-ranges-", ".part")
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            Files.newOutputStream(payload).use { output ->
                for (range in packageInfo.ranges) {
                    currentCoroutineContext().ensureActive()
                    val response = openRange(URI(packageInfo.sourceUrl), range.first, range.last, packageInfo.sourceBytes)
                    response.body().use { input ->
                        var remaining = range.last - range.first + 1
                        val buffer = ByteArray(1 shl 16)
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val read = runInterruptible { input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()) }
                            check(read > 0) { "Model range ended before its pinned length (range=${range.first}-${range.last}, remaining=$remaining, received=$total)" }
                            output.write(buffer, 0, read); hash.update(buffer, 0, read)
                            total += read; remaining -= read; check(total <= packageInfo.bytes) { "Model range exceeds its pinned payload" }
                        }
                        check(runInterruptible { input.read() } < 0) { "Model range exceeded its pinned extent" }
                    }
                    progress(total)
                }
            }
            check(total == packageInfo.bytes && hash.digest().joinToString("") { "%02x".format(it) } == packageInfo.sha256) {
                "Model range payload failed verification"
            }
            val staging = Files.createTempDirectory(root, ".tokenizer-")
            val backup = root.resolve(".tokenizer-backup")
            try {
                extractRangePayload(payload, packageInfo, staging)
                if (Files.exists(backup)) backup.toFile().deleteRecursively()
                if (Files.exists(tokenizer)) Files.move(tokenizer, backup, StandardCopyOption.REPLACE_EXISTING)
                try { Files.move(staging, tokenizer) }
                catch (error: Throwable) {
                    if (Files.exists(backup) && !Files.exists(tokenizer)) Files.move(backup, tokenizer, StandardCopyOption.REPLACE_EXISTING)
                    throw error
                }
                backup.toFile().deleteRecursively()
            } finally { staging.toFile().deleteRecursively() }
        } finally { Files.deleteIfExists(payload) }
    }

    /** Bounded TAR walk over the concatenated ranges; extracts only pinned members. */
    internal fun extractRangePayload(payload: Path, packageInfo: DesktopRangePackage, staging: Path) {
        val expected = packageInfo.memberByPath
        val seen = mutableSetOf<String>()
        RandomAccessFile(payload.toFile(), "r").use { input ->
            var boundary = 0L
            for ((segment, range) in packageInfo.ranges.withIndex()) {
                boundary += range.last - range.first + 1
                var entries = 0
                while (input.filePointer + 512 <= boundary) {
                    val header = ByteArray(512)
                    input.readFully(header)
                    if (header.all { it == 0.toByte() }) break
                    fun field(offset: Int, length: Int) = header.copyOfRange(offset, offset + length).toString(Charsets.US_ASCII).trimEnd('\u0000', ' ')
                    fun octal(offset: Int, length: Int) = field(offset, length).trim().let { if (it.isEmpty()) 0L else it.toLong(8) }
                    val checksum = header.indices.sumOf { index -> if (index in 148..155) 32L else (header[index].toInt() and 0xff).toLong() }
                    require(checksum == octal(148, 8)) { "Invalid model range TAR header checksum" }
                    require(++entries <= 200) { "Model range contains too many TAR entries" }
                    val name = field(0, 100).removePrefix("./")
                    val prefix = field(345, 155)
                    val size = octal(124, 12)
                    require(size >= 0) { "Invalid model range TAR size" }
                    val type = header[156].toInt().toChar()
                    // PAX/GNU metadata headers carry names like ./@PaxHeader and the root
                    // directory entry is "./" (empty after normalization); both are skipped.
                    if (!(type == '5' && (name.isEmpty() || name == "."))) {
                        require(prefix.isEmpty() && name.isNotEmpty() && name.length <= 200 && !name.startsWith("/") && '\\' !in name && name.split('/').none { it == ".." }) {
                            "Unsafe model range path: $name"
                        }
                    }
                    if (segment == 0 && name == "model_weights.ckpt") break
                    val padded = ((size + 511) / 512) * 512
                    require(input.filePointer + padded <= boundary) { "Truncated model range TAR member" }
                    when (type) {
                        '0', '\u0000' -> {
                            require(safeArchivePath(name)) { "Unsafe model range path: $name" }
                            val member = expected[name]
                            if (member != null && member.bytes == size) {
                                val bytes = ByteArray(size.toInt())
                                input.readFully(bytes)
                                // The pinned .nemo is append-only: earlier occurrences of the same
                                // name are superseded snapshots. Only an occurrence matching the
                                // final member hash is published; a missing final match fails below.
                                if (digest(bytes) == member.sha256) {
                                    val target = staging.resolve(name).normalize()
                                    require(target.startsWith(staging.normalize())) { "Model tokenizer component escapes its directory: $name" }
                                    Files.write(target, bytes)
                                    seen += name
                                }
                            } else input.seek(input.filePointer + size)
                        }
                        '5' -> require(size == 0L) { "Unsupported model range directory entry" }
                        'x', 'g' -> {
                            require(size <= 65536) { "Model range PAX metadata exceeds bound" }
                            input.seek(input.filePointer + size)
                        }
                        else -> error("Unsupported model range TAR entry type: $type")
                    }
                    input.seek(input.filePointer + (padded - size))
                }
                input.seek(boundary)
            }
        }
        check(seen == expected.keys) { "Model tokenizer is missing components: ${expected.keys - seen}" }
    }

    /**
     * Stream a verified model ZIP with the same bounds as the tar path, then verify the
     * expanded model-tree-sha256-v1 digest before publishing. The verified archive is kept
     * on failure so a retry does not download it again.
     */
    internal fun installZip(card: DesktopModelCard, root: Path, activity: (ModelInstallProgress) -> Unit = {}, checkActive: () -> Unit = {}) {
        val archive = card.archive ?: return
        val expectedTree = requireNotNull(archive.treeSha256) { "A ZIP model archive requires a tree digest pin" }
        val zip = root.resolve(card.artifacts.single().filename)
        check(Files.isRegularFile(zip)) { "Model archive is missing: ${zip.fileName}" }
        val staging = Files.createTempDirectory(root, ".extract-")
        val seen = mutableSetOf<String>()
        var expanded = 0L
        activity(ModelInstallProgress(ModelInstallPhase.EXTRACTING, 0, archive.expandedBytes))
        try {
            ZipInputStream(BufferedInputStream(Files.newInputStream(zip), 1 shl 16)).use { input ->
                var entries = 0
                while (true) {
                    checkActive()
                    val entry = input.nextEntry ?: break
                    check(++entries <= 2000) { "Model archive exceeds 2000 entries" }
                    val full = entry.name.trimEnd('/')
                    require(full.isNotEmpty() && full.length <= 1024 && safeArchivePath(full)) { "Invalid model archive path: ${entry.name}" }
                    require(full == archive.root || full.startsWith(archive.root + "/")) { "Unexpected model archive root: $full" }
                    if (entry.isDirectory) continue
                    val relative = full.removePrefix(archive.root + "/")
                    require(relative.isNotEmpty() && seen.add(relative)) { "Duplicate model archive component: $relative" }
                    val target = staging.resolve(relative).normalize()
                    require(target.startsWith(staging.normalize())) { "Model archive component escapes its directory: $relative" }
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            checkActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            expanded = Math.addExact(expanded, read.toLong())
                            require(expanded <= archive.expandedBytes) { "Model archive exceeds its pinned expanded size" }
                            output.write(buffer, 0, read)
                            activity(ModelInstallProgress(ModelInstallPhase.EXTRACTING, expanded, archive.expandedBytes, relative))
                        }
                    }
                }
            }
            require(expanded == archive.expandedBytes) { "Model archive expanded size differs from its pin" }
            check(modelTreeSha256(staging, { done, total, file -> activity(ModelInstallProgress(ModelInstallPhase.VERIFYING, done, total, file)) }, checkActive) == expectedTree) { "Model tree checksum mismatch after extraction" }
            // Collect first: moving files while walking the same staging tree mutates
            // the directory stream mid-iteration.
            val published = Files.walk(staging).use { stream ->
                stream.filter { Files.isRegularFile(it) }.map { staging.relativize(it).toString() }.toList()
            }
            for (relative in published) {
                checkActive()
                val target = root.resolve(relative)
                Files.createDirectories(target.parent)
                Files.move(staging.resolve(relative), target, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.deleteIfExists(zip)
        } catch (error: Throwable) {
            staging.toFile().deleteRecursively()
            throw error
        }
        staging.toFile().deleteRecursively()
    }
    /** A single pinned byte range; the model host must answer 206 with the exact extent. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun openRange(initial: URI, start: Long, end: Long, sourceBytes: Long): HttpResponse<java.io.InputStream> {
        var current = initial
        repeat(6) { redirects ->
            check(current.scheme == "https" && current.userInfo == null) { "Model range download requires HTTPS" }
            val request = HttpRequest.newBuilder(current).timeout(Duration.ofHours(2))
                .header("User-Agent", "Hearth-Desktop/1")
                .header("Range", "bytes=$start-$end")
                .header("Accept-Encoding", "identity")
                .GET().build()
            val response = suspendCancellableCoroutine<HttpResponse<java.io.InputStream>> { continuation ->
                val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                continuation.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { value, error ->
                    if (error != null) continuation.resumeWithException(error)
                    else if (continuation.isActive) continuation.resume(value) { value.body().close() }
                    else value.body().close()
                }
            }
            if (response.statusCode() in setOf(301, 302, 303, 307, 308)) {
                response.body().close()
                check(redirects < 5) { "Too many model range redirects" }
                current = current.resolve(response.headers().firstValue("Location").orElseThrow { IllegalStateException("Model range redirect has no destination") })
            } else {
                return checkedRangeResponse(response, start, end, sourceBytes)
            }
        }
        error("Too many model range redirects")
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
                    require(validArtifactPath(filename) && digest.matches(Regex("[a-f0-9]{64}")) && bytes > 0)
                    require(URI(url).scheme == "https")
                    DesktopModelArtifact(filename, url, bytes, digest)
                }
                val id = text("id"); require(id.matches(Regex("[a-zA-Z0-9_.-]{1,100}")))
                val archive = m["archive"]?.jsonObject?.let { a ->
                    val format = a.getValue("format").jsonPrimitive.content
                    require(format == "tar.bz2" || format == "zip") { "Unsupported desktop model archive format: $format" }
                    val root = a.getValue("root").jsonPrimitive.content
                    require(safeArchivePath(root) && '/' !in root) { "Invalid desktop model archive root: $root" }
                    val expanded = a.getValue("expandedBytes").jsonPrimitive.long
                    require(expanded > 0) { "Desktop model archive must declare its expanded size" }
                    val tree = a["treeSha256"]?.jsonPrimitive?.content
                    require(tree == null || tree.matches(Regex("[a-f0-9]{64}"))) { "Invalid desktop model archive tree SHA-256" }
                    val files = a["files"]?.jsonArray?.map { f ->
                        val o = f.jsonObject
                        val path = o.getValue("path").jsonPrimitive.content
                        val bytes = o.getValue("bytes").jsonPrimitive.long
                        val sha = o.getValue("sha256").jsonPrimitive.content
                        require(safeArchivePath(path) && bytes > 0 && sha.matches(Regex("[a-f0-9]{64}"))) { "Invalid desktop model archive component: $path" }
                        DesktopModelArchiveFile(path, bytes, sha)
                    } ?: emptyList()
                    if (format == "zip") {
                        require(tree != null && files.isEmpty()) {
                            "A ZIP model archive pins the expanded model-tree SHA-256 and no component list"
                        }
                    } else {
                        require(files.size in 1..1000 && files.map { it.path }.toSet().size == files.size) { "Desktop model archive components are missing or duplicated" }
                        require(files.sumOf { it.bytes } == expanded) { "Desktop model archive expanded size differs from its components" }
                    }
                    DesktopModelArchive(format, root, expanded, files, tree)
                }
                require(archive == null || (assets.size == 1 && assets.single().filename.endsWith(
                    if (archive.format == "zip") ".zip" else ".tar.bz2"))) {
                    "An archive model must have exactly one matching archive artifact"
                }
                val rangePackage = m["rangePackage"]?.jsonObject?.let { r ->
                    val sourceUrl = r.getValue("sourceUrl").jsonPrimitive.content
                    require(URI(sourceUrl).scheme == "https") { "A range package requires an HTTPS source" }
                    val sourceBytes = r.getValue("sourceBytes").jsonPrimitive.long
                    val sourceSha = r.getValue("sourceSha256").jsonPrimitive.content
                    val payloadBytes = r.getValue("bytes").jsonPrimitive.long
                    val payloadSha = r.getValue("sha256").jsonPrimitive.content
                    require(sourceBytes > 0 && payloadBytes > 0 && sourceSha.matches(Regex("[a-f0-9]{64}")) && payloadSha.matches(Regex("[a-f0-9]{64}"))) {
                        "Invalid desktop range package pins"
                    }
                    val ranges = r.getValue("ranges").jsonArray.map { part ->
                        val pair = part.jsonArray
                        require(pair.size == 2) { "A range package entry needs a start and end" }
                        val start = pair[0].jsonPrimitive.long; val end = pair[1].jsonPrimitive.long
                        require(start >= 0 && end >= start && end < sourceBytes) { "Invalid desktop range extent" }
                        LongRange(start, end)
                    }
                    require(ranges.isNotEmpty() && ranges.size <= 16) { "Invalid desktop range package" }
                    val members = r.getValue("members").jsonArray.map { entry ->
                        val member = entry.jsonObject
                        val path = member.getValue("path").jsonPrimitive.content
                        val bytes = member.getValue("bytes").jsonPrimitive.long
                        val sha = member.getValue("sha256").jsonPrimitive.content
                        require(safeArchivePath(path) && bytes > 0 && sha.matches(Regex("[a-f0-9]{64}"))) { "Invalid desktop range member: $path" }
                        DesktopModelArchiveFile(path, bytes, sha)
                    }
                    require(members.isNotEmpty() && members.map { it.path }.toSet().size == members.size) { "Desktop range members are missing or duplicated" }
                    DesktopRangePackage(sourceUrl, sourceBytes, sourceSha, payloadBytes, payloadSha, ranges, members)
                }
                require(rangePackage == null || archive == null) { "A model cannot combine an archive and a range package" }
                val platforms = m["platforms"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
                require(platforms == null || platforms.isNotEmpty() && platforms.all { it.matches(Regex("[a-z0-9]+-[a-z0-9]+")) }) {
                    "Invalid desktop model platforms"
                }
                DesktopModelCard(id, text("name"), text("family"), PipelineStage.valueOf(text("stage")), text("engineId"),
                    m.getValue("languages").jsonArray.map { it.jsonPrimitive.content }.toSet(), text("source"), text("license"), text("note"),
                    m.getValue("options").jsonObject.mapValues { it.value.jsonPrimitive.content }, assets, archive, rangePackage, platforms)
            }
        }
        internal suspend fun digest(path: Path, progress: (Long, Long) -> Unit = { _, _ -> }): String {
            val hash = MessageDigest.getInstance("SHA-256")
            val total = Files.size(path)
            var completed = 0L
            progress(0, total)
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size)
                    completed += size; progress(completed, total) }
            }
            return hash.digest().hex()
        }
        internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}

/** A rejected response still owns a live HTTP body; release it before surfacing the error. */
internal fun checkedRangeResponse(
    response: HttpResponse<java.io.InputStream>, start: Long, end: Long, sourceBytes: Long,
): HttpResponse<java.io.InputStream> {
    try {
        check(response.statusCode() == 206) { "Model host did not honor the pinned byte range (HTTP ${response.statusCode()})" }
        val expected = "bytes $start-$end/$sourceBytes"
        check(response.headers().firstValue("Content-Range").orElse("") == expected) { "Model range response differs from its pin" }
        return response
    } catch (failure: Throwable) {
        try { response.body().close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
}

/**
 * model-tree-sha256-v1 over a directory: the domain, then each regular file sorted by
 * UTF-8 path bytes as u64be(pathBytes) + path + u64be(size) + SHA-256(content).
 * Byte-for-byte compatible with common-jni's native ModelIntegrity/computeModelTreeSha256.
 */
internal fun modelTreeSha256(root: Path, progress: (Long, Long, String) -> Unit = { _, _, _ -> }, checkActive: () -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("model-tree-sha256-v1\u0000".toByteArray(Charsets.US_ASCII))
    val files = mutableListOf<Pair<ByteArray, Path>>()
    Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.forEach { path ->
            checkActive()
            files += root.relativize(path).joinToString("/").toByteArray(Charsets.UTF_8) to path
        }
    }
    check(files.isNotEmpty()) { "Model tree is empty" }
    files.sortWith { first, second -> comparePathBytes(first.first, second.first) }
    val total = files.sumOf { Files.size(it.second) }
    var completed = 0L
    for ((relative, path) in files) {
        checkActive()
        digest.update(longBytes(relative.size.toLong()))
        digest.update(relative)
        digest.update(longBytes(Files.size(path)))
        // The canonical contract hashes each file's 32-byte content digest, not the bytes.
        val content = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) { checkActive(); val read = input.read(buffer); if (read < 0) break; content.update(buffer, 0, read)
                completed += read; progress(completed, total, relative.toString(Charsets.UTF_8)) }
        }
        digest.update(content.digest())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun comparePathBytes(first: ByteArray, second: ByteArray): Int {
    val limit = minOf(first.size, second.size)
    for (index in 0 until limit) {
        val difference = (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
        if (difference != 0) return difference
    }
    return first.size - second.size
}

private fun longBytes(value: Long): ByteArray = ByteArray(8) { index -> (value ushr (56 - index * 8)).toByte() }

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

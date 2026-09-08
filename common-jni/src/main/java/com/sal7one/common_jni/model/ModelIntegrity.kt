package com.sal7one.common_jni.model

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

enum class ModelDigestAlgorithm {
    SHA256
}

enum class ModelDigestKind {
    FILE,
    TREE
}

/**
 * PINNED means the bytes matched a digest supplied independently by the caller.
 * TOFU only detects changes after the first successful import.
 */
enum class ModelDigestTrust {
    PINNED,
    TOFU
}

data class ModelDigest(
    val algorithm: ModelDigestAlgorithm,
    val kind: ModelDigestKind,
    val hex: String,
    val trust: ModelDigestTrust
) {
    init {
        require(algorithm == ModelDigestAlgorithm.SHA256) { "Unsupported digest algorithm: $algorithm" }
        require(SHA256_HEX.matches(hex)) { "Invalid SHA-256 digest" }
    }

    val encoded: String
        get() = "sha256:$hex"

    companion object {
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

data class ModelInspection(
    val digest: ModelDigest,
    val sizeBytes: Long,
    val fileCount: Int
)

data class StagedModel(
    val file: File,
    val inspection: ModelInspection
) {
    val digest: ModelDigest
        get() = inspection.digest

    val sizeBytes: Long
        get() = inspection.sizeBytes
}

class ModelIntegrityException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Shared, dependency-free model integrity and app-private staging utilities.
 *
 * Directory digests use `model-tree-sha256-v1` and are byte-for-byte compatible
 * with the native Group D implementation.
 */
object ModelIntegrity {
    const val MAX_SINGLE_FILE_BYTES: Long = 8L * 1024 * 1024 * 1024
    const val MAX_TREE_BYTES: Long = 16L * 1024 * 1024 * 1024
    const val MAX_TREE_FILES: Int = 100_000
    const val MAX_RELATIVE_DEPTH: Int = 32
    const val MAX_RELATIVE_PATH_BYTES: Int = 4096

    private val TREE_DOMAIN = "model-tree-sha256-v1\u0000".toByteArray(StandardCharsets.US_ASCII)
    private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    private const val BUFFER_SIZE = 64 * 1024
    private val publicationLock = Any()

    fun inspect(
        input: InputStream,
        expectedSha256: String? = null,
        maxBytes: Long = MAX_SINGLE_FILE_BYTES,
        onProgress: ((Long) -> Unit)? = null
    ): ModelInspection {
        require(maxBytes in 1..MAX_SINGLE_FILE_BYTES) { "Invalid model size limit" }
        val digest = MessageDigest.getInstance("SHA-256")
        val size = digestOnly(
            input,
            digest,
            maxBytes,
            requireContent = true,
            onProgress = onProgress
        )
        return ModelInspection(
            digest = checkedDigest(ModelDigestKind.FILE, digest.digest(), expectedSha256),
            sizeBytes = size,
            fileCount = 1
        )
    }

    fun stageFile(
        input: InputStream,
        privateRoot: File,
        requestedName: String,
        expectedSha256: String? = null,
        maxBytes: Long = MAX_SINGLE_FILE_BYTES,
        onProgress: ((Long) -> Unit)? = null
    ): StagedModel {
        require(maxBytes in 1..MAX_SINGLE_FILE_BYTES) { "Invalid model size limit" }
        val root = prepareRoot(privateRoot)
        val safeName = requireSafeName(requestedName)
        val temporary = safeChild(root, ".model-${UUID.randomUUID()}.partial")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val size = FileOutputStream(temporary).use { output ->
                copyAndDigest(input, output, digest, maxBytes, requireContent = true, onProgress).also {
                    output.fd.sync()
                }
            }
            val modelDigest = checkedDigest(
                kind = ModelDigestKind.FILE,
                rawDigest = digest.digest(),
                expectedSha256 = expectedSha256
            )
            val destination = publishWithUnusedName(temporary, root, safeName)
            return StagedModel(
                file = destination,
                inspection = ModelInspection(modelDigest, size, 1)
            )
        } catch (e: Exception) {
            temporary.delete()
            if (e is ModelIntegrityException) throw e
            throw ModelIntegrityException("Failed to stage model file", e)
        }
    }

    fun stageFile(
        source: File,
        privateRoot: File,
        requestedName: String = source.name,
        expectedSha256: String? = null,
        onProgress: ((Long) -> Unit)? = null
    ): StagedModel {
        val before = regularFileSnapshot(source)
        val staged = FileInputStream(source).use { input ->
            stageFile(input, privateRoot, requestedName, expectedSha256, onProgress = onProgress)
        }
        return try {
            requireUnchanged(before, regularFileSnapshot(source))
            staged
        } catch (e: Exception) {
            staged.file.delete()
            throw e
        }
    }

    /**
     * A bounded writer for an unpublished directory. Callers never receive the
     * staging directory itself, so every created path goes through validation.
     */
    class DirectorySink internal constructor(private val stagingRoot: File) {
        private data class Entry(
            val relativePathBytes: ByteArray,
            val sizeBytes: Long,
            val contentDigest: ByteArray
        )

        private val entries = mutableListOf<Entry>()
        private var aggregateBytes = 0L

        fun addDirectory(relativePath: String) {
            val components = validateRelativePath(relativePath)
            resolveComponents(stagingRoot, components).also { directory ->
                if (!directory.mkdirs() && !directory.isDirectory) {
                    throw ModelIntegrityException("Failed to create staged model directory")
                }
            }
        }

        fun addFile(relativePath: String, input: InputStream) {
            if (entries.size >= MAX_TREE_FILES) {
                throw ModelIntegrityException("Model tree exceeds $MAX_TREE_FILES files")
            }
            val components = validateRelativePath(relativePath)
            val destination = resolveComponents(stagingRoot, components)
            val parent = destination.parentFile
                ?: throw ModelIntegrityException("Staged model file has no parent")
            if (!parent.mkdirs() && !parent.isDirectory) {
                throw ModelIntegrityException("Failed to create staged model parent")
            }
            if (destination.exists()) {
                throw ModelIntegrityException("Duplicate model path: $relativePath")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val remaining = MAX_TREE_BYTES - aggregateBytes
            val perFileLimit = minOf(MAX_SINGLE_FILE_BYTES, remaining)
            if (perFileLimit <= 0) {
                throw ModelIntegrityException("Model tree exceeds $MAX_TREE_BYTES bytes")
            }

            val size = try {
                FileOutputStream(destination).use { output ->
                    copyAndDigest(input, output, digest, perFileLimit, requireContent = false).also {
                        output.fd.sync()
                    }
                }
            } catch (e: Exception) {
                destination.delete()
                throw e
            }

            aggregateBytes += size
            entries += Entry(
                relativePathBytes = relativePath.toByteArray(StandardCharsets.UTF_8),
                sizeBytes = size,
                contentDigest = digest.digest()
            )
        }

        internal fun finish(expectedSha256: String?): ModelInspection {
            if (entries.isEmpty()) throw ModelIntegrityException("Model tree is empty")
            if (aggregateBytes <= 0L) throw ModelIntegrityException("Model tree contains no data")
            val treeDigest = MessageDigest.getInstance("SHA-256")
            treeDigest.update(TREE_DOMAIN)
            entries.sortedWith { left, right ->
                compareUnsignedBytes(left.relativePathBytes, right.relativePathBytes)
            }.forEach { entry ->
                treeDigest.update(uint64BigEndian(entry.relativePathBytes.size.toLong()))
                treeDigest.update(entry.relativePathBytes)
                treeDigest.update(uint64BigEndian(entry.sizeBytes))
                treeDigest.update(entry.contentDigest)
            }
            return ModelInspection(
                digest = checkedDigest(ModelDigestKind.TREE, treeDigest.digest(), expectedSha256),
                sizeBytes = aggregateBytes,
                fileCount = entries.size
            )
        }
    }

    fun stageDirectory(
        privateRoot: File,
        requestedName: String,
        expectedSha256: String? = null,
        populate: (DirectorySink) -> Unit
    ): StagedModel {
        val root = prepareRoot(privateRoot)
        val safeName = requireSafeName(requestedName)
        val temporary = safeChild(root, ".model-${UUID.randomUUID()}.partial")
        if (!temporary.mkdir()) throw ModelIntegrityException("Failed to create model staging directory")

        try {
            val sink = DirectorySink(temporary)
            populate(sink)
            val inspection = sink.finish(expectedSha256)
            val destination = publishWithUnusedName(temporary, root, safeName)
            return StagedModel(destination, inspection)
        } catch (e: Exception) {
            temporary.deleteRecursively()
            if (e is ModelIntegrityException) throw e
            throw ModelIntegrityException("Failed to stage model directory", e)
        }
    }

    fun stageDirectory(
        sourceRoot: File,
        privateRoot: File,
        requestedName: String = sourceRoot.name,
        expectedSha256: String? = null
    ): StagedModel {
        val original = directorySnapshot(sourceRoot)
        val source = sourceRoot.canonicalFile
        requireUnchanged(original, directorySnapshot(source))
        val destinationRoot = privateRoot.canonicalFile
        if (isContained(source, destinationRoot)) {
            throw ModelIntegrityException("Model destination cannot be inside its source tree")
        }
        return stageDirectory(privateRoot, requestedName, expectedSha256) { sink ->
            copyDirectoryToSink(source, "", sink, 0)
        }
    }

    fun inspect(target: File, expectedSha256: String? = null): ModelInspection {
        val original = pathSnapshot(target)
        val canonical = target.canonicalFile
        val canonicalSnapshot = pathSnapshot(canonical)
        requireUnchanged(original, canonicalSnapshot)
        val attrs = canonicalSnapshot.attributes
        return when {
            attrs.isRegularFile -> inspectFile(canonical, expectedSha256)
            attrs.isDirectory -> inspectDirectory(canonical, expectedSha256)
            else -> throw ModelIntegrityException("Model is not a regular file or directory")
        }
    }

    fun verify(target: File, expected: ModelDigest): ModelInspection {
        val actual = inspect(target, expected.hex)
        if (actual.digest.kind != expected.kind) {
            throw ModelIntegrityException("Model digest kind changed")
        }
        return actual.copy(digest = actual.digest.copy(trust = expected.trust))
    }

    fun isContained(root: File, candidate: File): Boolean {
        val canonicalRoot = root.canonicalFile
        val canonicalCandidate = candidate.canonicalFile
        return canonicalCandidate == canonicalRoot ||
            canonicalCandidate.path.startsWith(canonicalRoot.path + File.separator)
    }

    fun requireSafeName(name: String): String {
        if (name.isEmpty() || name == "." || name == "..") {
            throw ModelIntegrityException("Model name is empty or reserved")
        }
        if (name.any { it == '/' || it == '\\' || it.code == 0 || it.isISOControl() }) {
            throw ModelIntegrityException("Model name contains an unsafe character")
        }
        if (!hasWellFormedUtf16(name)) {
            throw ModelIntegrityException("Model name contains invalid Unicode")
        }
        if (name.toByteArray(StandardCharsets.UTF_8).size > 255) {
            throw ModelIntegrityException("Model name is too long")
        }
        return name
    }

    private fun inspectFile(file: File, expectedSha256: String?): ModelInspection {
        val before = regularFileSnapshot(file)
        val length = before.attributes.size()
        if (length <= 0) throw ModelIntegrityException("Model file is empty")
        if (length > MAX_SINGLE_FILE_BYTES) {
            throw ModelIntegrityException("Model file exceeds $MAX_SINGLE_FILE_BYTES bytes")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val actualSize = FileInputStream(file).use { input ->
            digestOnly(input, digest, MAX_SINGLE_FILE_BYTES, requireContent = true)
        }
        if (actualSize != length) throw ModelIntegrityException("Model file changed while hashing")
        requireUnchanged(before, regularFileSnapshot(file))
        return ModelInspection(
            checkedDigest(ModelDigestKind.FILE, digest.digest(), expectedSha256),
            actualSize,
            1
        )
    }

    private fun inspectDirectory(directory: File, expectedSha256: String?): ModelInspection {
        directorySnapshot(directory)
        val entries = mutableListOf<TreeEntry>()
        val directories = mutableListOf<PathSnapshot>()
        collectTreeEntries(directory, directory, entries, directories, 0)
        if (entries.isEmpty()) throw ModelIntegrityException("Model tree is empty")

        var aggregate = 0L
        val treeDigest = MessageDigest.getInstance("SHA-256")
        treeDigest.update(TREE_DOMAIN)
        entries.sortedWith { left, right ->
            compareUnsignedBytes(left.relativePathBytes, right.relativePathBytes)
        }.forEach { entry ->
            val before = regularFileSnapshot(entry.file)
            requireUnchanged(entry.snapshot, before)
            val length = before.attributes.size()
            if (length < 0 || length > MAX_SINGLE_FILE_BYTES || aggregate > MAX_TREE_BYTES - length) {
                throw ModelIntegrityException("Model tree exceeds configured size limits")
            }
            val contentDigest = MessageDigest.getInstance("SHA-256")
            val actualSize = FileInputStream(entry.file).use { input ->
                digestOnly(input, contentDigest, MAX_SINGLE_FILE_BYTES, requireContent = false)
            }
            if (actualSize != length) throw ModelIntegrityException("Model tree changed while hashing")
            requireUnchanged(before, regularFileSnapshot(entry.file))
            aggregate += actualSize
            treeDigest.update(uint64BigEndian(entry.relativePathBytes.size.toLong()))
            treeDigest.update(entry.relativePathBytes)
            treeDigest.update(uint64BigEndian(actualSize))
            treeDigest.update(contentDigest.digest())
        }
        directories.forEach { snapshot ->
            requireUnchanged(snapshot, directorySnapshot(snapshot.file))
        }
        if (aggregate <= 0L) throw ModelIntegrityException("Model tree contains no data")
        return ModelInspection(
            checkedDigest(ModelDigestKind.TREE, treeDigest.digest(), expectedSha256),
            aggregate,
            entries.size
        )
    }

    private data class PathSnapshot(
        val file: File,
        val attributes: BasicFileAttributes
    )

    private data class TreeEntry(
        val file: File,
        val relativePathBytes: ByteArray,
        val snapshot: PathSnapshot
    )

    private fun collectTreeEntries(
        root: File,
        directory: File,
        entries: MutableList<TreeEntry>,
        directories: MutableList<PathSnapshot>,
        depth: Int
    ) {
        if (depth > MAX_RELATIVE_DEPTH) {
            throw ModelIntegrityException("Model tree exceeds depth $MAX_RELATIVE_DEPTH")
        }
        val directoryBefore = directorySnapshot(directory)
        val children = directory.listFiles()
            ?: throw ModelIntegrityException("Cannot list model directory")
        children.forEach { child ->
            val attrs = readAttributes(child)
            val relative = root.toPath().relativize(child.toPath()).joinToString("/")
            validateRelativePath(relative)
            when {
                attrs.isDirectory -> collectTreeEntries(root, child, entries, directories, depth + 1)
                attrs.isRegularFile -> {
                    if (entries.size >= MAX_TREE_FILES) {
                        throw ModelIntegrityException("Model tree exceeds $MAX_TREE_FILES files")
                    }
                    entries += TreeEntry(
                        child,
                        relative.toByteArray(StandardCharsets.UTF_8),
                        PathSnapshot(child, attrs)
                    )
                }
                else -> throw ModelIntegrityException("Model tree contains a non-regular entry")
            }
        }
        requireUnchanged(directoryBefore, directorySnapshot(directory))
        directories += directoryBefore
    }

    private fun copyDirectoryToSink(
        directory: File,
        relativeParent: String,
        sink: DirectorySink,
        depth: Int
    ) {
        if (depth > MAX_RELATIVE_DEPTH) {
            throw ModelIntegrityException("Model tree exceeds depth $MAX_RELATIVE_DEPTH")
        }
        val directoryBefore = directorySnapshot(directory)
        val children = directory.listFiles()
            ?: throw ModelIntegrityException("Cannot list source model directory")
        children.forEach { child ->
            val attrs = readAttributes(child)
            val relative = if (relativeParent.isEmpty()) child.name else "$relativeParent/${child.name}"
            when {
                attrs.isDirectory -> {
                    sink.addDirectory(relative)
                    copyDirectoryToSink(child, relative, sink, depth + 1)
                }
                attrs.isRegularFile -> {
                    val before = PathSnapshot(child, attrs)
                    FileInputStream(child).use { sink.addFile(relative, it) }
                    requireUnchanged(before, regularFileSnapshot(child))
                }
                else -> throw ModelIntegrityException("Model tree contains a non-regular entry")
            }
        }
        requireUnchanged(directoryBefore, directorySnapshot(directory))
    }

    private fun prepareRoot(root: File): File {
        val path = root.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            directorySnapshot(root)
        } else if (!root.mkdirs()) {
            throw ModelIntegrityException("Failed to create private model directory")
        }
        val canonical = root.canonicalFile
        directorySnapshot(canonical)
        return canonical
    }

    private fun unusedDestination(root: File, requestedName: String): File {
        var candidate = safeChild(root, requestedName)
        if (!isOccupied(candidate)) return candidate
        val dot = requestedName.lastIndexOf('.')
        val stem = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val extension = if (dot > 0) requestedName.substring(dot) else ""
        for (suffix in 1..10_000) {
            candidate = safeChild(root, "$stem-$suffix$extension")
            if (!isOccupied(candidate)) return candidate
        }
        throw ModelIntegrityException("No unused private model name is available")
    }

    private fun safeChild(root: File, name: String): File {
        requireSafeName(name)
        val child = File(root, name).canonicalFile
        if (!isContained(root, child) || child.parentFile != root.canonicalFile) {
            throw ModelIntegrityException("Model path escapes the private root")
        }
        return child
    }

    private fun publishWithUnusedName(temporary: File, root: File, requestedName: String): File =
        synchronized(publicationLock) {
            val destination = unusedDestination(root, requestedName)
            atomicPublish(temporary, destination)
            destination
        }

    private fun isOccupied(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun validateRelativePath(relativePath: String): List<String> {
        if (relativePath.isEmpty() || relativePath.startsWith('/') || relativePath.endsWith('/')) {
            throw ModelIntegrityException("Invalid relative model path")
        }
        val components = relativePath.split('/')
        if (components.size > MAX_RELATIVE_DEPTH) {
            throw ModelIntegrityException("Model path exceeds depth $MAX_RELATIVE_DEPTH")
        }
        components.forEach(::requireSafeName)
        if (relativePath.toByteArray(StandardCharsets.UTF_8).size > MAX_RELATIVE_PATH_BYTES) {
            throw ModelIntegrityException("Model path exceeds $MAX_RELATIVE_PATH_BYTES UTF-8 bytes")
        }
        return components
    }

    private fun resolveComponents(root: File, components: List<String>): File {
        var current = root
        components.forEach { component -> current = File(current, component) }
        val canonical = current.canonicalFile
        if (!isContained(root, canonical)) {
            throw ModelIntegrityException("Model path escapes its staging root")
        }
        return canonical
    }

    private fun atomicPublish(temporary: File, destination: File) {
        try {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            throw ModelIntegrityException("Filesystem does not support atomic model publication", e)
        }
    }

    private fun pathSnapshot(file: File): PathSnapshot = PathSnapshot(file, readAttributes(file))

    private fun regularFileSnapshot(file: File): PathSnapshot = pathSnapshot(file).also {
        if (!it.attributes.isRegularFile) throw ModelIntegrityException("Model is not a regular file")
    }

    private fun directorySnapshot(file: File): PathSnapshot = pathSnapshot(file).also {
        if (!it.attributes.isDirectory) throw ModelIntegrityException("Model is not a directory")
    }

    private fun requireUnchanged(before: PathSnapshot, after: PathSnapshot) {
        val beforeKey = before.attributes.fileKey()
        val afterKey = after.attributes.fileKey()
        val keyChanged = (beforeKey != null || afterKey != null) && beforeKey != afterKey
        if (keyChanged ||
            before.attributes.isRegularFile != after.attributes.isRegularFile ||
            before.attributes.isDirectory != after.attributes.isDirectory ||
            before.attributes.size() != after.attributes.size() ||
            before.attributes.lastModifiedTime() != after.attributes.lastModifiedTime()
        ) {
            throw ModelIntegrityException("Model changed while hashing or copying")
        }
    }

    private fun readAttributes(file: File): BasicFileAttributes = try {
        Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        ).also {
            if (Files.isSymbolicLink(file.toPath())) {
                throw ModelIntegrityException("Symbolic links are not allowed in models")
            }
        }
    } catch (e: ModelIntegrityException) {
        throw e
    } catch (e: Exception) {
        throw ModelIntegrityException("Cannot inspect model path", e)
    }

    private fun copyAndDigest(
        input: InputStream,
        output: FileOutputStream,
        digest: MessageDigest,
        maxBytes: Long,
        requireContent: Boolean,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (total > maxBytes - read) throw ModelIntegrityException("Model exceeds $maxBytes bytes")
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            total += read
            onProgress?.invoke(total)
        }
        if (requireContent && total == 0L) throw ModelIntegrityException("Model file is empty")
        return total
    }

    private fun digestOnly(
        input: InputStream,
        digest: MessageDigest,
        maxBytes: Long,
        requireContent: Boolean,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (total > maxBytes - read) throw ModelIntegrityException("Model exceeds $maxBytes bytes")
            digest.update(buffer, 0, read)
            total += read
            onProgress?.invoke(total)
        }
        if (requireContent && total == 0L) throw ModelIntegrityException("Model file is empty")
        return total
    }

    private fun checkedDigest(
        kind: ModelDigestKind,
        rawDigest: ByteArray,
        expectedSha256: String?
    ): ModelDigest {
        val actualHex = rawDigest.toHex()
        val normalizedExpected = expectedSha256?.let(::normalizeSha256)
        if (normalizedExpected != null && !MessageDigest.isEqual(
                rawDigest,
                normalizedExpected.hexToBytes()
            )
        ) {
            throw ModelIntegrityException("Model SHA-256 mismatch")
        }
        return ModelDigest(
            algorithm = ModelDigestAlgorithm.SHA256,
            kind = kind,
            hex = actualHex,
            trust = if (normalizedExpected == null) ModelDigestTrust.TOFU else ModelDigestTrust.PINNED
        )
    }

    private fun normalizeSha256(value: String): String {
        val normalized = value.trim().removePrefix("sha256:").lowercase()
        if (!SHA256_PATTERN.matches(normalized)) {
            throw ModelIntegrityException("Expected SHA-256 must contain exactly 64 hex characters")
        }
        return normalized
    }

    private fun compareUnsignedBytes(left: ByteArray, right: ByteArray): Int {
        val common = minOf(left.size, right.size)
        for (index in 0 until common) {
            val comparison = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size - right.size
    }

    private fun hasWellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                    index += 2
                }
                current.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }

    private fun uint64BigEndian(value: Long): ByteArray {
        require(value >= 0) { "Unsigned length cannot be negative" }
        return ByteArray(Long.SIZE_BYTES) { index ->
            (value ushr ((Long.SIZE_BYTES - 1 - index) * 8)).toByte()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun String.hexToBytes(): ByteArray = chunked(2)
        .map { pair -> pair.toInt(16).toByte() }
        .toByteArray()
}

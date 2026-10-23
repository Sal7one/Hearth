package com.sal7one.transiber.preferences

import java.io.File
import java.nio.file.Files

internal data class BoundModelFile(val relative: String, val bytes: Long, val modified: Long)
internal data class BoundModelPath(val path: String, val directory: Boolean, val bytes: Long, val files: List<BoundModelFile>, val error: String? = null)
internal data class ModelPathAvailability(val available: Boolean, val error: String? = null, val changed: Boolean = false)

/** Metadata only. Native loading still verifies the complete pinned file/tree digest. */
internal object ModelPathSnapshots {
    const val MAX_FILES = 20_000
    private fun checkedRoot(privateRoot: File, file: File): File {
        val root = privateRoot.canonicalFile
        require(file.isAbsolute && file.canonicalPath.startsWith(root.path + File.separator)) { "Model binding must stay in app-private model storage" }
        val lexicalRoot = privateRoot.absoluteFile.toPath().normalize()
        val canonicalRoot = root.toPath()
        val candidate = file.toPath().normalize()
        require(candidate == file.toPath()) { "Model binding path contains traversal segments" }
        // Context.filesDir may use /data/user/0, while pinned installers return
        // /data/data via canonicalFile. Both identify the same OS-owned root.
        // Arbitrary aliases and links *inside* model storage remain forbidden.
        val boundary = when {
            candidate.startsWith(lexicalRoot) -> lexicalRoot
            candidate.startsWith(canonicalRoot) -> canonicalRoot
            else -> error("Model binding must use the app-private storage path")
        }
        var path = candidate
        while (path != boundary) {
            require(!Files.isSymbolicLink(path)) { "Symbolic link in model binding: $file" }
            path = path.parent ?: error("Model binding has no private parent")
        }
        return file.absoluteFile
    }
    fun capture(privateRoot: File, file: File, expectedBytes: Long? = null, directory: Boolean = file.isDirectory): BoundModelPath {
        val root = checkedRoot(privateRoot, file)
        if (!root.exists()) return BoundModelPath(root.path, directory, expectedBytes ?: 0, emptyList())
        require(root.canRead()) { "Model path is unreadable: $root" }
        require(root.isDirectory == directory) { "Model path type changed: $root" }
        val files = if (!directory) listOf(BoundModelFile("", root.length(), root.lastModified()))
        else {
            val result = mutableListOf<BoundModelFile>()
            Files.walk(root.toPath(), 32).use { paths -> paths.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Symbolic link in model directory: $path" }
                if (Files.isRegularFile(path)) {
                    require(result.size < MAX_FILES) { "Model binding exceeds $MAX_FILES files" }
                    val value = path.toFile()
                    result += BoundModelFile(root.toPath().relativize(path).toString(), value.length(), value.lastModified())
                }
            } }
            require(result.isNotEmpty()) { "Model directory has no readable files: $root" }
            result.sortedBy { it.relative }
        }
        val bytes = files.fold(0L) { total, entry -> Math.addExact(total, entry.bytes) }
        require(expectedBytes == null || expectedBytes == bytes) { "Model path size differs: expected $expectedBytes, found $bytes bytes" }
        return BoundModelPath(root.path, directory, bytes, files)
    }
    fun check(privateRoot: File, binding: BoundModelPath): ModelPathAvailability = try {
        val root = checkedRoot(privateRoot, File(binding.path))
        when {
            binding.error != null -> ModelPathAvailability(false, binding.error)
            !root.exists() -> ModelPathAvailability(false, "Model path is missing: $root")
            !root.canRead() -> ModelPathAvailability(false, "Model path is unreadable: $root")
            root.isDirectory != binding.directory -> ModelPathAvailability(false, "Model path type changed: $root")
            binding.files.isEmpty() -> ModelPathAvailability(false, "Model path must be imported or located again: $root")
            else -> {
                var changed = false
                var bytes = 0L
                for (expected in binding.files) {
                    require(expected.relative.isEmpty() && !binding.directory ||
                        expected.relative.isNotBlank() && !File(expected.relative).isAbsolute && expected.relative.split('/').none { it == ".." || it == "." }) { "Invalid bound model file" }
                    val file = if (expected.relative.isEmpty()) root else File(root, expected.relative)
                    checkedRoot(privateRoot, file)
                    require(file.isFile && file.canRead()) { "Model file is missing or unreadable: $file" }
                    require(file.length() == expected.bytes) { "Model file size changed: $file" }
                    bytes = Math.addExact(bytes, expected.bytes)
                    changed = changed || file.lastModified() != expected.modified
                }
                require(bytes == binding.bytes) { "Model binding size does not match its file list" }
                ModelPathAvailability(true, changed = changed)
            }
        }
    } catch (error: Exception) { ModelPathAvailability(false, error.message ?: error.toString()) }
}

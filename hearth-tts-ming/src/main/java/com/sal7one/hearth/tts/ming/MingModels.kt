package com.sal7one.hearth.tts.ming

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Verifies and stages the flat Ming bundle the app installs. The fusor-ai runtime reads
 * `manifest.json` plus `parts/000.bin`…`parts/014.bin`; the catalog stores flat
 * `part-000.bin`… files, so [prepare] links each part into that layout without duplicating bytes.
 */
class MingModels(
    private val root: File,
    private val assets: List<MingAsset> = MingCatalog.assets,
) {
    fun file(asset: MingAsset): File = File(root, asset.filename)

    private val partAssets: List<MingAsset> = assets.filter { PART_FILE.matches(it.filename) }

    /** How many pinned components are absent or have the wrong size. Empty means installed. */
    fun missing(): List<String> = assets.filterNot { asset ->
        file(asset).let { it.isFile && it.length() == asset.bytes }
    }.map { it.filename }

    fun ready(): Boolean = partAssets.isNotEmpty() && missing().isEmpty()

    /** Hashes every component before the first native load. This reads the whole ~850 MB bundle. */
    fun verify(cancelled: () -> Unit = {}) {
        assets.forEach { asset ->
            cancelled()
            val file = file(asset)
            check(file.isFile && file.length() == asset.bytes) {
                "Install the complete Ming bundle first: missing or truncated ${asset.filename}"
            }
            check(sha256(file, cancelled) == asset.sha256) { "Ming bundle checksum failed: ${asset.filename}" }
        }
        prepare(cancelled)
    }

    /** Materialize the runtime's parts/ layout. Hard links first; a symlink keeps one copy. */
    fun prepare(cancelled: () -> Unit = {}) {
        check(root.isDirectory) { "Install the Ming bundle before loading the runtime" }
        require(partAssets.isNotEmpty()) { "The Ming catalog has no bundle parts" }
        val parts = File(root, "parts")
        if (!parts.isDirectory && !parts.mkdirs() && !parts.isDirectory) {
            error("Could not create the Ming parts/ layout in app storage")
        }
        partAssets.forEach { asset ->
            cancelled()
            val source = file(asset)
            check(source.isFile && source.length() == asset.bytes) {
                "Install the complete Ming bundle first: missing or truncated ${asset.filename}"
            }
            val target = File(parts, asset.filename.removePrefix("part-"))
            // Length alone is not enough: an app reinstall/upgrade may replace a flat part with
            // a different inode, leaving an old hard link in parts/. Re-link unless it is the same file.
            if (target.isFile && target.length() == asset.bytes &&
                runCatching { Files.isSameFile(target.toPath(), source.toPath()) }.getOrDefault(false)
            ) return@forEach
            if (target.exists() || Files.isSymbolicLink(target.toPath())) {
                check(target.delete()) { "Could not replace the Ming parts entry: ${target.name}" }
            }
            try {
                Files.createLink(target.toPath(), source.toPath())
            } catch (linkError: Exception) {
                try {
                    Files.createSymbolicLink(target.toPath(), source.toPath())
                } catch (symbolicError: Exception) {
                    symbolicError.addSuppressed(linkError)
                    throw IllegalStateException(
                        "Could not stage the Ming bundle into parts/ (${asset.filename}); the bundle stays unavailable",
                        symbolicError,
                    )
                }
            }
        }
    }

    private fun sha256(file: File, cancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                cancelled()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val PART_FILE = Regex("^part-\\d{3}\\.bin$")
    }
}

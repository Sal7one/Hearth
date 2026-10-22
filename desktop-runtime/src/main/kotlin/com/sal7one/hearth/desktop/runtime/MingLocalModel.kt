package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

/** The flat catalog files and the parts/ layout expected by the fusor-ai runtime. */
internal object MingLocalModel {
    val assets: List<String> = listOf("manifest.json", "tokenizer.json") + (0..14).map { "part-%03d.bin".format(it) }
    private val languages = setOf("en", "zh")

    /** Match Android's locale normalization: en-US/zh-Hans are accepted, unsupported tags are not. */
    fun language(tag: String): String? =
        Locale.forLanguageTag(tag).language.lowercase(Locale.ROOT).takeIf { it in languages }

    fun verify(selection: EngineSelection): Path {
        val root = Path.of(selection.modelPath).toRealPath()
        require(Files.isDirectory(root)) { "Select the complete Ming bundle directory" }
        for ((key, expected) in selection.options.filterKeys { it.startsWith("sha256.") }) {
            val name = key.removePrefix("sha256.")
            require(name in assets) { "Unexpected Ming component: $name" }
            val file = root.resolve(name).normalize()
            require(file.startsWith(root) && Files.isRegularFile(file)) { "Missing Ming component: $name" }
            check(sha256(file) == expected) { "Ming component checksum mismatch: $name" }
        }
        return prepare(root)
    }

    /**
     * Materialize the runtime's parts/ layout from the flat catalog files. Always verify that an
     * existing target is the same file as its flat source: a model update replaces flat parts in
     * place, and a stale hard link/symlink would otherwise make the runtime fail BLAKE3.
     */
    fun prepare(root: Path): Path {
        val parts = root.resolve("parts")
        Files.createDirectories(parts)
        for (index in 0..14) {
            val source = root.resolve("part-%03d.bin".format(index))
            val target = parts.resolve("%03d.bin".format(index))
            if (!Files.isRegularFile(source)) {
                // A native parts/ bundle has no flat source; only require an already-layered part.
                require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    "Missing Ming part: ${source.fileName}"
                }
                continue
            }
            val same = Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                runCatching { Files.isSameFile(target, source) }.getOrDefault(false)
            if (same) continue
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                Files.delete(target)
            }
            try {
                Files.createLink(target, source)
            } catch (_: Exception) {
                // Relative targets would resolve from parts/; always use an absolute target.
                Files.createSymbolicLink(target, source.toAbsolutePath())
            }
        }
        return root
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** The flat catalog files and the parts/ layout expected by the fusor-ai runtime. */
internal object MingLocalModel {
    val assets: List<String> = listOf("manifest.json", "tokenizer.json") + (0..14).map { "part-%03d.bin".format(it) }

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

    /** Materialize the runtime's parts/ layout; the catalog downloads flat files. */
    fun prepare(root: Path): Path {
        val parts = root.resolve("parts")
        val alreadyLayered = Files.isDirectory(parts) && (0..14).all { Files.isRegularFile(parts.resolve("%03d.bin".format(it))) }
        if (!alreadyLayered) {
            Files.createDirectories(parts)
            for (index in 0..14) {
                val source = root.resolve("part-%03d.bin".format(index))
                val target = parts.resolve("%03d.bin".format(index))
                require(Files.isRegularFile(source)) { "Missing Ming part: ${source.fileName}" }
                if (!Files.exists(target)) {
                    try { Files.createLink(target, source) }
                    catch (_: Exception) { Files.createSymbolicLink(target, source.fileName) }
                }
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

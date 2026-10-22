package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale

/** The flat catalog files and the parts/ layout expected by the fusor-ai runtime. */
internal object MingLocalModel {
    val assets: List<String> = listOf("manifest.json", "tokenizer.json") + (0..14).map { "part-%03d.bin".format(it) }
    private val languages = setOf("en", "zh")
    private val integrity = ComponentModelIntegrity("Ming", assets.toSet(), component = { root, name ->
        val flat = root.resolve(name)
        if (name.startsWith("part-") && !Files.exists(flat, LinkOption.NOFOLLOW_LINKS)) {
            root.resolve("parts/${name.removePrefix("part-")}")
        } else flat
    })

    /** Match Android's locale normalization: en-US/zh-Hans are accepted, unsupported tags are not. */
    fun language(tag: String): String? =
        Locale.forLanguageTag(tag).language.lowercase(Locale.ROOT).takeIf { it in languages }

    fun stamp(selection: EngineSelection, keepActive: () -> Unit = {}) =
        integrity.stamp(Path.of(selection.modelPath), selection.options, keepActive)

    fun verify(selection: EngineSelection, keepActive: () -> Unit = {}): Path =
        prepare(integrity.verify(Path.of(selection.modelPath), selection.options, keepActive).root)

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

}

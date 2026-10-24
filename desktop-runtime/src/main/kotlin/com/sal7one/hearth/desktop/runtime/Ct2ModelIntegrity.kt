package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Exact inputs opened by Ct2Model and CTranslate2's SequenceToSequenceModel loader. */
internal object Ct2ModelIntegrity {
    private val vocabularies = setOf("shared_vocabulary.json", "shared_vocabulary.txt", "source_vocabulary.json",
        "source_vocabulary.txt", "target_vocabulary.json", "target_vocabulary.txt")
    private val metadata = setOf("LICENSE", "LICENSE.txt", "NOTICE", "README.md", "tokenizer_config.json", "special_tokens_map.json", "hearth-translation.json")

    private fun inventory(root: Path, mode: Int): ComponentModelIntegrity {
        require(mode in 0..5) { "Unsupported CTranslate2 tokenizer mode" }
        fun present(name: String) = Files.exists(root.resolve(name), LinkOption.NOFOLLOW_LINKS)
        val required = mutableSetOf("model.bin", "config.json")
        required += when (mode) {
            0 -> setOf("src.spm.model", "tgt.spm.model")
            1 -> setOf("sentencepiece.bpe.model")
            else -> setOf("tokenizer.json")
        }
        val existing = vocabularies.filter(::present).toSet()
        require(existing.any { it.startsWith("shared_vocabulary.") } ||
            existing.any { it.startsWith("source_vocabulary.") } && existing.any { it.startsWith("target_vocabulary.") }) {
            "CTranslate2 needs a shared vocabulary or both source and target vocabularies"
        }
        // CT2 prefers shared over paired vocabularies and JSON over TXT. Pin every existing
        // candidate so an extra unverified file cannot silently override the selected package.
        required += existing
        if (present("vmap.txt")) required += "vmap.txt"
        return ComponentModelIntegrity("CTranslate2", required, metadata)
    }

    fun stamp(root: Path, mode: Int, selection: EngineSelection, keepActive: () -> Unit = {}) =
        inventory(root, mode).stamp(root, selection.options, keepActive)

    fun verify(root: Path, mode: Int, selection: EngineSelection, keepActive: () -> Unit = {}) =
        inventory(root, mode).verify(root, selection.options, keepActive)
}

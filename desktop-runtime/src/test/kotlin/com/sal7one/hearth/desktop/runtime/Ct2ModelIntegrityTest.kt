package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

/** Small synthetic bytes test integrity only; they are never advertised as executable model weights. */
class Ct2ModelIntegrityTest {
    private val quick = setOf("model.bin", "config.json", "source_vocabulary.json", "target_vocabulary.json", "src.spm.model", "tgt.spm.model")

    @Test fun omittedOrMalformedPinsCannotSkipModelOrTokenizerVerification() {
        fixture(quick) { root, selection ->
            Ct2ModelIntegrity.verify(root, 0, selection)
            for (file in quick) assertFailsWith<IllegalArgumentException>(file) {
                Ct2ModelIntegrity.verify(root, 0, selection.copy(options = selection.options - "sha256.$file"))
            }
            assertFailsWith<IllegalArgumentException> { Ct2ModelIntegrity.verify(root, 0, selection.copy(options = mapOf("sha256.config.json" to selection.options.getValue("sha256.config.json")))) }
            assertFailsWith<IllegalArgumentException> { Ct2ModelIntegrity.verify(root, 0, selection.copy(options = selection.options + ("sha256.model.bin" to "A".repeat(64)))) }
            assertFailsWith<IllegalArgumentException> { Ct2ModelIntegrity.verify(root, 0, selection.copy(options = selection.options + ("sha256../outside" to "a".repeat(64)))) }
            val original = Ct2ModelIntegrity.stamp(root, 0, selection)
            Files.writeString(root.resolve("src.spm.model"), "different tokenizer")
            assertNotEquals(original, Ct2ModelIntegrity.stamp(root, 0, selection))
            assertFailsWith<IllegalStateException> { Ct2ModelIntegrity.verify(root, 0, selection) }
        }
    }

    @Test fun nativeVocabularyOverridesAlsoRequirePins() {
        fixture(quick) { root, selection ->
            Files.writeString(root.resolve("shared_vocabulary.json"), "unverified override")
            assertFailsWith<IllegalArgumentException> { Ct2ModelIntegrity.verify(root, 0, selection) }
            Files.delete(root.resolve("shared_vocabulary.json"))
            Files.writeString(root.resolve("vmap.txt"), "unverified optional map")
            assertFailsWith<IllegalArgumentException> { Ct2ModelIntegrity.verify(root, 0, selection) }
        }
    }

    @Test fun everySupportedTokenizerModeHasACompleteInputInventory() {
        for (mode in 0..5) {
            val names = setOf("model.bin", "config.json", "shared_vocabulary.json") + when (mode) {
                0 -> setOf("src.spm.model", "tgt.spm.model")
                1 -> setOf("sentencepiece.bpe.model")
                else -> setOf("tokenizer.json")
            }
            fixture(names) { root, selection -> Ct2ModelIntegrity.verify(root, mode, selection) }
        }
    }

    private fun fixture(names: Set<String>, run: (Path, EngineSelection) -> Unit) {
        val root = Files.createTempDirectory("hearth-ct2-integrity-")
        try {
            val pins = names.associate { name ->
                val bytes = "fixture-$name".toByteArray()
                Files.write(root.resolve(name), bytes)
                "sha256.$name" to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }
            run(root, EngineSelection("marian-local", modelPath = root.toString(), options = pins))
        } finally { root.toFile().deleteRecursively() }
    }
}

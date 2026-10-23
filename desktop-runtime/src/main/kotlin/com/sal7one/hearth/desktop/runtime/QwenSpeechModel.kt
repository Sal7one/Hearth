package com.sal7one.hearth.desktop.runtime

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Pinned contract for the desktop Qwen3-ASR package.
 *
 * The six pins are the reviewed contents of the audited sherpa-onnx tar.bz2
 * (docs/model-catalog-audit-2026-10-19.json, id qwen3-asr-0.6b). Pins live here,
 * not in [com.sal7one.hearth.core.EngineSelection] options: saved selections pass
 * through the setup allowlist, and a model must be verified even when it is opened
 * from a directory that did not come from the catalog downloader.
 */
object QwenSpeechModel {
    const val ENGINE_ID = "qwen3-asr-local"
    const val LIBRARY = "hearth_desktop_qwen3_asr"
    const val PROTOCOL_VERSION = 1

    /** Mirrors common-jni/src/main/cpp/speech/qwen_language.h. */
    val languages: Set<String> = setOf(
        "zh", "en", "yue", "ar", "de", "fr", "es", "pt", "id", "it", "ko", "ru", "th", "vi", "ja",
        "tr", "hi", "ms", "nl", "sv", "da", "fi", "pl", "cs", "fil", "fa", "el", "hu", "mk", "ro",
    )

    data class PinnedFile(val path: String, val bytes: Long, val sha256: String)

    val files: List<PinnedFile> = listOf(
        PinnedFile("conv_frontend.onnx", 44_148_281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
        PinnedFile("encoder.int8.onnx", 182_491_662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
        PinnedFile("decoder.int8.onnx", 755_914_231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
        PinnedFile("tokenizer/merges.txt", 1_671_853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
        PinnedFile("tokenizer/vocab.json", 2_776_833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        PinnedFile("tokenizer/tokenizer_config.json", 12_487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
    )

    /** Normalize an app language code for Qwen; reject a language its prompt cannot express. */
    fun language(raw: String): String {
        val code = raw.trim().lowercase().replace('_', '-').substringBefore('-')
        require(code == "auto" || code in languages) { "Qwen3-ASR does not support this source language: $raw" }
        return code
    }

    /** Accept only a recognized code; a display name or unknown value never reaches a translator. */
    fun detected(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val code = text.lowercase().replace('_', '-').substringBefore('-')
        return code.takeIf { it in languages }
    }

    internal fun verify(root: Path, keepActive: () -> Unit = {}) {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Select the complete Qwen3-ASR model directory" }
        for (file in files) {
            keepActive()
            val target = root.resolve(file.path).normalize()
            require(target.startsWith(root.normalize())) { "Qwen3-ASR model path escapes its directory: ${file.path}" }
            var component = root
            for (part in file.path.split('/')) {
                component = component.resolve(part)
                require(!Files.isSymbolicLink(component)) { "Qwen3-ASR model contains a symbolic link: ${file.path}" }
            }
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && Files.size(target) == file.bytes) {
                "Missing or modified Qwen3-ASR model file: ${file.path}"
            }
            keepActive()
            require(sha256(target) == file.sha256) { "Qwen3-ASR model checksum mismatch: ${file.path}" }
        }
    }
}

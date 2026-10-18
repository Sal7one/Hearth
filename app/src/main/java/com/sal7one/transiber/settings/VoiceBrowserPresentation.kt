package com.sal7one.transiber.settings

import com.sal7one.transiber.models.ModelSearch

/** Voice identifiers remain verbatim; language options additionally match their English/native aliases. */
internal fun voiceMenuMatches(query: String, id: String, label: String): Boolean = ModelSearch.matches(
    query, id, label, if (Regex("[a-z]{2,3}(?:-[A-Za-z0-9]+)*").matches(id)) ModelSearch.languageTerms(listOf(id)) else "",
)

/** Publisher language inventories describe these examples, not an unchecked server connection. */
internal object VoiceBrowserLanguages {
    // Checked 2026-10-19: https://github.com/resemble-ai/chatterbox#supported-languages
    val chatterboxMultilingualV3 = "ar da de el en es fi fr he hi it ja ko ms nl no pl pt ru sv sw tr zh".split(' ').toSet()
    // Checked 2026-10-19: https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base#introduction
    val qwen3TtsBase = "zh en ja ko de fr ru pt es it".split(' ').toSet()
}

internal data class VoiceBrowserCard(
    val title: String,
    val runtime: String,
    val coverage: String,
    val license: String,
    val url: String,
    val languages: Set<String>,
) {
    // Coverage is display prose: e.g. "no Arabic" must never advertise Arabic support.
    fun matches(query: String): Boolean = ModelSearch.matches(
        query, title, runtime, license, url, "TTS text to speech self hosted",
        ModelSearch.languageTerms(languages),
    )
}

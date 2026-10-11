package com.sal7one.transiber.voice

import java.util.Locale

/** A standalone request does not mutate the speech/translation pipeline or shared voice choice. */
internal data class TtsPlaybackRequest(
    val text: String,
    val language: String,
    val choice: VoiceChoice,
    val gender: VoiceGender = VoiceGender.ANY,
    val volume: Float = 1f,
    val systemEngine: String? = null,
    val systemVoice: String? = null,
    val remoteVoice: String? = null,
) {
    fun validated(networkAllowed: Boolean): TtsPlaybackRequest {
        require(text.isNotBlank()) { "Enter text to speak" }
        require(text.length <= MAX_TEXT_CHARACTERS) { "Read aloud accepts at most $MAX_TEXT_CHARACTERS characters" }
        val locale = Locale.forLanguageTag(language)
        require(locale.language.isNotBlank() && locale.language != "und") { "Choose the language of this text" }
        require(choice.backend in setOf("system", "supertonic", "remote")) { "Unknown voice backend: ${choice.backend}" }
        check(choice.backend != "remote" || networkAllowed) { "Network voices are unavailable in the offline build" }
        require(choice.rate.isFinite() && choice.rate in .5f..2f) { "Voice speed must be between 0.5 and 2" }
        require(choice.steps in 2..12) { "Supertonic quality steps must be between 2 and 12" }
        require(volume.isFinite() && volume in 0f..1f) { "Voice volume must be between 0 and 100%" }
        require(systemEngine == null || systemEngine.matches(Regex("[A-Za-z0-9_.]+"))) { "Invalid Android voice engine" }
        require(systemVoice == null || systemVoice.length in 1..200 && !systemVoice.any(Char::isISOControl)) { "Invalid Android voice" }
        require(remoteVoice == null || remoteVoice.length in 1..120 && !remoteVoice.any(Char::isISOControl)) { "Invalid server voice" }
        return copy(language = locale.toLanguageTag())
    }

    companion object { const val MAX_TEXT_CHARACTERS = 5000 }
}

/** Discovery links describe compatible *external Android TTS engines*, never an installed Hearth model. */
internal object AndroidVoiceFamilies {
    data class Family(val name: String, val languages: String, val source: String)
    val families = listOf(
        Family("KittenTTS", "English", "https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kitten.html"),
        Family("Kokoro", "Multilingual", "https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html"),
        Family("Piper", "Per-language voices", "https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html"),
    )
    const val engineGuide = "https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html"
}

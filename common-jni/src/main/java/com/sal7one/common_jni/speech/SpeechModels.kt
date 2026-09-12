package com.sal7one.common_jni.speech

/** Runtime identity is separate from model format: arbitrary ONNX/GGUF files are not ASR models. */
enum class SpeechBackend(val id: String) {
    MOONSHINE("moonshine"), QWEN3_ASR("qwen3_asr"), NEMOTRON_3_5("nemotron_3_5")
}
enum class SpeechStreamingKind { UTTERANCE_WINDOWED, CACHE_AWARE }
enum class SpeechProfile(val id: String, val backend: SpeechBackend) {
    MOONSHINE_TINY_EN("moonshine-tiny-en-v2", SpeechBackend.MOONSHINE),
    MOONSHINE_BASE_EN("moonshine-base-en-v2", SpeechBackend.MOONSHINE),
    QWEN3_ASR_0_6B("qwen3-asr-0.6b", SpeechBackend.QWEN3_ASR),
    QWEN3_ASR_1_7B("qwen3-asr-1.7b", SpeechBackend.QWEN3_ASR),
    NEMOTRON_3_5_ASR_0_6B("nemotron-3.5-asr-0.6b", SpeechBackend.NEMOTRON_3_5);

    val capabilities: SpeechCapabilities get() = when (backend) {
        SpeechBackend.MOONSHINE -> SpeechCapabilities(SpeechStreamingKind.UTTERANCE_WINDOWED, false, setOf("en"), setOf("en"), true)
        SpeechBackend.QWEN3_ASR -> SpeechCapabilities(
            streaming = SpeechStreamingKind.UTTERANCE_WINDOWED,
            partialResults = false, sourceLanguages = QWEN_LANGUAGES, sourceLanguageHints = QWEN_LANGUAGES,
            configurableThreads = true,
        )
        SpeechBackend.NEMOTRON_3_5 -> SpeechCapabilities(
            streaming = SpeechStreamingKind.CACHE_AWARE,
            partialResults = true, sourceLanguages = NEMO_LANGUAGES, sourceLanguageHints = NEMO_LANGUAGES,
            configurableThreads = false,
        )
    }

    companion object {
        fun fromId(id: String): SpeechProfile = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unsupported speech model profile: $id")
        private val QWEN_LANGUAGES = "zh en yue ar de fr es pt id it ko ru th vi ja tr hi ms nl sv da fi pl cs fil fa el hu mk ro".split(' ').toSet()
        // Excludes NVIDIA's adaptation-only languages, which need fine-tuning.
        private val NEMO_LANGUAGES = "en es fr it pt nl de tr ru ar hi ja ko vi uk pl sv cs nb da bg fi hr sk zh hu ro et".split(' ').toSet()
    }
}
/** Recognition coverage and explicit language control are separate facts per runtime. */
data class SpeechSourceLanguage(val code: String, val canForce: Boolean)

data class SpeechCapabilities(
    val streaming: SpeechStreamingKind,
    val partialResults: Boolean,
    val sourceLanguages: Set<String>,
    val sourceLanguageHints: Set<String>,
    val configurableThreads: Boolean,
) {
    val languages: List<SpeechSourceLanguage> get() = sourceLanguages.map { SpeechSourceLanguage(it, it in sourceLanguageHints) }
    val sampleRate: Int get() = 16000
    val channels: Int get() = 1
    val translationTargets: Set<String> get() = emptySet()
    val wordTimestamps: Boolean get() = false
}

data class SpeechOptions(
    /** Spoken language, never the requested translation language. */
    val sourceLanguage: String = "auto",
    /** Qwen only; null selects the backend default. NeMo's C ABI has no thread-count option. */
    val numThreads: Int? = null,
    /** Nemotron lookahead: 0/1/3/6/13 frames, each 80ms. Not total caption latency. */
    val rightContext: Int = 3,
    /** Maximum utterance window; Nemotron forces a decoder endpoint while retaining loaded weights. */
    val maxUtteranceMs: Int = 4000,
    val silenceMs: Int = 600,
    val silenceThresholdDb: Float = -45f,
) {
    internal fun validate(profile: SpeechProfile) {
        require(numThreads == null || numThreads in 1..8) { "numThreads must be in 1..8" }
        require(numThreads == null || profile.capabilities.configurableThreads) { "Nemotron runtime does not expose per-session thread control" }
        require(rightContext in setOf(0, 1, 3, 6, 13)) { "rightContext must be 0, 1, 3, 6 or 13" }
        require(maxUtteranceMs in 1000..15000 && maxUtteranceMs % 20 == 0) { "maxUtteranceMs must be 1000..15000 in 20ms frames" }
        require(silenceMs in 200..2000 && silenceMs % 20 == 0) { "silenceMs must be 200..2000 in 20ms frames" }
        require(silenceThresholdDb.isFinite() && silenceThresholdDb in -100f..-10f) { "Invalid silenceThresholdDb" }
        val source = SpeechLanguage.normalize(sourceLanguage)
        require(source == "auto" || source in profile.capabilities.sourceLanguageHints) {
            "${profile.id} does not support this source-language hint: $sourceLanguage"
        }
    }
}

/** Normalize provider language names/locales without claiming an unknown language is English. */
object SpeechLanguage {
    private val names = "chinese:zh english:en cantonese:yue arabic:ar german:de french:fr spanish:es portuguese:pt indonesian:id italian:it korean:ko russian:ru thai:th vietnamese:vi japanese:ja turkish:tr hindi:hi malay:ms dutch:nl swedish:sv danish:da finnish:fi polish:pl czech:cs filipino:fil persian:fa greek:el hungarian:hu macedonian:mk romanian:ro mandarin:zh"
        .split(' ').associate { it.substringBefore(':') to it.substringAfter(':') }
    fun normalize(value: String): String {
        val v = value.trim().lowercase(java.util.Locale.ROOT).replace('_', '-')
        return names[v] ?: v.substringBefore('-')
    }
    internal fun nemoLocale(value: String): String {
        val explicit = value.trim().replace('_', '-')
        val localeVariants = listOf("en-US", "en-GB", "es-US", "es-ES", "fr-FR", "fr-CA", "pt-BR", "pt-PT")
        localeVariants.firstOrNull { it.equals(explicit, ignoreCase = true) }?.let { return it }
        val normalized = normalize(value)
        if (normalized == "auto") return "auto"
        val overrides = mapOf("en" to "en-US", "es" to "es-ES", "fr" to "fr-FR", "pt" to "pt-PT", "ar" to "ar-AR", "zh" to "zh-CN", "ja" to "ja-JP", "ko" to "ko-KR", "hi" to "hi-IN", "uk" to "uk-UA", "vi" to "vi-VN", "cs" to "cs-CZ", "sv" to "sv-SE", "da" to "da-DK", "nb" to "nb-NO", "et" to "et-EE")
        return overrides[normalized] ?: "$normalized-${normalized.uppercase(java.util.Locale.ROOT)}"
    }
}

data class SpeechAvailability(val backend: SpeechBackend, val available: Boolean, val revision: String?, val error: String?)
data class SpeechTranscript(
    val utteranceId: Long,
    val revision: Long,
    val text: String,
    val sourceLanguage: String?,
    val isFinal: Boolean,
    /** Position in the captured audio. This is not a word-alignment timestamp. */
    val audioEndSamples: Long,
)
data class SpeechUpdate(val transcripts: List<SpeechTranscript>, val acceptedSamples: Long, val inferenceNanos: Long)

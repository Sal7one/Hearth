package com.sal7one.common_jni.speech

/** Runtime identity is separate from model format: arbitrary ONNX/GGUF files are not ASR models. */
enum class SpeechBackend(val id: String) {
    MOONSHINE("moonshine"), QWEN3_ASR("qwen3_asr"), OMNILINGUAL_CTC("omnilingual_ctc"), PHONON("phonon"), TRANSCRIBEPSY("transcribepsy"), NEMOTRON_3_5("nemotron_3_5"),
    COHERE("cohere_asr"), FUNASR_MLT("funasr_mlt_nano"), VOXTRAL_REALTIME("voxtral_realtime"), AUDAR("audar_mtmd"), OWSM("owsm")
}
enum class SpeechStreamingKind { UTTERANCE_WINDOWED, CACHE_AWARE }
enum class SpeechAcceleration(val id: String) { CPU("cpu"), VULKAN("vulkan"), OPENCL("opencl") }
enum class SpeechProfile(val id: String, val backend: SpeechBackend) {
    MOONSHINE_TINY_EN("moonshine-tiny-en-v2", SpeechBackend.MOONSHINE),
    MOONSHINE_BASE_EN("moonshine-base-en-v2", SpeechBackend.MOONSHINE),
    MOONSHINE_BASE_AR("moonshine-base-ar-v2", SpeechBackend.MOONSHINE),
    MOONSHINE_BASE_ES("moonshine-base-es-v2", SpeechBackend.MOONSHINE),
    MOONSHINE_TINY_JA("moonshine-tiny-ja-v2", SpeechBackend.MOONSHINE),
    MOONSHINE_TINY_KO("moonshine-tiny-ko-v2", SpeechBackend.MOONSHINE),
    MOONSHINE_BASE_JA("moonshine-base-ja-v2", SpeechBackend.MOONSHINE),
    MOONSHINE_BASE_UK("moonshine-base-uk-v2", SpeechBackend.MOONSHINE),
    MOONSHINE_BASE_VI("moonshine-base-vi-v2", SpeechBackend.MOONSHINE),
    MOONSHINE_BASE_ZH("moonshine-base-zh-v2", SpeechBackend.MOONSHINE),
    QWEN3_ASR_0_6B("qwen3-asr-0.6b", SpeechBackend.QWEN3_ASR),
    QWEN3_ASR_1_7B("qwen3-asr-1.7b", SpeechBackend.QWEN3_ASR),
    OMNILINGUAL_CTC_300M_V2("omnilingual-ctc-300m-v2-int8", SpeechBackend.OMNILINGUAL_CTC),
    PHONON_2("phonon-2", SpeechBackend.PHONON),
    PHONON_2_5("phonon-2.5", SpeechBackend.PHONON),
    TRANSCRIBEPSY_250M_EU_SUB("transcribepsy-250m-eu-sub-q8", SpeechBackend.TRANSCRIBEPSY),
    NEMOTRON_3_5_ASR_0_6B("nemotron-3.5-asr-0.6b", SpeechBackend.NEMOTRON_3_5),
    COHERE_ARABIC_07_2026("cohere-transcribe-arabic-07-2026", SpeechBackend.COHERE),
    FUNASR_MLT_NANO_2512("fun-asr-mlt-nano-2512", SpeechBackend.FUNASR_MLT),
    VOXTRAL_MINI_REALTIME_2602("voxtral-mini-4b-realtime-2602", SpeechBackend.VOXTRAL_REALTIME),
    AUDAR_ASR_V1_FLASH("audar-asr-v1-flash", SpeechBackend.AUDAR),
    OWSM_V4_BASE_102M("owsm-v4-base-102m", SpeechBackend.OWSM);

    /** Monolingual checkpoints carry their source identity even when the caller chooses Auto. */
    val fixedSourceLanguage: String? get() = when (this) {
        MOONSHINE_TINY_EN, MOONSHINE_BASE_EN, PHONON_2 -> "en"
        MOONSHINE_BASE_AR -> "ar"
        MOONSHINE_BASE_ES -> "es"
        MOONSHINE_TINY_JA, MOONSHINE_BASE_JA -> "ja"
        MOONSHINE_TINY_KO -> "ko"
        MOONSHINE_BASE_UK -> "uk"
        MOONSHINE_BASE_VI -> "vi"
        MOONSHINE_BASE_ZH -> "zh"
        else -> null
    }

    /** A fixed-language model declares its language; this does not add a decoder language hint. */
    internal fun runtimeSourceLanguage(selection: String): String {
        val source = SpeechLanguage.normalize(selection)
        return if (source == "auto") fixedSourceLanguage ?: source else source
    }

    /** Recognition selection, including Auto, is validated consistently by UI/imports/runtime. */
    fun acceptsSource(selection: String): Boolean {
        val code = SpeechLanguage.normalize(selection)
        return if (code == "auto") capabilities.automaticSourceLanguage
        else code in capabilities.sourceLanguages
    }

    val capabilities: SpeechCapabilities get() = when (backend) {
        SpeechBackend.MOONSHINE -> SpeechCapabilities(SpeechStreamingKind.UTTERANCE_WINDOWED, false, setOf(requireNotNull(fixedSourceLanguage)), emptySet(), true)
        SpeechBackend.QWEN3_ASR -> SpeechCapabilities(
            streaming = SpeechStreamingKind.UTTERANCE_WINDOWED,
            partialResults = false, sourceLanguages = QWEN_LANGUAGES, sourceLanguageHints = QWEN_LANGUAGES,
            configurableThreads = true,
        )
        SpeechBackend.OMNILINGUAL_CTC -> SpeechCapabilities(
            streaming = SpeechStreamingKind.UTTERANCE_WINDOWED,
            partialResults = false, sourceLanguages = setOf("en", "ar", "ru", "zh"), sourceLanguageHints = emptySet(),
            configurableThreads = true,
        )
        SpeechBackend.PHONON -> SpeechCapabilities(
            streaming = SpeechStreamingKind.UTTERANCE_WINDOWED,
            partialResults = false,
            sourceLanguages = if (this == PHONON_2_5) PHONON_25_LANGUAGES else setOf("en"),
            // The transducer has no decoder language hint or language-identification output.
            sourceLanguageHints = emptySet(),
            configurableThreads = true,
        )
        SpeechBackend.TRANSCRIBEPSY -> SpeechCapabilities(
            streaming = SpeechStreamingKind.UTTERANCE_WINDOWED,
            partialResults = false, sourceLanguages = setOf("en", "nl", "fr", "de", "it", "pl", "pt", "es"),
            sourceLanguageHints = emptySet(), configurableThreads = true,
        )
        SpeechBackend.COHERE -> SpeechCapabilities(SpeechStreamingKind.UTTERANCE_WINDOWED, false,
            setOf("ar", "en"), setOf("ar", "en"), true, automaticSourceLanguage = false)
        SpeechBackend.AUDAR, SpeechBackend.OWSM -> SpeechCapabilities(SpeechStreamingKind.UTTERANCE_WINDOWED, false,
            setOf("ar", "en"), setOf("ar", "en"), true)
        SpeechBackend.FUNASR_MLT -> SpeechCapabilities(SpeechStreamingKind.UTTERANCE_WINDOWED, false,
            FUN_LANGUAGES, FUN_LANGUAGES, true)
        SpeechBackend.VOXTRAL_REALTIME -> SpeechCapabilities(SpeechStreamingKind.UTTERANCE_WINDOWED, true,
            "ar en fr es de ru zh ja it pt nl hi ko".split(' ').toSet(), emptySet(), true)
        SpeechBackend.NEMOTRON_3_5 -> SpeechCapabilities(
            streaming = SpeechStreamingKind.CACHE_AWARE,
            partialResults = true, sourceLanguages = NEMO_LANGUAGES, sourceLanguageHints = NEMO_LANGUAGES,
            configurableThreads = false,
        )
    }

    companion object {
        fun fromId(id: String): SpeechProfile = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unsupported speech model profile: $id")
        private val FUN_LANGUAGES = "ar en zh yue ja ko vi id th ms fil hi bg hr cs da nl et fi el hu ga lv lt mt pl pt ro sk sl sv".split(' ').toSet()
        private val PHONON_25_LANGUAGES = "en es fr de it pt nl pl ru uk ro cs sk bg hr sv da fi et lv lt hu sl mt el".split(' ').toSet()
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
    val automaticSourceLanguage: Boolean = true,
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
    /** Per-session CPU budget; null selects the backend default. NeMo exposes no thread-count option. */
    val numThreads: Int? = null,
    /** Nemotron lookahead: 0/1/3/6/13 frames, each 80ms. Not total caption latency. */
    val rightContext: Int = 3,
    /** Maximum utterance window; Nemotron forces a decoder endpoint while retaining loaded weights. */
    val maxUtteranceMs: Int = 4000,
    val silenceMs: Int = 600,
    val silenceThresholdDb: Float = -45f,
    /** Optional GPU engines are loaded only after explicit selection, never by CPU availability probes. */
    val acceleration: SpeechAcceleration = SpeechAcceleration.CPU,
    /** Writable app-private cache for compiled Adreno kernels. Only used by the explicit OpenCL runtime. */
    val gpuCacheDirectory: java.io.File? = null,
) {
    internal fun validate(profile: SpeechProfile) {
        require(numThreads == null || numThreads in 1..8) { "numThreads must be in 1..8" }
        require(numThreads == null || profile.capabilities.configurableThreads) { "Nemotron runtime does not expose per-session thread control" }
        require(rightContext in setOf(0, 1, 3, 6, 13)) { "rightContext must be 0, 1, 3, 6 or 13" }
        require(maxUtteranceMs in 1000..15000 && maxUtteranceMs % 20 == 0) { "maxUtteranceMs must be 1000..15000 in 20ms frames" }
        require(silenceMs in 200..2000 && silenceMs % 20 == 0) { "silenceMs must be 200..2000 in 20ms frames" }
        require(silenceThresholdDb.isFinite() && silenceThresholdDb in -100f..-10f) { "Invalid silenceThresholdDb" }
        require(acceleration == SpeechAcceleration.CPU || profile.backend == SpeechBackend.TRANSCRIBEPSY ||
            (acceleration == SpeechAcceleration.VULKAN && profile.backend in setOf(SpeechBackend.COHERE, SpeechBackend.FUNASR_MLT, SpeechBackend.VOXTRAL_REALTIME))) {
            "Unsupported speech backend acceleration"
        }
        val source = SpeechLanguage.normalize(sourceLanguage)
        require(profile.acceptsSource(source)) {
            "${profile.id} does not support this source-language selection: $sourceLanguage"
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

package com.sal7one.transiber.caption

import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.common_jni.translation.TranslationLanguages
import com.sal7one.transiber.byok.CloudConfigStore.SttMode

/** Runtime support is distinct from the language names/flags shown by the picker. */
data class CaptionLanguageChoices(val codes: Set<String>, val note: String, val automaticLanguages: Set<String> = emptySet())

object CaptionLanguages {
    fun source(config: CaptionOverlayConfig, cloudMode: SttMode): CaptionLanguageChoices = when (config.engine) {
        CaptionEngineChoice.NEMOTRON -> CaptionLanguageChoices(
            SpeechProfile.NEMOTRON_3_5_ASR_0_6B.capabilities.sourceLanguageHints + "auto",
            "Choose the language being spoken. Nemotron receives this hint; speed and accuracy depend on the audio.")
        CaptionEngineChoice.QWEN -> CaptionLanguageChoices(setOf("auto"),
            "This Qwen runtime accepts Auto only. These languages are recognized automatically; selecting one cannot speed up this adapter.",
            SpeechProfile.QWEN3_ASR_0_6B.capabilities.sourceLanguages)
        CaptionEngineChoice.VOSK -> CaptionLanguageChoices(setOf("model"),
            "Vosk uses its installed model’s language. Import or choose a different model to change it.")
        CaptionEngineChoice.WHISPER -> CaptionLanguageChoices(LanguageCatalog.whisperCodes + "auto",
            "A language hint skips Whisper’s detection step. English-only models still recognize English only.")
        CaptionEngineChoice.CLOUD -> when {
            cloudMode == SttMode.STREAMING_ASSEMBLYAI -> CaptionLanguageChoices(setOf("auto"),
                "The current AssemblyAI connection chooses the source language; this adapter has no language-hint control.")
            cloudMode == SttMode.STREAMING_OPENAI && config.mode == CaptionMode.TRANSLATE -> CaptionLanguageChoices(setOf("auto"),
                "OpenAI live translation detects the spoken language automatically.")
            cloudMode == SttMode.BATCH && config.mode == CaptionMode.TRANSLATE -> CaptionLanguageChoices(setOf("auto"),
                "The cloud audio-translation endpoint detects the spoken language automatically.")
            cloudMode == SttMode.STREAMING_DEEPGRAM -> CaptionLanguageChoices(deepgramCodes + "auto",
                "Auto uses Nova-3 multilingual mode (10 languages). Select a language for its dedicated recognition mode.")
            else -> CaptionLanguageChoices(LanguageCatalog.whisperCodes + "auto",
                "A language hint is sent to your provider. Supported languages depend on your selected cloud model.")
        }
    }

    // Nova-3 base codes, publisher documentation checked 2026-09-12. Locale variants remain provider-specific.
    val deepgramCodes = "af ar hy as be bn bs bg ca zh hr cs da nl en et fi fr ka de el gu he hi hu id it ja kn kk ko lv lt mk ms mr mn ne no ps fa pl pt pa ro ru sr sk sl es sv tl ta te th tr uk ur vi".split(' ').toSet()

    fun target(config: CaptionOverlayConfig, cloudMode: SttMode): CaptionLanguageChoices = when {
        config.engine.speechBackend != null -> {
            val spec = TranslationCatalog.models.firstOrNull { it.id == config.localTranslationModelId }
            CaptionLanguageChoices(spec?.targetLanguages ?: TranslationLanguages.hyLanguages,
                if (spec == null) "Choose a local translation model in Setup to translate into these languages."
                else "${spec.label}: ${spec.targetLanguages.size} output languages. The spoken language must also be supported by this translation model.")
        }
        config.engine == CaptionEngineChoice.CLOUD && cloudMode == SttMode.STREAMING_OPENAI ->
            CaptionLanguageChoices(setOf("en", "ar", "zh"), "Available output languages in this app’s live cloud translation setup.")
        config.engine == CaptionEngineChoice.WHISPER || (config.engine == CaptionEngineChoice.CLOUD && cloudMode == SttMode.BATCH) ->
            CaptionLanguageChoices(setOf("en", "ar"), "English uses audio translation. Arabic needs the separate English → Arabic translation model.")
        else -> CaptionLanguageChoices(setOf("ar"), "This path needs English speech and the English → Arabic translation model. Other speech stays CC-only.")
    }

    /** Never forward a remembered hint to an adapter which cannot accept it. */
    fun effectiveSource(config: CaptionOverlayConfig, cloudMode: SttMode): String {
        val selected = com.sal7one.common_jni.speech.SpeechLanguage.normalize(config.streamLanguage)
        return if (selected in source(config, cloudMode).codes && selected != "model") selected else "auto"
    }
}

package com.sal7one.transiber.caption

import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.common_jni.translation.TranslationLanguages
import com.sal7one.transiber.byok.CloudConfigStore.SttMode

/** Picker options come from recognition + adapter capabilities, never the display-name catalog. */
data class CaptionLanguageChoices(
    val codes: Set<String>,
    val note: String,
    val languages: List<com.sal7one.common_jni.speech.SpeechSourceLanguage> = emptyList(),
) {
    val allowsSelection: Boolean get() = codes.size > 1
}

/** The actual selected model, resolved identically before start and in both settings surfaces. */
data class CaptionLanguageModel(val id: String, val label: String = id, val whisperVocabulary: Int? = null)

object CaptionLanguages {
    private fun hinted(label: String, codes: Set<String>, explanation: String) = CaptionLanguageChoices(
        codes + "auto", "$label · $explanation",
        codes.map { com.sal7one.common_jni.speech.SpeechSourceLanguage(it, true) })
    private fun automatic(label: String, explanation: String) = CaptionLanguageChoices(setOf("auto"), "$label · $explanation")

    fun source(config: CaptionOverlayConfig, cloudMode: SttMode,
        model: CaptionLanguageModel = CaptionLanguageModel(config.modelId)): CaptionLanguageChoices = when (config.engine) {
        CaptionEngineChoice.MOONSHINE -> CaptionLanguageChoices(setOf("en"), "Moonshine · This model recognizes English only. No automatic language detection or override is needed.")
        CaptionEngineChoice.NEMOTRON, CaptionEngineChoice.QWEN -> {
            val profile = if (config.engine == CaptionEngineChoice.NEMOTRON) SpeechProfile.NEMOTRON_3_5_ASR_0_6B
                else if (model.id == SpeechProfile.QWEN3_ASR_1_7B.id) SpeechProfile.QWEN3_ASR_1_7B else SpeechProfile.QWEN3_ASR_0_6B
            val capabilities = profile.capabilities
            CaptionLanguageChoices(capabilities.languages.filter { it.canForce }.map { it.code }.toSet() + "auto",
                "${profile.label} · Choose a language to bypass automatic language selection, or choose Auto. Only languages supported by this model and runtime are listed.", capabilities.languages)
        }
        CaptionEngineChoice.VOSK -> CaptionLanguageChoices(setOf("model"),
            "${model.label.ifBlank { "Vosk" }} · The installed model fixes the spoken language. Change the model to change language; automatic detection and language overrides are unavailable.")
        CaptionEngineChoice.WHISPER -> when (model.whisperVocabulary) {
            51864 -> CaptionLanguageChoices(setOf("en"), "${model.label} · English-only model. Spoken language is fixed to English; no detection is needed.",
                listOf(com.sal7one.common_jni.speech.SpeechSourceLanguage("en", false)))
            51865, 51866 -> hinted(model.label, if (model.whisperVocabulary == 51865) LanguageCatalog.whisperCodes - "yue" else LanguageCatalog.whisperCodes,
                "Choose a language to skip detection, or choose Auto. Coverage comes from this model's vocabulary.")
            else -> automatic(model.label.ifBlank { "Whisper" }, "Language capabilities are unavailable until a supported model is imported. No language override will be sent.")
        }
        CaptionEngineChoice.CLOUD -> when {
            cloudMode == SttMode.STREAMING_SONIOX -> hinted("Soniox v5", sonioxCodes, "Select a language hint, or Auto. A hint guides recognition; it does not prohibit other languages.")
            cloudMode == SttMode.STREAMING_ELEVENLABS -> hinted("Scribe v2 Realtime", scribeCodes, "Select a spoken-language hint, or Auto. Select a source explicitly when using the local translation bridge.")
            cloudMode == SttMode.STREAMING_ASSEMBLYAI -> automatic("AssemblyAI streaming", "Automatic source language only in this connection. There is no manual language control.")
            cloudMode == SttMode.STREAMING_OPENAI && captionTranslationRoute(config,cloudMode) == CaptionTranslationRoute.LIVE_TARGET -> automatic("OpenAI live translation", "Detects the spoken language automatically. This translation connection does not accept a source-language override.")
            cloudMode == SttMode.BATCH && captionTranslationRoute(config,cloudMode).sttToEnglish -> automatic(model.label.ifBlank { "Cloud audio translation" }, "The audio-translation endpoint detects the source automatically; it accepts no language override.")
            cloudMode == SttMode.STREAMING_DEEPGRAM -> hinted("Deepgram Nova-3", deepgramCodes,
                "Select the spoken language for dedicated recognition. Auto uses multilingual mode (10 languages).")
            cloudMode == SttMode.STREAMING_OPENAI -> hinted("OpenAI gpt-live-transcribe", cloudTranscriptionCodes,
                "Select the spoken language to send an explicit recognition hint, or choose Auto.")
            model.id.substringAfterLast('/') == "whisper-1" || model.id.substringAfterLast('/').startsWith("whisper-large-v3") ||
                Regex("gpt-4o-(mini-)?transcribe(-[0-9]{4}-[0-9]{2}-[0-9]{2})?").matches(model.id.substringAfterLast('/')) -> hinted(model.label, cloudTranscriptionCodes,
                    "Select the spoken language to send an explicit recognition hint, or choose Auto.")
            else -> automatic(model.label.ifBlank { "Custom cloud model" }, "Manual spoken-language selection is not available for this model in this app. Recognition uses its default language behavior.")
        }
    }

    // Conservative common-language hint set for the explicitly identified cloud models above.
    // This is not a claim of exhaustive provider coverage; unknown models never inherit it.
    val cloudTranscriptionCodes = "af ar hy az be bs bg ca zh hr cs da nl en et fi fr gl de el he hi hu is id it ja kn kk ko lv lt mk ms mr mi ne no fa pl pt ro ru sr sk sl es sw sv tl ta th tr uk ur vi cy".split(' ').toSet()

    // Nova-3 base codes, publisher documentation checked 2026-09-12. Locale variants remain provider-specific.
    val deepgramCodes = "af ar hy as be bn bs bg ca zh hr cs da nl en et fi fr ka de el gu he hi hu id it ja kn kk ko lv lt mk ms mr mn ne no ps fa pl pt pa ro ru sr sk sl es sv tl ta te th tr uk ur vi".split(' ').toSet()

    val sonioxCodes = "af sq ar az eu be bn bs bg ca zh hr cs da nl en et fi fr gl de el gu he hi hu id it ja kn kk ko lv lt mk ms ml mr no fa pl pt pa ro ru sr sk sl es sw sv tl ta te th tr uk ur vi cy".split(' ').toSet()
    // Initial adapter language choices verified in Scribe's supported language list.
    val scribeCodes = setOf("en", "ar", "ru", "zh", "ja", "ko", "fr", "de", "es", "pt", "it", "hi", "tr", "uk", "nl", "pl", "sv", "fi", "da", "vi", "id", "ms", "th")

    fun target(config: CaptionOverlayConfig, cloudMode: SttMode,
               textLanguages: com.sal7one.transiber.translation.CloudTranslationLanguages? = null): CaptionLanguageChoices = when {
        config.textTranslationProviderId.isNotBlank() -> {
            val from = config.streamLanguage
            val known = from !in setOf("auto", "model", "und", "mul", "")
            if (config.textTranslationProviderId == "local") {
                val codes = com.sal7one.transiber.translation.TranslationOptions.languages(config.localTranslationModelId)
                CaptionLanguageChoices(if (known) codes.filter { com.sal7one.transiber.translation.TranslationOptions.supports(config.localTranslationModelId, from, it) }.toSet() else codes,
                    "${com.sal7one.transiber.translation.TranslationOptions.label(config.localTranslationModelId)} · text translation after speech recognition.")
            } else CaptionLanguageChoices(textLanguages?.targetLanguages.orEmpty().filter { !known || textLanguages?.supports(from,it) == true }.toSet(),
                if (textLanguages == null) "Set up this translation connection and check its languages first." else "Cloud text translation · supported targets from the chosen spoken language.")
        }
        config.engine == CaptionEngineChoice.CLOUD && cloudMode == SttMode.STREAMING_SONIOX ->
            CaptionLanguageChoices(sonioxCodes, "Soniox v5 integrated translation; original and translated text are retained separately.")
        config.engine == CaptionEngineChoice.CLOUD && cloudMode == SttMode.STREAMING_OPENAI ->
            CaptionLanguageChoices(setOf("en", "ar", "zh"), "Available output languages in this app’s live cloud translation setup.")
        captionTranslationRoute(config.copy(mode = CaptionMode.TRANSLATE), cloudMode) == CaptionTranslationRoute.TEXT_TRANSLATOR && config.localTranslationModelId == com.sal7one.transiber.translation.TranslationOptions.ML_KIT ->
            CaptionLanguageChoices(com.sal7one.transiber.translation.TranslationOptions.mlKitCodes, "ML Kit · download spoken and target language packs in Models. Non-English pairs translate through English.")
        config.engine.speechBackend != null || captionTranslationRoute(config.copy(mode = CaptionMode.TRANSLATE), cloudMode) == CaptionTranslationRoute.TEXT_TRANSLATOR -> {
            val spec = TranslationCatalog.models.firstOrNull { it.id == config.localTranslationModelId }
            CaptionLanguageChoices(spec?.targetLanguages ?: TranslationLanguages.hyLanguages,
                if (spec == null) "Choose a local translation model in Setup to translate into these languages."
                else "${spec.label}: ${spec.targetLanguages.size} output languages. The spoken language must also be supported by this translation model.")
        }
        config.engine == CaptionEngineChoice.WHISPER || (config.engine == CaptionEngineChoice.CLOUD && cloudMode == SttMode.BATCH) ->
            CaptionLanguageChoices(setOf("en", "ar"), "English uses audio translation. Arabic needs the separate English → Arabic translation model.")
        else -> CaptionLanguageChoices(setOf("ar"), "This path needs English speech and the English → Arabic translation model. Other speech stays CC-only.")
    }

    /** Never forward a remembered hint to an adapter which cannot accept it. */
    fun effectiveSource(config: CaptionOverlayConfig, cloudMode: SttMode, model: CaptionLanguageModel = CaptionLanguageModel(config.modelId)): String {
        val selected = com.sal7one.common_jni.speech.SpeechLanguage.normalize(config.streamLanguage)
        val choices = source(config, cloudMode, model)
        if (choices.codes == setOf("en")) return "en"
        return if (selected in choices.codes && selected != "model") selected else "auto"
    }
}

package com.sal7one.transiber.caption

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.UiMessage
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.CloudSpeechLanguages
import com.sal7one.transiber.byok.CloudConfigStore.SttMode

/** Picker options come from recognition + adapter capabilities, never the display-name catalog. */
data class CaptionLanguageChoices(
    val codes: Set<String>,
    val note: String,
    val languages: List<com.sal7one.common_jni.speech.SpeechSourceLanguage> = emptyList(),
    /** A provider-validated code field, not an assertion that every ISO language is supported. */
    val allowsLanguageCode: Boolean = false,
    val localizedNote: UiMessage? = null,
) {
    constructor(codes: Set<String>, note: UiMessage,
        languages: List<com.sal7one.common_jni.speech.SpeechSourceLanguage> = emptyList(),
        allowsLanguageCode: Boolean = false) : this(codes, note.toString(), languages, allowsLanguageCode, note)
    val allowsSelection: Boolean get() = codes.size > 1 || allowsLanguageCode
    fun accepts(code: String): Boolean = code in codes ||
        (allowsLanguageCode && CloudSpeechLanguages.isExplicitLanguageCode(code))
    fun customCode(query: String): String? = query.trim().lowercase(java.util.Locale.ROOT)
        .takeIf { allowsLanguageCode && CloudSpeechLanguages.isExplicitLanguageCode(it) && it !in codes }
    fun pickerCodes(selected: String): Set<String> = if (accepts(selected)) codes + selected else codes
}

/** The actual selected model, resolved identically before start and in both settings surfaces. */
data class CaptionLanguageModel(val id: String, val label: String = id, val whisperVocabulary: Int? = null)

object CaptionLanguages {
    val openAiTranslation: CaptionLanguageChoices get() = CaptionLanguageChoices(
        CloudSpeechLanguages.openAiTranslationSuggestions,
        UiMessage(UiR.string.capability_note_ace2e8aaad, "OpenAI checks the requested language when you connect. This list shows language names, not guaranteed model coverage. Search by name or enter a 2–3 letter language code."),
        allowsLanguageCode = true,
    )
    private fun hinted(label: String, codes: Set<String>, explanation: UiMessage) = CaptionLanguageChoices(
        codes + "auto", UiMessage(UiR.string.capability_note_c1aacd9629, "%1\$s · %2\$s", listOf(label, explanation)),
        codes.map { com.sal7one.common_jni.speech.SpeechSourceLanguage(it, true) })
    private fun automatic(label: String, explanation: UiMessage) = CaptionLanguageChoices(setOf("auto"), UiMessage(UiR.string.capability_note_c1aacd9629, "%1\$s · %2\$s", listOf(label, explanation)))

    fun source(config: CaptionOverlayConfig, cloudMode: SttMode,
        model: CaptionLanguageModel = CaptionLanguageModel(config.modelId)): CaptionLanguageChoices = when (config.engine) {
        CaptionEngineChoice.MOONSHINE -> CaptionLanguageChoices(setOf("en"), UiMessage(UiR.string.capability_note_67daa0fec5, "Moonshine · This model recognizes English only. No automatic language detection or override is needed."))
        CaptionEngineChoice.NEMOTRON, CaptionEngineChoice.QWEN -> {
            val profile = if (config.engine == CaptionEngineChoice.NEMOTRON) SpeechProfile.NEMOTRON_3_5_ASR_0_6B
                else if (model.id == SpeechProfile.QWEN3_ASR_1_7B.id) SpeechProfile.QWEN3_ASR_1_7B else SpeechProfile.QWEN3_ASR_0_6B
            val capabilities = profile.capabilities
            CaptionLanguageChoices(capabilities.languages.filter { it.canForce }.map { it.code }.toSet() + "auto",
                UiMessage(UiR.string.capability_note_492861ff93, "%1\$s · Choose a language to bypass automatic language selection, or choose Auto. Only languages supported by this model and runtime are listed.", listOf(profile.label)), capabilities.languages)
        }
        CaptionEngineChoice.VOSK -> CaptionLanguageChoices(setOf("model"),
            UiMessage(UiR.string.capability_note_8b97049ba5, "%1\$s · The installed model fixes the spoken language. Change the model to change language; automatic detection and language overrides are unavailable.", listOf(model.label.ifBlank { "Vosk" })))
        CaptionEngineChoice.WHISPER -> when (model.whisperVocabulary) {
            51864 -> CaptionLanguageChoices(setOf("en"), UiMessage(UiR.string.capability_note_302c201b8d, "%1\$s · English-only model. Spoken language is fixed to English; no detection is needed.", listOf(model.label)),
                listOf(com.sal7one.common_jni.speech.SpeechSourceLanguage("en", false)))
            51865, 51866 -> hinted(model.label, if (model.whisperVocabulary == 51865) LanguageCatalog.whisperCodes - "yue" else LanguageCatalog.whisperCodes,
                UiMessage(UiR.string.capability_note_6b9bbaddde, "Choose a language to skip detection, or choose Auto. Coverage comes from this model's vocabulary."))
            else -> automatic(model.label.ifBlank { "Whisper" }, UiMessage(UiR.string.capability_note_825ace0f79, "Language capabilities are unavailable until a supported model is imported. No language override will be sent."))
        }
        CaptionEngineChoice.CLOUD -> when {
            cloudMode == SttMode.STREAMING_SONIOX -> hinted("Soniox v5", CloudSpeechLanguages.sonioxCodes, UiMessage(UiR.string.capability_note_f03453f699, "Select a language hint, or Auto. A hint guides recognition; it does not prohibit other languages."))
            cloudMode == SttMode.STREAMING_ELEVENLABS -> hinted("Scribe v2 Realtime", CloudSpeechLanguages.scribeCodes, UiMessage(UiR.string.capability_note_a9bb5b597d, "Select a spoken-language hint, or Auto. Select a source explicitly when using the local translation bridge."))
            cloudMode == SttMode.STREAMING_ASSEMBLYAI -> automatic("AssemblyAI streaming", UiMessage(UiR.string.capability_note_8975a9a157, "Automatic source language only in this connection. There is no manual language control."))
            cloudMode == SttMode.STREAMING_OPENAI && captionTranslationRoute(config,cloudMode) == CaptionTranslationRoute.LIVE_TARGET -> automatic("OpenAI live translation", UiMessage(UiR.string.capability_note_1ffb589569, "Detects the spoken language automatically. This translation connection does not accept a source-language override."))
            cloudMode == SttMode.BATCH && captionTranslationRoute(config,cloudMode).sttToEnglish -> automatic(model.label.ifBlank { "Cloud audio translation" }, UiMessage(UiR.string.capability_note_e752f4c2db, "The audio-translation endpoint detects the source automatically; it accepts no language override."))
            cloudMode == SttMode.STREAMING_DEEPGRAM -> hinted("Deepgram Nova-3", CloudSpeechLanguages.deepgramCodes,
                UiMessage(UiR.string.capability_note_51deda856c, "Select the spoken language for dedicated recognition. Auto uses multilingual mode (10 languages)."))
            cloudMode == SttMode.STREAMING_OPENAI -> hinted("OpenAI gpt-live-transcribe", CloudSpeechLanguages.cloudTranscriptionCodes,
                UiMessage(UiR.string.capability_note_5715f3cacd, "Select the spoken language to send an explicit recognition hint, or choose Auto."))
            model.id.substringAfterLast('/') == "whisper-1" || model.id.substringAfterLast('/').startsWith("whisper-large-v3") ||
                Regex("gpt-4o-(mini-)?transcribe(-[0-9]{4}-[0-9]{2}-[0-9]{2})?").matches(model.id.substringAfterLast('/')) -> hinted(model.label, CloudSpeechLanguages.cloudTranscriptionCodes,
                    UiMessage(UiR.string.capability_note_5715f3cacd, "Select the spoken language to send an explicit recognition hint, or choose Auto."))
            else -> automatic(model.label.ifBlank { "Custom cloud model" }, UiMessage(UiR.string.capability_note_be018a0a4e, "Manual spoken-language selection is not available for this model in this app. Recognition uses its default language behavior."))
        }
    }

    fun target(config: CaptionOverlayConfig, cloudMode: SttMode,
               textLanguages: com.sal7one.transiber.translation.CloudTranslationLanguages? = null): CaptionLanguageChoices = when {
        config.textTranslationProviderId.isNotBlank() -> {
            val from = config.streamLanguage
            val known = from !in setOf("auto", "model", "und", "mul", "")
            if (config.textTranslationProviderId == "local") {
                val codes = com.sal7one.transiber.translation.TranslationOptions.languages(config.localTranslationModelId)
                CaptionLanguageChoices(if (known) codes.filter { com.sal7one.transiber.translation.TranslationOptions.supports(config.localTranslationModelId, from, it) }.toSet() else codes,
                    UiMessage(UiR.string.capability_note_de49da057d, "%1\$s · text translation after speech recognition.", listOf(com.sal7one.transiber.translation.TranslationOptions.label(config.localTranslationModelId))))
            } else CaptionLanguageChoices(textLanguages?.targetLanguages.orEmpty().filter { !known || textLanguages?.supports(from,it) == true }.toSet(),
                if (textLanguages == null) UiMessage(UiR.string.capability_note_444ec285c4, "Set up this translation connection and check its languages first.") else UiMessage(UiR.string.capability_note_017b414f07, "Cloud text translation · supported targets from the chosen spoken language."))
        }
        config.engine == CaptionEngineChoice.CLOUD && cloudMode == SttMode.STREAMING_SONIOX ->
            CaptionLanguageChoices(CloudSpeechLanguages.sonioxCodes, UiMessage(UiR.string.capability_note_0f1a227949, "Soniox v5 integrated translation; original and translated text are retained separately."))
        config.engine == CaptionEngineChoice.CLOUD && cloudMode == SttMode.STREAMING_OPENAI ->
            openAiTranslation
        captionTranslationRoute(config.copy(mode = CaptionMode.TRANSLATE), cloudMode) == CaptionTranslationRoute.TEXT_TRANSLATOR && config.localTranslationModelId == com.sal7one.transiber.translation.TranslationOptions.ML_KIT ->
            CaptionLanguageChoices(com.sal7one.transiber.translation.TranslationOptions.mlKitCodes, UiMessage(UiR.string.capability_note_b410e7fbf2, "ML Kit · download spoken and target language packs in Models. Non-English pairs translate through English."))
        config.engine.speechBackend != null || captionTranslationRoute(config.copy(mode = CaptionMode.TRANSLATE), cloudMode) == CaptionTranslationRoute.TEXT_TRANSLATOR -> {
            val spec = TranslationCatalog.models.firstOrNull { it.id == config.localTranslationModelId }
            CaptionLanguageChoices(spec?.targetLanguages.orEmpty(),
                if (spec == null) UiMessage(UiR.string.capability_note_0434e0f540, "Choose a known local translation model in Setup to load its languages.")
                else UiMessage(UiR.string.capability_note_38000e13f0, "%1\$s: %2\$s output languages. The spoken language must also be supported by this translation model.", listOf(spec.label, spec.targetLanguages.size)))
        }
        config.engine == CaptionEngineChoice.WHISPER || (config.engine == CaptionEngineChoice.CLOUD && cloudMode == SttMode.BATCH) ->
            CaptionLanguageChoices(setOf("en", "ar"), UiMessage(UiR.string.capability_note_c1004bf540, "English uses audio translation. Arabic needs the separate English → Arabic translation model."))
        else -> CaptionLanguageChoices(setOf("ar"), UiMessage(UiR.string.capability_note_949094c4d7, "This path needs English speech and the English → Arabic translation model. Other speech stays CC-only."))
    }

    /** Never forward a remembered hint to an adapter which cannot accept it. */
    fun effectiveSource(config: CaptionOverlayConfig, cloudMode: SttMode, model: CaptionLanguageModel = CaptionLanguageModel(config.modelId)): String {
        val selected = com.sal7one.common_jni.speech.SpeechLanguage.normalize(config.streamLanguage)
        val choices = source(config, cloudMode, model)
        if (choices.codes == setOf("en")) return "en"
        return if (selected in choices.codes && selected != "model") selected else "auto"
    }
}

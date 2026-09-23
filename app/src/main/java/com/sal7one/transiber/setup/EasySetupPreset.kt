package com.sal7one.transiber.setup

import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.translation.TranslationOptions
import com.sal7one.transiber.byok.CloudConfigStore
import com.sal7one.transiber.caption.*
import java.net.URI

/** Explicit, reversible presets; merely opening Easy setup never applies one. */
internal object EasySetupPreset {
    val providers = listOf(CloudConfigStore.Provider.OPENAI,CloudConfigStore.Provider.OPENROUTER,CloudConfigStore.Provider.CUSTOM)
    val speech = SpeechProfile.NEMOTRON_3_5_ASR_0_6B
    const val broadTranslatorId = "hy-mt2-q4"
    val broadTranslator get() = TranslationCatalog.find(broadTranslatorId)
    val fastPairs get() = MarianPackage.pairs
    fun local(previous: CaptionOverlayConfig, modelId: String, translatorId: String,
              source: String, target: String): CaptionOverlayConfig {
        require(modelId.isNotBlank()) { "Install Nemotron first." }
        require(source in speech.capabilities.sourceLanguageHints) { "Nemotron cannot force spoken language $source" }
        require(TranslationOptions.supports(translatorId, source, target)) {
            "${TranslationOptions.label(translatorId)} does not support $source → $target"
        }
        return previous.copy(engine=CaptionEngineChoice.NEMOTRON, modelId=modelId,
            mode=CaptionMode.TRANSLATE, streamLanguage=source, target=TranslationTarget.of(target),
            localTranslationEnabled=true, localTranslationModelId=translatorId, textTranslationProviderId="local")
    }
    fun cloud(previous: CaptionOverlayConfig, provider: CloudConfigStore.Provider, target: String): CaptionOverlayConfig {
        val live=provider==CloudConfigStore.Provider.OPENAI
        if(live) require(CaptionLanguages.openAiTranslation.accepts(target)) { "Unsupported live translation destination: $target" }
        return previous.copy(engine=CaptionEngineChoice.CLOUD, modelId="", streamLanguage="auto",
            mode=if(live) CaptionMode.TRANSLATE else CaptionMode.CAPTIONS,
            target=if(live) TranslationTarget.of(target) else previous.target,
            localTranslationEnabled=false, textTranslationProviderId="")
    }
    fun endpoint(value: String): String {
        val trimmed=value.trim().trimEnd('/')
        val uri=runCatching { URI(trimmed) }.getOrElse { error("Enter a valid HTTPS server URL.") }
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null) {
            "Use an HTTPS API base URL without embedded keys, a query or a fragment."
        }
        return trimmed
    }
    fun canReuseKey(chosen: CloudConfigStore.Provider, candidateEndpoint: String, saved: CloudConfigStore.Provider, savedEndpoint: String): Boolean =
        chosen==saved && runCatching { endpoint(candidateEndpoint)==endpoint(savedEndpoint) }.getOrDefault(false)
}

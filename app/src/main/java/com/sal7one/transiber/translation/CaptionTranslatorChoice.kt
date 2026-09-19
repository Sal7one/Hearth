package com.sal7one.transiber.translation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.byok.CloudConfigStore
import com.sal7one.transiber.caption.*

/** Capability refreshes update both the app picker and the already-open overlay picker. */
@Composable
internal fun captionTranslationChoices(config: CaptionOverlayConfig, mode: CloudConfigStore.SttMode): CaptionLanguageChoices {
    val context = LocalContext.current
    val revision by ConversationTranslationSettings.revision.collectAsState()
    return remember(config, mode, revision) {
        val provider = ConversationTranslationSettings.provider(config.textTranslationProviderId)
        CaptionLanguages.target(config, mode, provider?.let {
            if (ByokPolicy.FEATURE_BYOK) ConversationTranslationSettings.capabilities(context,it) else null
        })
    }
}

@Composable
internal fun captionCloudTranslatorReady(config: CaptionOverlayConfig): Boolean {
    val context = LocalContext.current
    val revision by ConversationTranslationSettings.revision.collectAsState()
    return remember(config.textTranslationProviderId, revision) {
        val provider = ConversationTranslationSettings.provider(config.textTranslationProviderId)
        ByokPolicy.FEATURE_BYOK && provider != null &&
            ConversationTranslationSettings.capabilities(context, provider) != null &&
            (provider == TextTranslationProvider.LIBRETRANSLATE || ConversationTranslationSettings.hasKey(context, provider))
    }
}

@Composable
internal fun CaptionTranslatorChooser(config: CaptionOverlayConfig,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    onModels: () -> Unit, enabled: Boolean = true) {
    if(config.engine==CaptionEngineChoice.VOSK) androidx.compose.material3.Text("Vosk imports do not provide source-language metadata to the translator yet. Use another speech engine for this text-translation stage.")
    TranslatorChooser(config.textTranslationProviderId, config.localTranslationModelId,
        "Used by live audio captions. Original speech text is translated after recognition. Legacy Whisper/batch needs an explicit spoken language for this text stage. Changing this does not change the camera or conversation provider.",
        source=config.streamLanguage, target=config.target.languageTag, enabled=enabled,
        automaticLabel=if (config.textTranslationProviderId.isBlank() && captionTranslationRoute(config.copy(mode=CaptionMode.TRANSLATE), com.sal7one.transiber.byok.CloudConfigStore.sttMode(androidx.compose.ui.platform.LocalContext.current)) == CaptionTranslationRoute.TEXT_TRANSLATOR) "${TranslationOptions.label(config.localTranslationModelId)} · existing route" else "Speech provider / existing route", onModels=onModels,
        onSelect={ provider, model -> update { it.copy(textTranslationProviderId=provider,
            localTranslationModelId=model, localTranslationEnabled=provider.isNotBlank() || it.effectiveEngine.speechBackend != null, mode=CaptionMode.TRANSLATE) } })
}

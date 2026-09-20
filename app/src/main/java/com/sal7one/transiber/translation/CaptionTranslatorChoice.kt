package com.sal7one.transiber.translation

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

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
    val uiText = rememberUiText()

    val cloudMode = CloudConfigStore.sttMode(LocalContext.current)
    val integrated = integratedCaptionProvider(config.engine, cloudMode)
    if(config.engine==CaptionEngineChoice.VOSK) androidx.compose.material3.Text(uiText(UiR.string.ui_vosk_imports_do_not_provide_source_language_metadata_to_the_trans_168ba))
    TranslatorChooser(config.textTranslationProviderId, config.localTranslationModelId,
        if (integrated != null) uiText(UiR.string.integrated_translation_detail, integrated) else uiText(UiR.string.ui_used_by_live_audio_captions_original_speech_text_is_translated_af_7413f),
        source=config.streamLanguage, target=config.target.languageTag, enabled=enabled,
        automaticLabel=if (integrated != null) uiText(UiR.string.integrated_translation_provider, integrated) else if (config.textTranslationProviderId.isBlank() && captionTranslationRoute(config.copy(mode=CaptionMode.TRANSLATE), com.sal7one.transiber.byok.CloudConfigStore.sttMode(androidx.compose.ui.platform.LocalContext.current)) == CaptionTranslationRoute.TEXT_TRANSLATOR) uiText(UiR.string.ui_1_s_existing_route_97965, TranslationOptions.label(config.localTranslationModelId)) else uiText(UiR.string.ui_speech_provider_existing_route_39ee3), onModels=onModels,
        onSelect={ provider, model -> update { it.copy(textTranslationProviderId=provider,
            localTranslationModelId=model, localTranslationEnabled=provider.isNotBlank() || it.effectiveEngine.speechBackend != null, mode=CaptionMode.TRANSLATE) } })
}

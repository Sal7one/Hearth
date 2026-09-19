package com.sal7one.transiber.caption

import com.sal7one.transiber.byok.CloudConfigStore.SttMode

internal enum class CaptionTranslationRoute(val sttToEnglish: Boolean = false, val secondStage: Boolean = false) {
    ORIGINAL,
    TEXT_TRANSLATOR,
    LIVE_TARGET,
    ENGLISH_TASK(sttToEnglish = true),
    ENGLISH_PIVOT(sttToEnglish = true, secondStage = true),
    ENGLISH_TEXT(secondStage = true),
    UNSUPPORTED,
}

internal fun captionTranslationRoute(config: CaptionOverlayConfig, cloudMode: SttMode): CaptionTranslationRoute {
    if (config.mode == CaptionMode.CAPTIONS) return CaptionTranslationRoute.ORIGINAL
    if (config.localTranslationEnabled && config.textTranslationProviderId.isNotBlank()) return CaptionTranslationRoute.TEXT_TRANSLATOR
    if (config.engine == CaptionEngineChoice.CLOUD && cloudMode in setOf(SttMode.STREAMING_OPENAI, SttMode.STREAMING_SONIOX)) return CaptionTranslationRoute.LIVE_TARGET
    if (config.localTranslationEnabled && config.localTranslationModelId.isNotBlank() &&
        (config.engine.speechBackend != null || (config.engine == CaptionEngineChoice.CLOUD && cloudMode != SttMode.BATCH))) return CaptionTranslationRoute.TEXT_TRANSLATOR
    if (config.engine.speechBackend != null) return if (config.localTranslationEnabled) CaptionTranslationRoute.TEXT_TRANSLATOR else CaptionTranslationRoute.UNSUPPORTED
    val whisperTask = config.engine == CaptionEngineChoice.WHISPER ||
        (config.engine == CaptionEngineChoice.CLOUD && cloudMode == SttMode.BATCH)
    return when {
        whisperTask && config.target == TranslationTarget.ENGLISH -> CaptionTranslationRoute.ENGLISH_TASK
        whisperTask && config.target == TranslationTarget.ARABIC -> CaptionTranslationRoute.ENGLISH_PIVOT
        config.target == TranslationTarget.ARABIC && config.streamLanguage.substringBefore('-') == "en" -> CaptionTranslationRoute.ENGLISH_TEXT
        else -> CaptionTranslationRoute.UNSUPPORTED
    }
}

/** Use the source validated while loading the actual model, not a model-less re-evaluation. */
internal fun captionTranslationSource(resolved: String, detected: String?): String? =
    resolved.takeUnless { it in setOf("auto", "model", "und", "mul", "") } ?: detected

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

internal fun captionTranslationRoute(saved: CaptionOverlayConfig, cloudMode: SttMode): CaptionTranslationRoute {
    val config = saved.withCaptionMode(saved.mode)
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

/** The integrated provider offered by this speech connection, regardless of an override. */
internal fun integratedCaptionProvider(engine: CaptionEngineChoice, mode: SttMode): String? =
    if (engine != CaptionEngineChoice.CLOUD) null else when (mode) {
        SttMode.STREAMING_OPENAI -> "OpenAI"
        SttMode.STREAMING_SONIOX -> "Soniox"
        else -> null
    }

/** Explicit engine selection chooses its default route; merely opening settings never does.
 * Saved weights remain installed/remembered. A translator chosen afterwards still overrides.
 */
internal fun CaptionOverlayConfig.selectCaptionEngine(next: CaptionEngineChoice, mode: SttMode): CaptionOverlayConfig {
    val integrated = integratedCaptionProvider(next, mode) != null
    return copy(engine = next, modelId = if (engine == next) modelId else "",
        speechSelectionRevision = speechSelectionRevision + 1,
        streamLanguage = if (integrated || next.speechBackend != null) "auto" else streamLanguage,
        textTranslationProviderId = if (integrated) "" else textTranslationProviderId,
        localTranslationEnabled = if (integrated) false else localTranslationEnabled)
        .withCaptionMode(this.mode)
}

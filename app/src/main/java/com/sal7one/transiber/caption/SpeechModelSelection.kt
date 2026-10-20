package com.sal7one.transiber.caption

import com.sal7one.common_jni.speech.SpeechProfile

/** Explicit STT activation changes only that stage and preserves a valid spoken-language choice. */
internal fun activateSpeechModel(previous: CaptionOverlayConfig, profile: SpeechProfile, modelId: String): CaptionOverlayConfig {
    val source = previous.streamLanguage.takeIf(profile::acceptsSource)
        ?: if (profile.capabilities.automaticSourceLanguage) "auto" else profile.capabilities.sourceLanguages.sorted().first()
    return previous.copy(engine = profile.captionEngine, modelId = modelId, streamLanguage = source)
}

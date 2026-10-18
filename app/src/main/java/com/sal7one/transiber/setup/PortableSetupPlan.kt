package com.sal7one.transiber.setup

import com.sal7one.transiber.caption.*

/** Explicit application semantics, shared by bundles and imported recipes. */
internal object PortableSetupPlan {
    fun apply(previous: CaptionOverlayConfig, setup: PortableSetup, state: PortableSetupPreview): CaptionOverlayConfig {
        check(state.ready) { "Setup is not ready to apply" }
        var next = previous
        setup.speech?.let { next = next.copy(engine = checkNotNull(state.speechEngine), modelId = checkNotNull(state.speechModelId), streamLanguage = it.sourceLanguage) }
        setup.translation?.let { stage -> next = next.copy(localTranslationEnabled = stage.enabled,
            localTranslationModelId = if (stage.modelId.startsWith("provider-")) previous.localTranslationModelId else stage.modelId,
            textTranslationProviderId = if (stage.modelId.startsWith("provider-")) stage.modelId.removePrefix("provider-") else "local",
            target = TranslationTarget.of(stage.targetLanguage)) }
        // A speech-only setup explicitly means original captions. A text-only setup never needs or changes STT.
        if (setup.speech != null) next = next.copy(mode = if (setup.translation?.enabled == true) CaptionMode.TRANSLATE else CaptionMode.CAPTIONS,
            localTranslationEnabled = setup.translation?.enabled == true)
        setup.voice?.let { next = next.copy(speakCaptions = it.enabled, speakerChoice = it.choice, speakerGender = it.gender, speakerVolume = it.volume) }
        return next
    }
}

package com.sal7one.transiber.caption

import com.sal7one.common_jni.speech.SpeechProfile
import org.junit.Assert.*
import org.junit.Test

class SpeechModelSelectionTest {
    @Test fun explicitSpeechActivationPreservesEveryOtherPipelineAndPresentationSetting() {
        val before = CaptionOverlayConfig(mode = CaptionMode.TRANSLATE, engine = CaptionEngineChoice.CLOUD,
            modelId = "previous", streamLanguage = "ar", localTranslationEnabled = true,
            localTranslationModelId = "translator", textTranslationProviderId = "local", target = TranslationTarget.ENGLISH)
        val after = activateSpeechModel(before, SpeechProfile.AUDAR_ASR_V1_FLASH, "speech-new")
        assertEquals(before.copy(engine = CaptionEngineChoice.AUDAR, modelId = "speech-new"), after)
    }
    @Test fun unsupportedPreviousLanguagesBecomeValidForTheChosenModel() {
        val previous = CaptionOverlayConfig(streamLanguage = "de")
        assertEquals("auto", activateSpeechModel(previous, SpeechProfile.AUDAR_ASR_V1_FLASH, "id").streamLanguage)
        assertEquals("auto", activateSpeechModel(previous, SpeechProfile.MOONSHINE_BASE_EN, "id").streamLanguage)
        assertEquals("ar", activateSpeechModel(previous, SpeechProfile.COHERE_ARABIC_07_2026, "id").streamLanguage)
        assertEquals("en", activateSpeechModel(previous.copy(streamLanguage = "en"), SpeechProfile.OWSM_V4_BASE_102M, "id").streamLanguage)
    }
}

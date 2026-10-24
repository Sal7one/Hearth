package com.sal7one.transiber.caption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CaptionSpeechTimingRevisionTest {
    private val selected = CaptionOverlayConfig(engine = CaptionEngineChoice.PHONON, modelId = "selected-phonon",
        speechSelectionRevision = 7, mode = CaptionMode.TRANSLATE, textTranslationProviderId = "local",
        localTranslationModelId = "ct2-quickmt-en-ar", target = TranslationTarget.ARABIC,
        minimalHeader = true, showSettings = true)

    @Test fun savedTimingRestartsOnlySpeechWithoutChangingTranslatorLanguagesOrAppearance() {
        val applied = selected.afterSpeechTimingSaved(CaptionEngineChoice.PHONON, "selected-phonon")
        assertEquals(selected.copy(speechSelectionRevision = 8), applied)
    }

    @Test fun saveFinishingAfterAnotherPackageWasSelectedCannotRestartTheNewPackage() {
        val switched = selected.copy(modelId = "another-phonon")
        assertSame(switched, switched.afterSpeechTimingSaved(CaptionEngineChoice.PHONON, "selected-phonon"))
    }

    @Test fun saveFinishingAfterAnotherEngineWasSelectedCannotRestartThatEngine() {
        val switched = selected.copy(engine = CaptionEngineChoice.QWEN)
        assertSame(switched, switched.afterSpeechTimingSaved(CaptionEngineChoice.PHONON, "selected-phonon"))
    }
}

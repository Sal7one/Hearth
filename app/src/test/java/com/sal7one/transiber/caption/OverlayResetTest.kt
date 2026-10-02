package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class OverlayResetTest {
    @Test fun resetRecoversPresentationWithoutChangingThePipelineOrLiveSession() {
        val configured = CaptionOverlayConfig(
            mode = CaptionMode.TRANSLATE, source = CaptionSource.MIC,
            engine = CaptionEngineChoice.NEMOTRON, modelId = "installed-speech",
            localTranslationEnabled = true, localTranslationModelId = "installed-translator",
            textTranslationProviderId = "saved-connection", streamLanguage = "ar",
            target = TranslationTarget.of("es"), display = CaptionDisplay.TRANSLATED,
            speechSelectionRevision = 7, paused = true, showSettings = true,
            speakCaptions = true, speakerChoice = CaptionSpeakerChoice.SHARED,
            speakerGender = SpeakerGender.FEMALE, speakerVolume = 35,
            widthPercent = 100, bubbleHeightDp = 144, maxHeightPercent = 20,
            anchor = CaptionAnchor.TOP, xOffsetPx = 900, yOffsetPx = -800,
            fontScale = CaptionFontScale.HUGE, historyLines = 8, showPartial = false,
            theme = CaptionTheme.LIGHT, backgroundOpacity = 0, tapThrough = true,
            languagePicker = CaptionLanguagePicker.TARGET,
        )
        val reset = configured.resetOverlayPresentation(OverlayOrientation.PORTRAIT)
        assertEquals(configured.copy(
            widthPercent = 85, maxHeightPercent = 40, bubbleHeightDp = null,
            anchor = CaptionAnchor.BOTTOM, xOffsetPx = 0, yOffsetPx = 0,
            fontScale = CaptionFontScale.NORMAL, historyLines = CaptionReading.DEFAULT_PREVIOUS_LINES,
            showPartial = true, theme = CaptionTheme.DARK, backgroundOpacity = 70,
            tapThrough = false, languagePicker = null,
        ), reset)
        assertTrue(reset.showSettings && reset.paused && reset.speakCaptions)
    }

    @Test fun landscapeResetPreservesPortraitGeometry() {
        val prefs = mutablePreferencesOf()
        val portrait = CaptionOverlayConfig(widthPercent = 90, bubbleHeightDp = 220)
        CaptionConfigStore.writeInto(prefs, portrait, OverlayOrientation.PORTRAIT)
        val reset = portrait.resetOverlayPresentation(OverlayOrientation.LANDSCAPE)
        CaptionConfigStore.writeInto(prefs, reset, OverlayOrientation.LANDSCAPE)
        val savedLandscape = CaptionConfigStore.readFrom(prefs, OverlayOrientation.LANDSCAPE)
        assertEquals(40, savedLandscape.widthPercent)
        assertEquals(60, savedLandscape.maxHeightPercent)
        assertNull(savedLandscape.bubbleHeightDp)
        val savedPortrait = CaptionConfigStore.readFrom(prefs, OverlayOrientation.PORTRAIT)
        assertEquals(90, savedPortrait.widthPercent)
        assertEquals(220, savedPortrait.bubbleHeightDp)
    }

    @Test fun resetDoesNotResurrectTheLegacyCompactHeight() {
        val prefs = mutablePreferencesOf(intPreferencesKey("bubble_height_dp") to 144)
        val old = CaptionConfigStore.readFrom(prefs)
        assertEquals(144, old.bubbleHeightDp)
        CaptionConfigStore.writeInto(prefs, old.resetOverlayPresentation(OverlayOrientation.PORTRAIT))
        val saved = CaptionConfigStore.readFrom(prefs)
        assertNull(saved.bubbleHeightDp)
        assertEquals(640, overlayHeightPx(1800, 2f, saved))
    }
}

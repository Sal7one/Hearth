package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.*
import org.junit.Test

class MinimalCaptionOverlayConfigTest {
    @Test fun oldInstallsRetainTheirNormalHeightAndMinimalHeaderStartsOptedOut() {
        val prefs = mutablePreferencesOf(intPreferencesKey("bubble_height_dp") to 220)
        for (orientation in OverlayOrientation.entries) {
            val config = CaptionConfigStore.readFrom(prefs, orientation)
            assertFalse(config.minimalHeader)
            assertEquals(64, config.minimalHeightDp)
            assertFalse(config.minimalHeaderExpanded)
        }
        assertEquals(220, CaptionConfigStore.readFrom(prefs).bubbleHeightDp)
    }

    @Test fun minimalPresentationIsGlobalButBothReadingHeightsRemainIndependentPerOrientation() {
        val prefs = mutablePreferencesOf()
        val portrait = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 80, bubbleHeightDp = 260)
        CaptionConfigStore.writeInto(prefs, portrait, OverlayOrientation.PORTRAIT)
        val initialLandscape = CaptionConfigStore.readFrom(prefs, OverlayOrientation.LANDSCAPE)
        assertTrue(initialLandscape.minimalHeader)
        assertEquals(64, initialLandscape.minimalHeightDp)
        assertNull(initialLandscape.bubbleHeightDp)

        CaptionConfigStore.writeInto(prefs, initialLandscape.copy(minimalHeightDp = 104, bubbleHeightDp = 180), OverlayOrientation.LANDSCAPE)
        val restoredPortrait = CaptionConfigStore.readFrom(prefs, OverlayOrientation.PORTRAIT)
        val restoredLandscape = CaptionConfigStore.readFrom(prefs, OverlayOrientation.LANDSCAPE)
        assertEquals(80, restoredPortrait.minimalHeightDp); assertEquals(260, restoredPortrait.bubbleHeightDp)
        assertEquals(104, restoredLandscape.minimalHeightDp); assertEquals(180, restoredLandscape.bubbleHeightDp)

        CaptionConfigStore.writeInto(prefs, restoredLandscape.copy(minimalHeader = false), OverlayOrientation.LANDSCAPE)
        val standard = CaptionConfigStore.readFrom(prefs, OverlayOrientation.PORTRAIT)
        assertFalse(standard.minimalHeader)
        assertEquals(80, standard.minimalHeightDp); assertEquals(260, standard.bubbleHeightDp)
        CaptionConfigStore.writeInto(prefs, standard.copy(minimalHeader = true), OverlayOrientation.PORTRAIT)
        assertEquals(104, CaptionConfigStore.readFrom(prefs, OverlayOrientation.LANDSCAPE).minimalHeightDp)
    }

    @Test fun expansionNeverChangesStoredPreferencesAndAFreshSessionAlwaysStartsCollapsed() {
        val collapsed = mutablePreferencesOf(); val expanded = mutablePreferencesOf()
        val config = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 72, bubbleHeightDp = 190)
        CaptionConfigStore.writeInto(collapsed, config)
        CaptionConfigStore.writeInto(expanded, config.copy(minimalHeaderExpanded = true))
        assertEquals(collapsed.asMap(), expanded.asMap())
        assertFalse(CaptionConfigStore.readFrom(expanded).minimalHeaderExpanded)
        assertEquals(72, CaptionConfigStore.readFrom(expanded).minimalHeightDp)
    }

    @Test fun compactHeightIsBoundedOnReadAndWriteWithoutChangingStandardHeight() {
        val prefs = mutablePreferencesOf()
        for ((input, expected) in listOf(Int.MIN_VALUE to 48, 47 to 48, 90 to 90, Int.MAX_VALUE to 600)) {
            val config = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = input, bubbleHeightDp = 280)
            assertEquals(expected, config.withUiClamp().minimalHeightDp)
            CaptionConfigStore.writeInto(prefs, config)
            assertEquals(expected, CaptionConfigStore.readFrom(prefs).minimalHeightDp)
            assertEquals(280, CaptionConfigStore.readFrom(prefs).bubbleHeightDp)
        }
        prefs[intPreferencesKey("minimal_height_portrait_v1")] = -500
        assertEquals(48, CaptionConfigStore.readFrom(prefs).minimalHeightDp)
    }

    @Test fun liveExpansionSurvivesStorageUpdatesAndRotationButTurningMinimalOffCollapsesIt() {
        val live = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 80, minimalHeaderExpanded = true,
            paused = true, tapThrough = true, showSettings = true, languagePicker = CaptionLanguagePicker.TARGET)
        val stored = live.copy(minimalHeaderExpanded = false, paused = false, tapThrough = false,
            showSettings = false, languagePicker = null, fontScale = CaptionFontScale.LARGE)
        val merged = stored.withLiveOverlayState(live, CaptionSource.MIC)
        assertTrue(merged.minimalHeaderExpanded && merged.paused && merged.tapThrough && merged.showSettings)
        assertEquals(CaptionSource.MIC, merged.source)
        assertEquals(CaptionFontScale.LARGE, merged.fontScale)
        val rotated = merged.withStoredGeometry(CaptionOverlayConfig(minimalHeightDp = 112, bubbleHeightDp = 170))
        assertEquals(112, rotated.minimalHeightDp); assertEquals(170, rotated.bubbleHeightDp)
        assertTrue(rotated.minimalHeader && rotated.minimalHeaderExpanded && rotated.paused && rotated.showSettings)
        assertFalse(stored.copy(minimalHeader = false).withLiveOverlayState(live, null).minimalHeaderExpanded)
        assertFalse(stored.withLiveOverlayState(live.copy(minimalHeader = false), null).minimalHeaderExpanded)
    }

    @Test fun presentationResetExitsMinimalModeAndKeepsModelAndLanguageChoices() {
        val config = CaptionOverlayConfig(minimalHeader = true, minimalHeightDp = 160, minimalHeaderExpanded = true,
            bubbleHeightDp = 260, engine = CaptionEngineChoice.NEMOTRON, modelId = "saved-speech",
            textTranslationProviderId = "local", localTranslationModelId = "saved-translator",
            mode = CaptionMode.TRANSLATE, target = TranslationTarget.ARABIC, streamLanguage = "en", paused = true)
        val reset = config.resetOverlayPresentation(OverlayOrientation.LANDSCAPE)
        assertFalse(reset.minimalHeader); assertFalse(reset.minimalHeaderExpanded); assertEquals(64, reset.minimalHeightDp)
        assertNull(reset.bubbleHeightDp)
        assertEquals(config.engine, reset.engine); assertEquals(config.modelId, reset.modelId)
        assertEquals(config.textTranslationProviderId, reset.textTranslationProviderId)
        assertEquals(config.localTranslationModelId, reset.localTranslationModelId)
        assertEquals(config.target, reset.target); assertEquals(config.streamLanguage, reset.streamLanguage)
        assertEquals(config.mode, reset.mode); assertTrue(reset.paused)
    }
}

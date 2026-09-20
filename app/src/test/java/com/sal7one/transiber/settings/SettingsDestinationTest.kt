package com.sal7one.transiber.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsDestinationTest {
    @Test fun offlineSettingsOfferNoCloudDestination() {
        assertEquals(listOf(SettingsLocation.LOCAL), settingsLocations(false))
    }

    @Test fun connectedBuildKeepsLocalFirstAndCloudSeparate() {
        assertEquals(listOf(SettingsLocation.LOCAL, SettingsLocation.CLOUD), settingsLocations(true))
    }

    @Test fun ocrRemainsLocalWhileSpeechTranslationAndVoicesHaveBothRoutes() {
        assertTrue(SettingsFeature.CAMERA in settingsFeatures(SettingsLocation.LOCAL))
        assertFalse(SettingsFeature.CAMERA in settingsFeatures(SettingsLocation.CLOUD))
        assertEquals(setOf(SettingsFeature.SPEECH, SettingsFeature.TRANSLATION, SettingsFeature.VOICES),
            settingsFeatures(SettingsLocation.CLOUD).toSet())
        assertTrue(settingsFeatures(SettingsLocation.LOCAL).containsAll(settingsFeatures(SettingsLocation.CLOUD)))
    }
    @Test fun explicitCloudEntryOverridesLastBrowsedLocalTab() {
        val state = SettingsTabState(SettingsLocation.LOCAL, 4)
        assertEquals(SettingsTabState(SettingsLocation.CLOUD, 5), state.enter(SettingsLocation.CLOUD, 5, true))
    }

    @Test fun returningFromModelSetupKeepsTheBrowsedTab() {
        val state = SettingsTabState(SettingsLocation.LOCAL, 4)
        assertEquals(state, state.enter(SettingsLocation.CLOUD, 4, true))
    }

    @Test fun restoringCloudStateInOfflineBuildClampsToLocal() {
        val state = SettingsTabState(SettingsLocation.CLOUD, 4)
        assertEquals(SettingsTabState(SettingsLocation.LOCAL, 4), state.enter(SettingsLocation.CLOUD, 4, false))
        assertEquals(SettingsTabState(SettingsLocation.LOCAL, 5), state.enter(SettingsLocation.CLOUD, 5, false))
    }
}

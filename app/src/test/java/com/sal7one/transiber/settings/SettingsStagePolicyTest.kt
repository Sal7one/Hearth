package com.sal7one.transiber.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsStagePolicyTest {
    @Test fun offlineStagesNeverOfferCloudEvenWhenTheSavedChoiceWasCloud() {
        SettingsFeature.entries.forEach { feature ->
            assertFalse(settingsStageLocations(feature, false, true).contains(SettingsLocation.CLOUD))
            assertEquals(SettingsLocation.LOCAL, settingsStageDestination(SettingsLocation.CLOUD, feature, false, true))
        }
    }

    @Test fun televisionSpeechAndTranslationOpenCloudWithoutALocalTabDetour() {
        listOf(SettingsFeature.SPEECH, SettingsFeature.TRANSLATION).forEach { feature ->
            assertEquals(listOf(SettingsLocation.CLOUD), settingsStageLocations(feature, true, false))
            assertEquals(SettingsLocation.CLOUD, settingsStageDestination(SettingsLocation.LOCAL, feature, true, false))
        }
        assertTrue(settingsStageLocations(SettingsFeature.CAMERA, true, false).isEmpty())
        assertEquals(listOf(SettingsLocation.LOCAL, SettingsLocation.CLOUD), settingsStageLocations(SettingsFeature.VOICES, true, false))
    }

    @Test fun unsupportedOfflineTvStagesHaveNoAccidentalNetworkFallback() {
        assertNull(settingsStageDestination(SettingsLocation.CLOUD, SettingsFeature.SPEECH, false, false))
        assertEquals(SettingsLocation.LOCAL, settingsStageDestination(SettingsLocation.CLOUD, SettingsFeature.VOICES, false, false))
    }
}

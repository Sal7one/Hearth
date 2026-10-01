package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import com.sal7one.transiber.voice.VoiceGender
import com.sal7one.transiber.voice.VoiceGenderMapping
import org.junit.Assert.*
import org.junit.Test

class SpeakerSettingsTest {
    @Test fun genderAndVolumePersistAndClampThroughTheConfigStore() {
        val prefs = mutablePreferencesOf()
        val withPrefs = CaptionConfigStore.readFrom(
            prefs.also {
                it[stringPreferencesKey("engine")] = "PHONON"
                it[stringPreferencesKey("speaker_gender_v1")] = "FEMALE"
                it[intPreferencesKey("speaker_volume_v1")] = 140
            })
        assertEquals(SpeakerGender.FEMALE, withPrefs.speakerGender)
        assertEquals("stored volume clamps to 100", 100, withPrefs.speakerVolume)

        val out = mutablePreferencesOf()
        CaptionConfigStore.writeInto(out, withPrefs.copy(speakerGender = SpeakerGender.MALE, speakerVolume = 40))
        assertEquals("MALE", out[stringPreferencesKey("speaker_gender_v1")])
        assertEquals(40, out[intPreferencesKey("speaker_volume_v1")])

        val legacy = CaptionConfigStore.readFrom(mutablePreferencesOf())
        assertEquals(SpeakerGender.ANY, legacy.speakerGender)
        assertEquals(100, legacy.speakerVolume)

        val corrupt = CaptionConfigStore.readFrom(
            mutablePreferencesOf().also { it[stringPreferencesKey("speaker_gender_v1")] = "ROBOT" })
        assertEquals(corrupt.speakerGender, SpeakerGender.ANY)
    }

    @Test fun supertonicGenderSwapsPrefixKeepingTheVoiceIndex() {
        assertEquals("M3", VoiceGenderMapping.supertonicVoice("F3", VoiceGender.MALE))
        assertEquals("F5", VoiceGenderMapping.supertonicVoice("M5", VoiceGender.FEMALE))
        assertEquals("F2", VoiceGenderMapping.supertonicVoice("F2", VoiceGender.FEMALE))
        assertEquals("F1", VoiceGenderMapping.supertonicVoice("F1", VoiceGender.ANY))
        assertEquals("F1", VoiceGenderMapping.supertonicVoice("garbage", VoiceGender.FEMALE))
        assertEquals("M2", VoiceGenderMapping.supertonicVoice("M2", VoiceGender.ANY))
    }

    @Test fun cloudGenderKeepsConfiguredVoiceWhenItMatchesOtherwisePicksACanonicalOne() {
        assertEquals("nova", VoiceGenderMapping.cloudVoice("alloy", VoiceGender.FEMALE))
        assertEquals("shimmer", VoiceGenderMapping.cloudVoice("shimmer", VoiceGender.FEMALE))
        assertEquals("echo", VoiceGenderMapping.cloudVoice("nova", VoiceGender.MALE))
        assertEquals("onyx", VoiceGenderMapping.cloudVoice("onyx", VoiceGender.MALE))
        assertEquals("alloy", VoiceGenderMapping.cloudVoice("alloy", VoiceGender.ANY))
    }

    @Test fun systemGenderHeuristicMatchesFemaleBeforeMaleInsideFemaleNames() {
        assertTrue(VoiceGenderMapping.systemVoiceMatches("en-us-x-female-local", VoiceGender.FEMALE))
        assertFalse(VoiceGenderMapping.systemVoiceMatches("en-us-x-female-local", VoiceGender.MALE))
        assertTrue(VoiceGenderMapping.systemVoiceMatches("en-us-x-male-local", VoiceGender.MALE))
        assertFalse(VoiceGenderMapping.systemVoiceMatches("en-us-x-iom-local", VoiceGender.MALE))
        assertTrue(VoiceGenderMapping.systemVoiceMatches("anything", VoiceGender.ANY))
    }

    @Test fun captionGenderMapsOntoTheSharedVoiceType() {
        assertEquals(VoiceGender.ANY, SpeakerGender.ANY.toVoiceGender())
        assertEquals(VoiceGender.FEMALE, SpeakerGender.FEMALE.toVoiceGender())
        assertEquals(VoiceGender.MALE, SpeakerGender.MALE.toVoiceGender())
    }
}

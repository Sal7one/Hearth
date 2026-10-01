package com.sal7one.transiber.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class OfflineVoicePolicyTest {
    @Test fun genderPreferenceOnlyConsidersTheRequestedLanguageAndFallsBackToSavedVoice() {
        val voices = listOf(voice("english-female", "en-US"), voice("arabic-local", "ar-SA"))
        assertEquals("arabic-local", OfflineVoicePolicy.selectForReadAloud(voices, Locale.forLanguageTag("ar"),
            "arabic-local", VoiceGender.FEMALE))
    }

    @Test fun explicitGenderCanSelectAnotherInstalledVoiceWithoutClaimingSavedVoiceIsMissing() {
        val voices = listOf(voice("english-male", "en-US"), voice("english-female", "en-US"))
        assertEquals("english-female", OfflineVoicePolicy.selectForReadAloud(voices, Locale.US,
            "english-male", VoiceGender.FEMALE))
        assertEquals("english-male", OfflineVoicePolicy.selectForReadAloud(voices, Locale.US,
            "english-male", VoiceGender.ANY))
    }

    @Test(expected = IllegalStateException::class) fun actuallyMissingSavedVoiceStillFails() {
        OfflineVoicePolicy.selectForReadAloud(listOf(voice("other", "en-US")), Locale.US, "missing", VoiceGender.ANY)
    }

    private fun voice(id: String, tag: String, network: Boolean = false, quality: Int = 100) =
        OfflineVoicePolicy.Candidate(id, Locale.forLanguageTag(tag), network, quality)
    @Test fun refusesNetworkAndWrongLanguageFallbacks() {
        val voices = listOf(voice("online", "ar-SA", true), voice("english", "en-US"))
        assertNull(OfflineVoicePolicy.select(voices, Locale.forLanguageTag("ar")))
        assertNull(OfflineVoicePolicy.select(voices, Locale.ROOT))
    }
    @Test fun honorsRegionThenQualityWithoutRequiringThatRegion() {
        val voices = listOf(voice("egypt", "ar-EG", quality = 500), voice("saudi", "ar-SA"))
        assertEquals("saudi", OfflineVoicePolicy.select(voices, Locale.forLanguageTag("ar")))
        assertEquals("egypt", OfflineVoicePolicy.select(voices, Locale.forLanguageTag("ar-EG")))
        assertEquals("egypt", OfflineVoicePolicy.select(voices.take(1), Locale.forLanguageTag("ar-SA")))
    }
    @Test fun highestQualityLocalVoiceWinsWhenRegionIsUnspecified() {
        assertEquals("best", OfflineVoicePolicy.select(listOf(voice("first", "en-US"), voice("best", "en-GB", quality=500)), Locale.ENGLISH))
    }
}

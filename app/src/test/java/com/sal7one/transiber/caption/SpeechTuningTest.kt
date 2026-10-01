package com.sal7one.transiber.caption

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.sal7one.common_jni.speech.SpeechProfile
import org.junit.Assert.*
import org.junit.Test

class SpeechTuningTest {
    private fun prefs(vararg pairs: Pair<String, Int>): MutablePreferences =
        mutablePreferencesOf(*pairs.map { androidx.datastore.preferences.core.intPreferencesKey(it.first) to it.second }.toTypedArray())

    @Test fun presetsMatchThemselvesAndStandardIsTheRuntimeDefault() {
        SpeechTuning.PRESETS.forEach { preset ->
            assertTrue(preset.matches(SpeechTuning(preset.maxUtteranceMs, preset.silenceMs)))
            assertEquals(preset, preset.preset)
        }
        assertTrue(SpeechTuning(4000, 600).isStandard)
        assertEquals(SpeechTuning.STANDARD, SpeechTuning(4000, 600).preset)
        assertNull(SpeechTuning(3000, 600).preset)
    }

    @Test fun boundsMirrorSpeechOptionsValidation() {
        // Presets must stay within the runtime's accepted SpeechOptions ranges.
        SpeechTuning.PRESETS.forEach { assertTrue(SpeechTuning.isValid(it.maxUtteranceMs, it.silenceMs)) }
        assertTrue(SpeechTuning.isValid(1000, 200))
        assertTrue(SpeechTuning.isValid(15000, 2000))
        assertFalse(SpeechTuning.isValid(999, 600))
        assertFalse(SpeechTuning.isValid(15020, 600))
        assertFalse(SpeechTuning.isValid(4000, 199))
        assertFalse(SpeechTuning.isValid(4000, 2020))
        assertFalse(SpeechTuning.isValid(4010, 600))
        assertFalse(SpeechTuning.isValid(4000, 610))
    }

    @Test fun preferencesRoundTripPerProfileAndStandardIsStoredAsAbsent() {
        val written = prefs()
        SpeechTuning.writeInto(written, "phonon-2", SpeechTuning.RESPONSIVE)
        assertEquals(SpeechTuning.RESPONSIVE, SpeechTuning.fromPreferences(written, "phonon-2"))
        assertNull(SpeechTuning.fromPreferences(written, "moonshine-tiny-en-v2"))

        SpeechTuning.writeInto(written, "moonshine-tiny-en-v2", SpeechTuning.STANDARD)
        assertNull("standard is the default, stored as absent", SpeechTuning.fromPreferences(written, "moonshine-tiny-en-v2"))
        assertEquals(SpeechTuning.RESPONSIVE, SpeechTuning.fromPreferences(written, "phonon-2"))

        SpeechTuning.writeInto(written, "phonon-2", null)
        assertNull(SpeechTuning.fromPreferences(written, "phonon-2"))
    }

    @Test fun corruptOrOutOfRangeStoredValuesFallBackToDefault() {
        val corrupt = prefs("phonon-2.window" to 4005, "phonon-2.silence" to 600)
        assertNull(SpeechTuning.fromPreferences(corrupt, "phonon-2"))
        val half = prefs("phonon-2.silence" to 400)
        assertNull(SpeechTuning.fromPreferences(half, "phonon-2"))
    }
}

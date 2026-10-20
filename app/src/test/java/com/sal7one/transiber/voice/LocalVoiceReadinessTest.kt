package com.sal7one.transiber.voice

import com.sal7one.hearth.tts.magpie.MagpieCatalog
import org.junit.Assert.*
import org.junit.Test

class LocalVoiceReadinessTest {
    @Test fun magpieNeedsItsOwnCompletePackageAndAvailableRuntime() {
        assertTrue(ready("magpie", "John"))
        assertFalse(ready("magpie", "John", runtime = false))
        assertFalse(ready("magpie", "John", packageReady = false))
        assertFalse(ready("magpie", "John", allowed = false))
        // Installed Supertonic files cannot make Magpie ready or change its selected voice.
        assertFalse(ready("magpie", "F1"))
        MagpieCatalog.speakers.forEach { assertTrue(ready("magpie", it)) }
    }

    @Test fun magpieRejectsGenderFiltersWithoutStartingOrCheckingAnEngine() {
        listOf(VoiceGender.FEMALE, VoiceGender.MALE).forEach { gender ->
            assertFalse(LocalVoiceReadiness.ready("magpie", "John", gender, true,
                supertonicReady = { error("Unrelated Supertonic stage") },
                magpieAvailable = { error("Gender must be rejected before runtime checks") },
                magpieReady = { error("Gender must be rejected before package checks") }))
        }
    }

    @Test fun supertonicStillUsesItsGenderMappedReadinessWithoutMagpie() {
        var mappedVoice: String? = null
        assertTrue(LocalVoiceReadiness.ready("supertonic", "F3", VoiceGender.MALE, true,
            supertonicReady = { mappedVoice = VoiceGenderMapping.supertonicVoice("F3", VoiceGender.MALE); true },
            magpieAvailable = { error("Unrelated Magpie runtime") },
            magpieReady = { error("Unrelated Magpie package") }))
        assertEquals("M3", mappedVoice)
        assertFalse(ready("supertonic", "F1", packageReady = false))
    }

    @Test fun policyAndUnknownFamilyCannotBeBypassedByExistingFiles() {
        fun unexpected(): Boolean = error("Readiness checks must be short-circuited")
        assertFalse(LocalVoiceReadiness.ready("magpie", "John", VoiceGender.ANY, false,
            ::unexpected, ::unexpected, ::unexpected))
        assertFalse(LocalVoiceReadiness.ready("future", "John", VoiceGender.ANY, true,
            ::unexpected, ::unexpected, ::unexpected))
    }

    private fun ready(family: String, voice: String, runtime: Boolean = true,
                      packageReady: Boolean = true, allowed: Boolean = true): Boolean =
        LocalVoiceReadiness.ready(family, voice, VoiceGender.ANY, allowed,
            supertonicReady = { packageReady }, magpieAvailable = { runtime }, magpieReady = { packageReady })
}

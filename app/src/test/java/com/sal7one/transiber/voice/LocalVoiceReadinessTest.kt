package com.sal7one.transiber.voice

import org.junit.Assert.*
import org.junit.Test

class LocalVoiceReadinessTest {
    @Test fun providerNeedsItsOwnCompletePackageAndAvailableRuntime() {
        assertTrue(ready())
        assertFalse(ready(runtime = false))
        assertFalse(ready(packageReady = false))
        assertFalse(ready(allowed = false))
        assertFalse(ready(voice = "F1"))
    }
    @Test fun unverifiedGenderRejectsFiltersBeforeAnyRuntimeOrFilesCheck() {
        listOf(VoiceGender.FEMALE, VoiceGender.MALE).forEach { gender ->
            assertFalse(LocalVoiceReadiness.ready("John", gender, true, listOf("John"), false,
                runtimeAvailable = { error("Gender must be rejected before runtime") },
                packageReady = { error("Gender must be rejected before package checks") }))
        }
    }
    @Test fun verifiedSupertonicStillUsesTheRequestedMappedGender() {
        var mappedVoice: String? = null
        assertTrue(LocalVoiceReadiness.ready("F3", VoiceGender.MALE, true, listOf("F3", "M3"), true,
            runtimeAvailable = { true },
            packageReady = { mappedVoice = VoiceGenderMapping.supertonicVoice("F3", VoiceGender.MALE); true }))
        assertEquals("M3", mappedVoice)
    }
    @Test fun policyAndUnknownVoiceCannotBeBypassedByExistingFiles() {
        fun unexpected(): Boolean = error("Checks must be short-circuited")
        assertFalse(LocalVoiceReadiness.ready("John",VoiceGender.ANY,false,listOf("John"),false,::unexpected,::unexpected))
        assertFalse(LocalVoiceReadiness.ready("unknown",VoiceGender.ANY,true,listOf("John"),false,::unexpected,::unexpected))
    }
    @Test fun designAttributesAreNotVerifiedGenderAndCannotUseAnotherFamiliesSpeakers() {
        val designs=listOf("auto","design-female","design-male")
        fun ready(voice: String, gender: VoiceGender = VoiceGender.ANY) =
            LocalVoiceReadiness.ready(voice,gender,true,designs,false,{ true },{ true })
        assertTrue(ready("auto"));assertTrue(ready("design-female"))
        assertFalse(ready("John"));assertFalse(ready("design-female",VoiceGender.FEMALE))
        assertFalse(ready("design-male",VoiceGender.MALE))
    }
    private fun ready(voice: String = "John", runtime: Boolean = true,
                      packageReady: Boolean = true, allowed: Boolean = true) =
        LocalVoiceReadiness.ready(voice,VoiceGender.ANY,allowed,listOf("John"),false,{ runtime },{ packageReady })
}

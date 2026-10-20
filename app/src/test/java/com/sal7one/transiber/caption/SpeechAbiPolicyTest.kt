package com.sal7one.transiber.caption

import com.sal7one.common_jni.speech.SpeechProfile
import org.junit.Assert.*
import org.junit.Test

class SpeechAbiPolicyTest {
    private val expansion = setOf(SpeechProfile.COHERE_ARABIC_07_2026, SpeechProfile.FUNASR_MLT_NANO_2512,
        SpeechProfile.VOXTRAL_MINI_REALTIME_2602, SpeechProfile.AUDAR_ASR_V1_FLASH, SpeechProfile.OWSM_V4_BASE_102M)
    @Test fun newPortsRequireBothArm64PrimaryAbiAnd64BitProcess() {
        expansion.forEach { profile ->
            assertTrue(SpeechAbiPolicy.supports(profile, "arm64-v8a", true))
            assertFalse(SpeechAbiPolicy.supports(profile, "arm64-v8a", false))
            assertFalse(SpeechAbiPolicy.supports(profile, "x86_64", true))
            assertFalse(SpeechAbiPolicy.supports(profile, "armeabi-v7a", false))
            assertFalse(SpeechAbiPolicy.supports(profile, null, true))
        }
    }
    @Test fun existingFourAbiSpeechPortsAreUnaffected() {
        SpeechProfile.entries.filterNot { it in expansion }.forEach { profile ->
            listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
                assertTrue(SpeechAbiPolicy.supports(profile, abi, false))
                assertTrue(SpeechAbiPolicy.supports(profile, abi, true))
            }
        }
    }
}

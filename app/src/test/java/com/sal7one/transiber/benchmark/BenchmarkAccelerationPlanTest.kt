package com.sal7one.transiber.benchmark

import com.sal7one.common_jni.speech.SpeechProfile
import org.junit.Assert.*
import org.junit.Test

class BenchmarkAccelerationPlanTest {
    @Test fun comparisonsRetainArtifactAndDistinctHistoryIdentities() {
        val qvac = BenchmarkCandidate("installed-package", "QVAC", "Speech / utterance", setOf("en"),
            profile = SpeechProfile.TRANSCRIBEPSY_250M_EU_SUB)
        val planned = BenchmarkAccelerationPlan.expand(listOf(qvac), true)
        assertEquals(listOf("cpu", "vulkan", "opencl"), planned.map { it.acceleration })
        assertTrue(planned.all { it.id == qvac.id && it.profile == qvac.profile })
        assertEquals(3, planned.map(BenchmarkAccelerationPlan::identity).toSet().size)
        assertNull(qvac.acceleration)
    }

    @Test fun newNativeMultilingualEnginesUseOnlyTheirPackagedComputeRoutes() {
        listOf(SpeechProfile.COHERE_ARABIC_07_2026, SpeechProfile.FUNASR_MLT_NANO_2512, SpeechProfile.VOXTRAL_MINI_REALTIME_2602).forEach { profile ->
            val candidate = BenchmarkCandidate(profile.id, profile.id, "Speech / utterance", setOf("ar", "en"), profile = profile)
            assertEquals(listOf("cpu", "vulkan"), BenchmarkAccelerationPlan.expand(listOf(candidate), true).map { it.acceleration })
        }
    }
    @Test fun cpuOnlyEnginesDoNotReceiveInventedGpuRoutes() {
        val vosk = BenchmarkCandidate("vosk-model", "Vosk", "Vosk / batch", setOf("model"))
        assertEquals(listOf("cpu"), BenchmarkAccelerationPlan.expand(listOf(vosk), true).map { it.acceleration })
        assertEquals(listOf(vosk), BenchmarkAccelerationPlan.expand(listOf(vosk), false))
    }

    @Test fun whisperHasNoOpenClAndHundredsOfSelectedModelsRemainAvailable() {
        val whisper = BenchmarkCandidate("w", "Whisper", "Whisper / batch", setOf("en"))
        assertEquals(listOf("cpu", "vulkan"), BenchmarkAccelerationPlan.expand(listOf(whisper), true).map { it.acceleration })
        val hundreds = List(500) { whisper.copy(id = "model-$it") }
        assertEquals(1000, BenchmarkAccelerationPlan.expand(hundreds, true).size)
        assertEquals(500, BenchmarkAccelerationPlan.expand(hundreds, false).size)
        assertEquals(2, BenchmarkAccelerationPlan.expand(listOf(whisper, whisper), true).size)
        assertEquals(2, BenchmarkAccelerationPlan.expand(listOf(whisper.copy(acceleration = "cpu"), whisper.copy(acceleration = "vulkan")), true).size)
    }
}

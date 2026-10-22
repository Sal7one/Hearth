package com.sal7one.transiber.benchmark

import org.junit.Assert.*
import org.junit.Test

class BenchmarkExecutionMetadataTest {
    @Test fun actualBackendComesFromSessionEvidenceAndMixedGpuCpuDoesNotBecomeCpu() {
        assertEquals("vulkan", BenchmarkExecutionMetadata.actualAcceleration("Vulkan · Adreno 830 · loader assigned 25/25 layers; GPU model buffers 257.75 MiB. Unsupported operations may use CPU."))
        assertEquals("opencl", BenchmarkExecutionMetadata.actualAcceleration("TranscribePsy · mel: CPU · encoder: OpenCL0 · decoder: OpenCL0"))
        assertEquals("cpu", BenchmarkExecutionMetadata.actualAcceleration("CPU · CTranslate2"))
        assertNull(BenchmarkExecutionMetadata.actualAcceleration(null))
        assertNull(BenchmarkExecutionMetadata.actualAcceleration("ML Kit managed execution"))
        assertEquals("vulkan", BenchmarkExecutionMetadata.actualAcceleration("Cohere Transcribe · frontend: CPU · primary compute: vulkan · utterance finals"))
        assertEquals("cpu", BenchmarkExecutionMetadata.actualAcceleration("Audar Flash · BF16 encoder: CPU · Qwen3-ASR decoder: CPU · greedy · threads: 4"))
        assertEquals("opencl", BenchmarkExecutionMetadata.actualAcceleration("OpenCL · Adreno 830 · loader assigned 12/25 layers; GPU model buffers 100.50 MiB. Adreno kernels enabled; unsupported operations may use CPU."))
    }


    @Test fun buildFlagsRequestsAndDeviceProbesNeverCountAsActualGpuPlacement() {
        listOf("Requested vulkan; initialization did not complete", "Compiled CPU=1 VULKAN=1 OPENCL=1",
            "Vulkan GPU: Adreno 830 + CPU", "CPU runtime, Vulkan build option available",
            "Vulkan · Adreno 830 · loader assigned 0/25 layers; GPU model buffers 100.00 MiB.",
            "Vulkan · Adreno 830 · loader assigned 26/25 layers; GPU model buffers 100.00 MiB.",
            "Vulkan · Adreno 830 · loader assigned 25/25 layers; GPU model buffers 0.00 MiB.",
            "OpenCL · unknown device · loader assigned 25/25 layers; GPU model buffers 100.00 MiB.",
            "TranscribePsy · mel: CPU · encoder: Vulkan0 · decoder: CPU").forEach { evidence ->
            assertNull(evidence, BenchmarkExecutionMetadata.actualAcceleration(evidence))
        }
    }

    @Test fun resumeRequiresExactArtifactSettingsInputDeviceStageBackendAndCompletedPasses() {
        val model = BenchmarkCandidate("model", "Model", "Whisper / batch", setOf("en"),
            acceleration = "cpu", artifactFingerprint = "pinned-sha256")
        val configuration = BenchmarkExecutionMetadata.configurationHash(model, 3, "settings")!!
        val result = BenchmarkResult("batch", 1, "Model", "model@cpu", "Whisper / batch", "input", "en", "", 1000,
            10.0, listOf(30.0, 20.0, 20.0), listOf("hello"), "CPU", listOf(10.0, 9.0, 9.0), "phone",
            protocolVersion = 2, configurationHash = configuration, actualAcceleration = "cpu")
        fun resume(row: BenchmarkResult = result, candidate: BenchmarkCandidate = model, settings: String? = configuration) =
            BenchmarkExecutionMetadata.canResume(row, candidate, "input", settings, "en", "", "phone", 3)
        assertTrue(resume())
        assertFalse(resume(result.copy(error = "failed")))
        assertFalse(resume(result.copy(texts = emptyList())))
        assertFalse(resume(result.copy(texts = listOf("", "", "  "))))
        assertFalse(resume(result.copy(actualAcceleration = null)))
        assertFalse(resume(result.copy(actualAcceleration = "vulkan")))
        assertFalse(resume(result.copy(computeMs = listOf(30.0))))
        assertFalse(resume(result.copy(inputHash = "different")))
        assertFalse(resume(result.copy(device = "other phone")))
        assertFalse(resume(result.copy(task = "voice")))
        assertFalse(resume(candidate = model.copy(acceleration = "vulkan")))
        assertFalse(resume(settings = BenchmarkExecutionMetadata.configurationHash(model.copy(artifactFingerprint = "new-artifact"), 3, "settings")))
        assertFalse(resume(settings = BenchmarkExecutionMetadata.configurationHash(model, 3, "new settings")))
        assertFalse(resume(settings = null))
    }
}

package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopGpuSettingsTest {
    @Test fun independentComputeAndDeviceChoicesSurviveSettingsAndPortableSetup() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("hearth-compute-settings-")
        val config = PipelineConfig(
            speech = EngineSelection("whisper-local", options = mapOf("acceleration" to "cuda", "gpuDevice" to "1", "threads" to "3")),
            translation = EngineSelection("llama-translation-local", options = mapOf("acceleration" to "cpu", "gpuDevice" to "0", "threads" to "5")),
        )
        val store = DesktopSettingsStore(directory)
        try {
            store.save(config)
            assertEquals(config, store.load())
            assertEquals(config, SetupCodec.decode(SetupCodec.encode(config), portable = true))
            for (acceleration in listOf("cpu", "metal", "cuda")) {
                val changed = config.copy(speech = config.speech!!.copy(options = mapOf("acceleration" to acceleration, "gpuDevice" to "0")))
                assertEquals(changed, SetupCodec.decode(SetupCodec.encode(changed)))
            }
        } finally { store.closeAndJoin(); directory.toFile().deleteRecursively() }
    }

    @Test fun invalidComputeAndDeviceValuesFailBeforePersistenceOrImport() {
        for (device in listOf("-1", "1.0", "", "gpu0", "2147483648")) {
            assertFailsWith<IllegalArgumentException> {
                SetupCodec.encode(PipelineConfig(speech = EngineSelection("whisper-local", options = mapOf("gpuDevice" to device))))
            }
        }
        for (backend in listOf("", "auto", "vulkan", "CUDA")) {
            assertFailsWith<IllegalArgumentException> {
                SetupCodec.encode(PipelineConfig(translation = EngineSelection("llama-translation-local", options = mapOf("acceleration" to backend))))
            }
        }
    }
}

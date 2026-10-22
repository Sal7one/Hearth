package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class DesktopEngineSetupsTest {
    @Test fun switchingAndDisablingEnginesPreservesTheirSetupsAcrossRestartAndExcludesHistoryFromExports() = runBlocking {
        val directory = Files.createTempDirectory("hearth-engine-setups-")
        try {
            val qwen = EngineSelection("qwen3-asr-local", "qwen", "/private/qwen", options = mapOf("threads" to "6"))
            val remote = EngineSelection("openai-translation", "custom-model", endpoint = "https://example.invalid/v1", credentialId = "saved-reference")
            val voice = EngineSelection("supertonic-local", "supertonic", "/private/voice")
            val first = DesktopSettingsStore(directory)
            try {
                first.load()
                first.save(PipelineConfig(speech = qwen, translation = remote, voice = voice, voiceId = "M1"))
                first.save(PipelineConfig(speech = EngineSelection("whisper-local")))
            } finally { first.closeAndJoin() }
            val reopened = DesktopSettingsStore(directory)
            try {
                reopened.load()
                assertEquals(qwen, reopened.engineSetups.value.getValue(qwen.engineId).speech)
                assertEquals(remote, reopened.engineSetups.value.getValue(remote.engineId).translation)
                assertEquals("M1", reopened.engineSetups.value.getValue(voice.engineId).voiceId)
                val exported = directory.resolve("portable.json")
                reopened.exportSetup(exported)
                val text = Files.readString(exported)
                assertFalse(text.contains("/private/"))
                assertFalse(text.contains("saved-reference"))
                assertFalse(text.contains("engine_setups"))
            } finally { reopened.closeAndJoin() }
        } finally { directory.toFile().deleteRecursively() }
    }
}

package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.ui.screens.voice.voicesState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DesktopStageSelectionTest {
    @Test fun choosingAnEngineRestoresItsRememberedRouteWithoutChangingAnotherStageOrLanguages() {
        val rememberedVoice = EngineSelection("supertonic-local", "voice", "/verified/voice", options = mapOf("speed" to "1.2"))
        val original = PipelineConfig(speech = EngineSelection("qwen3-asr-local", "qwen", "/verified/qwen"),
            translation = EngineSelection("marian-local", "quickmt", "/verified/quickmt"), sourceLanguage = "en", targetLanguage = "ar")
        val descriptor = EngineDescriptor("supertonic-local", "Supertonic", PipelineStage.TTS, EngineKind.LOCAL, requiresModel = true)
        val next = original.chooseEngine(descriptor, PipelineConfig(voice = rememberedVoice, voiceId = "M1", speakTranslatedText = false))
        assertEquals(rememberedVoice, next.voice)
        assertEquals("M1", next.voiceId)
        assertFalse(next.speakTranslatedText)
        assertEquals(original.speech, next.speech)
        assertEquals(original.translation, next.translation)
        assertEquals("en", next.sourceLanguage)
        assertEquals("ar", next.targetLanguage)
        assertEquals(rememberedVoice, original.chooseEngine(descriptor, installed = rememberedVoice).voice)
    }

    @Test fun enablingReadAloudPrefersAvailableSupertonicAndNeverResetsAnExistingVoice() {
        val moss = EngineDescriptor("moss-tts-local", "MOSS", PipelineStage.TTS, EngineKind.LOCAL)
        val supertonic = EngineDescriptor("supertonic-local", "Supertonic", PipelineStage.TTS, EngineKind.LOCAL)
        val system = EngineDescriptor("system-tts", "System", PipelineStage.TTS, EngineKind.SYSTEM)
        val original = PipelineConfig(speech = EngineSelection("whisper-local"), translation = EngineSelection("marian-local"))
        val selected = original.enableReadAloud(listOf(moss, supertonic, system))
        assertEquals("supertonic-local", selected.voice?.engineId)
        assertEquals(original.speech, selected.speech)
        assertEquals(original.translation, selected.translation)
        assertEquals("system-tts", original.enableReadAloud(listOf(moss.copy(available = false),
            supertonic.copy(available = false), system)).voice?.engineId)
        assertFailsWith<IllegalStateException> { original.enableReadAloud(listOf(supertonic.copy(available = false))) }
        val customized = original.copy(voice = EngineSelection("magpie-tts-local", "magpie", "/verified/magpie",
            credentialId = "kept", options = mapOf("threads" to "3")), voiceId = "Aria")
        assertSame(customized, customized.enableReadAloud(listOf(moss, supertonic)))
    }

    @Test fun selectingTheCurrentEnginePreservesEveryIndependentStageSetting() {
        fun route(id: String) = EngineSelection(id, "model-$id", "/verified/$id", "https://example.com/v1", "credential-$id",
            mapOf("threads" to "3", "sha256" to "a".repeat(64)))
        val config = PipelineConfig(route("speech"), route("translation"), route("voice"),
            sourceLanguage = "ar", targetLanguage = "en", voiceId = "chosen-voice", speakTranslatedText = false)
        for (stage in PipelineStage.entries) {
            val original = requireNotNull(config.selection(stage))
            val engine = EngineDescriptor(original.engineId, "Selected engine", stage, EngineKind.LOCAL)
            assertSame(config, config.chooseEngine(engine))
            val changed = config.chooseEngine(engine.copy(id = "replacement", requiresCredential = true))
            for (other in PipelineStage.entries.filter { it != stage }) {
                assertEquals(config.selection(other), changed.selection(other))
            }
            assertEquals("replacement", changed.selection(stage)?.engineId)
            assertEquals("", changed.selection(stage)?.modelPath)
            assertEquals("ar", changed.sourceLanguage)
            assertEquals("en", changed.targetLanguage)
            assertFalse(changed.speakTranslatedText)
            assertEquals(if (stage == PipelineStage.TTS) "" else "chosen-voice", changed.voiceId)
        }
    }

    @Test fun voiceFamiliesKeepTheirActualNamesAndSupertonicStylesStayExclusive() {
        val names = mapOf("supertonic-local" to "Supertonic 3", "moss-tts-local" to "MOSS-TTS Nano",
            "magpie-tts-local" to "Magpie", "omnivoice-tts-local" to "OmniVoice",
            "chatterbox-tts-local" to "Chatterbox V3", "ming-local" to "Ming-3.0 local")
        val engines = names.map { (id, name) -> EngineDescriptor(id, name, PipelineStage.TTS, EngineKind.LOCAL, requiresModel = true) }
        for ((id, name) in names) {
            val config = PipelineConfig(voice = EngineSelection(id, modelPath = "/verified/$id"))
            val state = voicesState(engines, config)
            assertEquals(names.values.toList(), state.engines.map { it.name })
            assertEquals(name, state.engineDetail)
            assertTrue(state.ready)
            if (id != "supertonic-local") {
                assertTrue(state.styles.isEmpty())
                assertEquals("Engine default", state.selectedStyle)
                assertFalse(state.engines.single { it.id == id }.note.contains("Ten styles"))
            }
            assertFalse(voicesState(engines, PipelineConfig(voice = EngineSelection(id))).ready)
        }
    }
}

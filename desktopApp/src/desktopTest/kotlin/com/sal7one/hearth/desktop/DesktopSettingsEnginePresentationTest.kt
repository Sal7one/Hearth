package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import com.sal7one.hearth.desktop.data.DesktopModelArtifact
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.ui.screens.speech.speechState
import com.sal7one.hearth.desktop.ui.screens.translation.translationState
import com.sal7one.hearth.desktop.ui.screens.voice.voicesState
import org.junit.Test
import java.nio.file.Path
import kotlin.test.*

class DesktopSettingsEnginePresentationTest {
    private fun card(id: String, engine: String, stage: PipelineStage) = DesktopModelCard(id, "Model $id", "family", stage,
        engine, setOf("en", "ar"), "https://example.com/model", "MIT", "", emptyMap(),
        listOf(DesktopModelArtifact("model.bin", "https://example.com/model.bin", 3, "a".repeat(64))))

    @Test fun speechModelsAndReadinessBelongToTheSelectedEngineAndVerifiedPath() {
        val engines = listOf(EngineDescriptor("whisper-local", "Whisper", PipelineStage.STT, EngineKind.LOCAL, requiresModel = true),
            EngineDescriptor("vosk-local", "Vosk (Kaldi)", PipelineStage.STT, EngineKind.LOCAL, requiresModel = true))
        val whisper = card("whisper", engines[0].id, PipelineStage.STT)
        val vosk = card("vosk", engines[1].id, PipelineStage.STT)
        val route = EngineSelection(engines[1].id, vosk.id, "/verified/vosk")
        val installed = mapOf(vosk.id to DesktopModelStatus(true, Path.of(route.modelPath)))
        fun state(selection: EngineSelection) = speechState(engines, PipelineConfig(speech = selection, sourceLanguage = "en"), listOf(whisper, vosk), installed, emptyList(), null)
        val valid = state(route)
        assertEquals(listOf("Whisper", "Vosk (Kaldi)"), valid.engines.map { it.name })
        assertTrue(valid.engines[1].detail.contains("Vosk / Kaldi"))
        assertFalse(valid.engines[1].detail.contains("whisper.cpp"))
        assertEquals(listOf(vosk.id), valid.models.map { it.id })
        assertTrue(valid.ready)
        assertFalse(state(route.copy(modelPath = "/changed/path")).ready)
        assertFalse(state(route.copy(engineId = engines[0].id)).ready)
    }

    @Test fun ctranslate2AndGgufHaveDistinctIdentitiesCompatibleModelsAndControls() {
        val engines = listOf(EngineDescriptor("llama-translation-local", "Local GGUF translation", PipelineStage.TRANSLATION, EngineKind.LOCAL, requiresModel = true),
            EngineDescriptor("marian-local", "CTranslate2 · Marian/OPUS", PipelineStage.TRANSLATION, EngineKind.LOCAL, requiresModel = true))
        val gguf = card("gguf", engines[0].id, PipelineStage.TRANSLATION)
        val ct2 = card("quickmt", engines[1].id, PipelineStage.TRANSLATION).copy(options = mapOf("sources" to "en", "targets" to "ar"))
        val route = EngineSelection(engines[1].id, ct2.id, "/verified/ct2")
        val state = translationState(engines, PipelineConfig(translation = route, sourceLanguage = "en", targetLanguage = "ar"),
            listOf(gguf, ct2), mapOf(ct2.id to DesktopModelStatus(true, Path.of(route.modelPath))))
        assertEquals(engines.map { it.name }, state.engines.map { it.name })
        assertTrue(state.engines[0].detail.contains("llama.cpp"))
        assertTrue(state.engines[1].detail.contains("CTranslate2"))
        assertFalse(state.engines[1].detail.contains("llama.cpp"))
        assertEquals(listOf(ct2.id), state.models.map { it.id })
        assertFalse(state.supportsPromptMode)
        assertTrue(state.ready)
        assertEquals(listOf("en"), state.sourceOptions.map { it.id })
        assertEquals(listOf("ar"), state.targetOptions.map { it.id })
    }

    @Test fun providerConnectionsDoNotRequireALocalModelAndTargetsNeverOfferDetection() {
        val engine = EngineDescriptor("openai-translation", "Provider translation", PipelineStage.TRANSLATION, EngineKind.CLOUD,
            requiresCredential = true, defaultEndpoint = "https://example.com/v1", defaultModel = "translator")
        val route = EngineSelection(engine.id, "translator", endpoint = engine.defaultEndpoint, credentialId = "saved-reference")
        val config = PipelineConfig(translation = route, sourceLanguage = "en", targetLanguage = "ar")
        val state = translationState(listOf(engine), config, emptyList(), emptyMap())
        assertTrue(state.ready)
        assertTrue(state.cloudSelected)
        assertEquals("Provider", state.placementLabel)
        assertTrue(state.models.isEmpty())
        assertTrue(state.targetOptions.none { it.id == "auto" })
        assertFalse(translationState(listOf(engine), config.copy(translation = route.copy(credentialId = "")), emptyList(), emptyMap()).ready)
    }

    @Test fun localVoiceReadinessRequiresTheMatchingVerifiedFamilyAndPath() {
        val engine = EngineDescriptor("moss-tts-local", "MOSS-TTS Nano 100M", PipelineStage.TTS, EngineKind.LOCAL, requiresModel = true)
        val model = card("moss", engine.id, PipelineStage.TTS)
        val route = EngineSelection(engine.id, model.id, "/verified/moss")
        val installed = mapOf(model.id to DesktopModelStatus(true, Path.of(route.modelPath)))
        val state = voicesState(listOf(engine), PipelineConfig(voice = route), listOf(model), installed)
        assertEquals(engine.name, state.engines.single().name)
        assertTrue(state.engines.single().detail.contains("ONNX Runtime"))
        assertTrue(state.ready)
        assertTrue(state.styles.isEmpty())
        assertFalse(voicesState(listOf(engine), PipelineConfig(voice = route.copy(modelPath = "/not/verified")), listOf(model), installed).ready)
    }
}

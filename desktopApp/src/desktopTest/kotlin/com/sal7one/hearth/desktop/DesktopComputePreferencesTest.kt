package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import com.sal7one.hearth.desktop.data.*
import com.sal7one.hearth.desktop.ui.language.stageSetupIssue
import com.sal7one.hearth.desktop.ui.screens.settings.*
import com.sal7one.hearth.desktop.ui.screens.speech.*
import com.sal7one.hearth.desktop.ui.screens.translation.translationState
import java.nio.file.Path
import kotlin.test.*

class DesktopComputePreferencesTest {
    private fun engine(stage: PipelineStage = PipelineStage.STT, acceleration: Set<String> = setOf("cpu")) =
        EngineDescriptor(if (stage == PipelineStage.STT) "whisper-local" else "llama-translation-local", "Local engine", stage,
            EngineKind.LOCAL, acceleration = acceleration, requiresModel = true)
    private fun card(engine: EngineDescriptor) = DesktopModelCard("model-${engine.stage}", "Pinned model", "test", engine.stage,
        engine.id, setOf("en", "ar"), "https://example.test/model", "test", "", emptyMap(), emptyList())

    @Test fun onlyActualLocalDescriptorCapabilitiesBecomeComputeChoices() {
        val cpu = engine()
        val cuda = engine(acceleration = setOf("cpu", "cuda"))
        val metal = engine(acceleration = setOf("cpu", "metal"))
        assertEquals(listOf(Compute.CPU), computeSettings(cpu, EngineSelection(cpu.id)).options)
        assertEquals(listOf(Compute.CUDA, Compute.CPU), computeSettings(cuda, EngineSelection(cuda.id)).options)
        assertEquals(listOf(Compute.METAL, Compute.CPU), computeSettings(metal, EngineSelection(metal.id)).options)
        assertTrue(computeSettings(cuda.copy(available = false), EngineSelection(cuda.id)).options.isEmpty())
        assertTrue(computeSettings(cuda.copy(kind = EngineKind.CLOUD), EngineSelection(cuda.id)).options.isEmpty())
    }

    @Test fun freshRoutesPreferDetectedGpuWhileExplicitCpuAndIndependentTuningRemainIntact() {
        val engine = engine(acceleration = setOf("cpu", "cuda"))
        val fresh = EngineSelection(engine.id, modelId = "pinned", options = mapOf("threads" to "3"))
        assertEquals(mapOf("threads" to "3", "acceleration" to "cuda"), preferDetectedCompute(engine, fresh).options)
        val explicit = fresh.copy(options = fresh.options + ("acceleration" to "cpu") + ("gpuDevice" to "1"))
        assertEquals(explicit, preferDetectedCompute(engine, explicit))
        assertEquals(fresh, preferDetectedCompute(engine.copy(available = false), fresh))
        assertEquals(fresh, preferDetectedCompute(engine.copy(acceleration = setOf("cpu")), fresh))
        assertEquals("metal", preferDetectedCompute(engine.copy(acceleration = setOf("cpu", "metal")), fresh).options["acceleration"])
    }

    @Test fun unavailableSavedGpuIsRetainedAndNeverDisplayedAsCpuReadiness() {
        val engine = engine()
        val route = EngineSelection(engine.id, options = mapOf("acceleration" to "cuda"))
        val settings = computeSettings(engine, route)
        assertEquals(Compute.CUDA, settings.selected)
        assertEquals(listOf(Compute.CPU), settings.options)
        assertNotNull(settings.issue)
        assertNotNull(stageSetupIssue(PipelineConfig(speech = route), PipelineStage.STT, listOf(engine), emptyMap()))
    }

    @Test fun speechAndTranslationMappersKeepTheirOwnComputeSelectionsAndReadiness() {
        val speech = engine(acceleration = setOf("cpu", "cuda"))
        val translation = engine(PipelineStage.TRANSLATION, setOf("cpu", "metal"))
        val speechCard = card(speech)
        val translationCard = card(translation)
        val config = PipelineConfig(sourceLanguage = "en", targetLanguage = "ar",
            speech = EngineSelection(speech.id, speechCard.id, "/verified/speech", options = mapOf("acceleration" to "cuda")),
            translation = EngineSelection(translation.id, translationCard.id, "/verified/translation", options = mapOf("acceleration" to "cpu")))
        val catalog = listOf(speechCard, translationCard)
        val installed = mapOf(speechCard.id to DesktopModelStatus(true, Path.of("/verified/speech")),
            translationCard.id to DesktopModelStatus(true, Path.of("/verified/translation")))
        val capabilities = listOf(speech, translation)
        val stt = speechState(capabilities, config, catalog, installed, emptyList(), null)
        val mt = translationState(capabilities, config, catalog, installed)
        assertEquals(Compute.CUDA, stt.compute)
        assertTrue(stt.ready)
        assertEquals(Compute.CPU, mt.compute)
        assertTrue(mt.ready)
        val withoutCuda = speechState(listOf(speech.copy(acceleration = setOf("cpu")), translation), config, catalog, installed, emptyList(), null)
        assertEquals(Compute.CUDA, withoutCuda.compute)
        assertFalse(withoutCuda.ready)
        assertNotNull(withoutCuda.computeIssue)
    }
}

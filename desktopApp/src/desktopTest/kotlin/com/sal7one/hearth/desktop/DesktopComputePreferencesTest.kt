package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import com.sal7one.hearth.desktop.data.*
import com.sal7one.hearth.desktop.ui.language.stageSetupIssue
import com.sal7one.hearth.desktop.ui.screens.settings.*
import com.sal7one.hearth.desktop.ui.screens.speech.*
import com.sal7one.hearth.desktop.ui.screens.translation.translationState
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
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

    @Test fun legacyActiveAndRememberedRoutesResolveOnlyMissingComputeChoices() {
        val speech = engine(acceleration = setOf("cpu", "metal"))
        val translation = engine(PipelineStage.TRANSLATION, setOf("cpu", "metal"))
        val legacy = EngineSelection(speech.id, "large-turbo", "/verified/speech",
            options = mapOf("sha256" to "pinned", "threads" to "6", "windowMs" to "2000"))
        val explicitCpu = EngineSelection(translation.id, "translator", "/verified/translation",
            options = mapOf("acceleration" to "cpu"))
        val config = PipelineConfig(sourceLanguage = "en", targetLanguage = "ar", speech = legacy, translation = explicitCpu)
        val resolved = config.withDetectedCompute(listOf(speech, translation))
        assertEquals(legacy.copy(options = legacy.options + ("acceleration" to "metal")), resolved.speech)
        assertEquals(explicitCpu, resolved.translation)
        assertEquals("en", resolved.sourceLanguage)
        assertEquals(resolved, resolved.withDetectedCompute(listOf(speech, translation)))
        assertEquals(resolved, config.chooseEngine(speech))
        assertEquals(resolved.speech, PipelineConfig().chooseEngine(speech, config).speech)
        assertSame(config, config.withDetectedCompute(listOf(speech.copy(available = false), translation)))
        assertSame(config, config.withDetectedCompute(listOf(speech.copy(acceleration = setOf("cpu")), translation)))
    }

    @Test fun resolvedLegacyComputeSurvivesRestartAndRemainsInRememberedRoute() = runBlocking {
        val directory = Files.createTempDirectory("hearth-compute-migration-")
        val descriptor = engine(acceleration = setOf("cpu", "metal"))
        val legacy = PipelineConfig(speech = EngineSelection(descriptor.id, "pinned", "/verified/model",
            options = mapOf("sha256" to "integrity-pin", "threads" to "6")))
        try {
            val initial = DesktopSettingsStore(directory)
            try {
                initial.save(legacy)
                initial.save(initial.load().withDetectedCompute(listOf(descriptor)))
            } finally { initial.closeAndJoin() }
            val reopened = DesktopSettingsStore(directory)
            try {
                val saved = reopened.load()
                assertEquals("metal", saved.speech?.options?.get("acceleration"))
                assertEquals("integrity-pin", saved.speech?.options?.get("sha256"))
                assertEquals("6", saved.speech?.options?.get("threads"))
                assertEquals(saved.speech, reopened.engineSetups.value[descriptor.id]?.speech)
            } finally { reopened.closeAndJoin() }
        } finally { directory.toFile().deleteRecursively() }
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

    @Test fun nemotronShowsItsEffectiveWorkersInsteadOfAnIgnoredSavedPreference() {
        val descriptor = engine().copy(id = "nemotron-asr-local")
        val config = PipelineConfig(speech = EngineSelection(descriptor.id, options = mapOf("threads" to "12")))
        val state = speechState(listOf(descriptor), config, emptyList(), emptyMap(), emptyList(), null)
        assertEquals(4, state.threads)
        assertNotNull(state.fixedThreadsHint)
        assertEquals("12", config.speech?.options?.get("threads"))
        val whisper = descriptor.copy(id = "whisper-local")
        val adjustable = speechState(listOf(whisper), config.copy(speech = config.speech?.copy(engineId = whisper.id)),
            emptyList(), emptyMap(), emptyList(), null)
        assertEquals(12, adjustable.threads)
        assertNull(adjustable.fixedThreadsHint)
    }

    @Test fun customImportedSpeechWindowDisplaysItsActualDuration() {
        val descriptor = engine()
        for ((milliseconds, label) in listOf("7000" to "7 s windows", "1500" to "1.5 s windows")) {
            val config = PipelineConfig(speech = EngineSelection(descriptor.id, options = mapOf("windowMs" to milliseconds)))
            val state = speechState(listOf(descriptor), config, emptyList(), emptyMap(), emptyList(), null)
            assertEquals(label, state.windowLabel)
        }
    }
}

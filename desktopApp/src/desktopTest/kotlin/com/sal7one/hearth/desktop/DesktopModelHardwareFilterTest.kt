package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.*
import com.sal7one.hearth.desktop.data.*
import com.sal7one.hearth.desktop.ui.screens.models.*
import java.nio.file.Path
import kotlin.test.*

class DesktopModelHardwareFilterTest {
    private fun card(id: String, engine: String, stage: PipelineStage) = DesktopModelCard(id, id, "test", stage,
        engine, setOf("en", "ar"), "https://example.test/model", "test", "", emptyMap(), emptyList())
    private val whisper = card("whisper", "whisper-local", PipelineStage.STT)
    private val gguf = card("gguf", "llama-translation-local", PipelineStage.TRANSLATION)
    private val qwen = card("qwen", "qwen3-asr-local", PipelineStage.STT)
    private val ct2 = card("ct2", "marian-local", PipelineStage.TRANSLATION)
    private val tts = card("tts", "supertonic-local", PipelineStage.TTS)
    private val cards = listOf(whisper, gguf, qwen, ct2, tts)
    private fun engine(card: DesktopModelCard, acceleration: Set<String>, available: Boolean = true) =
        EngineDescriptor(card.engineId, card.name, card.stage, EngineKind.LOCAL, acceleration = acceleration, available = available)
    private fun shown(filter: ModelsFilter, engines: List<EngineDescriptor> = emptyList(), installed: Map<String, DesktopModelStatus> = emptyMap()) =
        modelsState(cards, installed, PipelineConfig(), emptyList(), filter, 0, engines = engines)

    @Test fun supportedPathsDoNotPretendCudaIsAvailableOnAMacOrCpuFamiliesHaveGpu() {
        val metal = listOf(engine(whisper, setOf("cpu", "metal")), engine(gguf, setOf("cpu", "metal")))
        assertEquals(setOf("whisper", "gguf"), shown(ModelsFilter(hardware = ModelHardwareFilter.CUDA), metal).ids())
        assertEquals(setOf("whisper", "gguf"), shown(ModelsFilter(hardware = ModelHardwareFilter.METAL)).ids())
        assertEquals(setOf("qwen", "ct2", "tts"), shown(ModelsFilter(hardware = ModelHardwareFilter.CPU_ONLY), metal).ids())
        assertEquals(setOf("whisper", "gguf"), shown(ModelsFilter(hardware = ModelHardwareFilter.AVAILABLE), metal).ids())
        assertTrue(shown(ModelsFilter(hardware = ModelHardwareFilter.AVAILABLE)).isEmptyBecauseOfFilter)
        assertTrue(shown(ModelsFilter(hardware = ModelHardwareFilter.AVAILABLE), listOf(engine(whisper, setOf("cpu", "metal"), false))).isEmpty)
        assertTrue(desktopModelGpuPaths(tts.copy(engineId = "whisper-local")).isEmpty())
    }

    @Test fun hardwareComposesWithStageSearchAndVerifiedInstallation() {
        val installed = mapOf(whisper.id to DesktopModelStatus(true, Path.of("/models/whisper")))
        assertEquals(setOf("whisper"), shown(ModelsFilter(query = "cuda", stage = PipelineStage.STT,
            onlyInstalled = true, hardware = ModelHardwareFilter.GPU), installed = installed).ids())
        assertTrue(shown(ModelsFilter(stage = PipelineStage.TTS, hardware = ModelHardwareFilter.GPU)).isEmptyBecauseOfFilter)
        val plain = shown(ModelsFilter())
        assertEquals(cards.size, plain.totalCount)
        assertEquals(cards.map { it.id }.toSet(), plain.ids())
        assertEquals(ModelHardwareFilter.ALL, ModelHardwareFilter.fromKey("future-driver"))
    }

    @Test fun availabilityUsesMatchingLocalEngineAndActualAcceleration() {
        assertTrue(availableModelGpuPaths(whisper, listOf(engine(whisper, setOf("cpu")))).isEmpty())
        assertEquals(setOf("cuda"), availableModelGpuPaths(whisper, listOf(engine(whisper, setOf("cpu", "cuda")))))
        assertTrue(availableModelGpuPaths(whisper, listOf(engine(whisper, setOf("metal")).copy(kind = EngineKind.CLOUD))).isEmpty())
        assertTrue(availableModelGpuPaths(whisper, listOf(engine(whisper, setOf("metal")).copy(stage = PipelineStage.TTS))).isEmpty())
    }
    private fun ModelsState.ids() = groups.flatMap { it.rows }.map { it.id }.toSet()
}

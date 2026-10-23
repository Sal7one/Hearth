package com.sal7one.transiber.models

import com.sal7one.common_jni.translation.Ct2Catalog
import com.sal7one.transiber.caption.CaptionEngineChoice
import com.sal7one.transiber.caption.SpeechAccelerationStore
import com.sal7one.transiber.settings.ct2BrowserMatches
import com.sal7one.transiber.settings.speechCheckpointGroups
import org.junit.Assert.*
import org.junit.Test

class ModelHardwareFilterTest {
    @Test fun speechPathsFollowRuntimeChoicesAndNeverClaimQwenGpu() {
        val catalog = SpeechArtifactCatalog.all
        for (item in catalog) assertEquals(SpeechAccelerationStore.choices(item.engine).filter { it != "cpu" }.toSet(), speechGpuPaths(item))
        for (engine in listOf(CaptionEngineChoice.WHISPER, CaptionEngineChoice.COHERE, CaptionEngineChoice.FUNASR_MLT, CaptionEngineChoice.VOXTRAL)) {
            val models = catalog.filter { it.engine == engine }
            assertTrue(models.isNotEmpty())
            models.forEach { assertEquals(setOf("vulkan"), speechGpuPaths(it)) }
        }
        catalog.filter { it.engine == CaptionEngineChoice.TRANSCRIBEPSY }.also { assertTrue(it.isNotEmpty()) }
            .forEach { assertEquals(setOf("vulkan", "opencl"), speechGpuPaths(it)) }
        catalog.filter { it.engine in setOf(CaptionEngineChoice.QWEN, CaptionEngineChoice.VOSK) }
            .forEach { assertTrue(speechGpuPaths(it).isEmpty()) }
        val arm32 = catalog.first { it.engine == CaptionEngineChoice.WHISPER }.copy(supportedAbis = setOf("armeabi-v7a"))
        assertTrue(speechGpuPaths(arm32).isEmpty())
    }

    @Test fun speechFilteringComposesWithoutSplittingCheckpointQuantizations() {
        val catalog = SpeechArtifactCatalog.all
        val gpu = speechCheckpointGroups(catalog, "", "", "", false, emptySet(), ModelHardwareFilter.GPU)
        assertTrue(gpu.isNotEmpty())
        gpu.flatten().forEach { assertTrue(speechGpuPaths(it).isNotEmpty()) }
        val opencl = speechCheckpointGroups(catalog, "", "", "", false, emptySet(), ModelHardwareFilter.OPENCL)
        assertTrue(opencl.isNotEmpty())
        assertTrue(opencl.flatten().all { it.engine == CaptionEngineChoice.TRANSCRIBEPSY })
        val selected = catalog.first { it.engine == CaptionEngineChoice.WHISPER && "ar" in it.languages }
        val shown = speechCheckpointGroups(catalog, selected.family, "ar", "", true, setOf(selected.id), ModelHardwareFilter.VULKAN)
        assertEquals(listOf(selected.id), shown.flatten().map { it.id })
        assertTrue(speechCheckpointGroups(catalog, "qwen3-asr", "", "", false, emptySet(), ModelHardwareFilter.GPU).isEmpty())
    }

    @Test fun translationGpuExcludesAllCompactCt2AndMarianRoutes() {
        assertEquals(setOf("vulkan", "opencl"), translationGpuPaths(true))
        assertTrue(ModelHardwareFilter.GPU.matches(translationGpuPaths(true)))
        assertFalse(ModelHardwareFilter.CPU_ONLY.matches(translationGpuPaths(true)))
        assertTrue(ModelHardwareFilter.CPU_ONLY.matches(translationGpuPaths(false)))
        for (spec in Ct2Catalog.models) {
            for (filter in listOf(ModelHardwareFilter.GPU, ModelHardwareFilter.VULKAN, ModelHardwareFilter.OPENCL))
                assertFalse(ct2BrowserMatches(spec, "", "any", "any", "any", filter))
            assertTrue(ct2BrowserMatches(spec, "", "any", "any", "any", ModelHardwareFilter.CPU_ONLY))
        }
        assertEquals(ModelHardwareFilter.ALL, ModelHardwareFilter.fromKey("metal"))
    }
}

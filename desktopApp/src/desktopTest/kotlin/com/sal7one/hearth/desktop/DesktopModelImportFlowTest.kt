package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import com.sal7one.hearth.desktop.ui.screens.models.ModelsFilter
import com.sal7one.hearth.desktop.ui.screens.models.ModelStatus
import com.sal7one.hearth.desktop.ui.screens.models.modelsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files
import kotlin.test.*

class DesktopModelImportFlowTest {
    @Test fun verifyingAnExistingPathEnablesExplicitUseAndRechecksSelectedImports() = runTest {
        val directory = Files.createTempDirectory("hearth-existing-model-")
        try {
            val library = DesktopModelLibrary(directory)
            val card = library.catalog.first { it.stage == PipelineStage.STT && it.archive == null && it.artifacts.size == 1 }
            val picked = Files.write(directory.resolve("existing-model.bin"), byteArrayOf(1, 2, 3))
            val verified = EngineSelection(card.engineId, card.id, picked.toString(), options = mapOf("sha256" to card.artifacts.single().sha256))
            val checkedPaths = mutableListOf<java.nio.file.Path>()
            val controller = DesktopModelController(library, backgroundScope, { fail(it) }) { id, path ->
                assertEquals(card.id, id); checkedPaths.add(path); verified
            }
            val original = PipelineConfig(translation = EngineSelection("openai-translation", credentialId = "mt-key"),
                voice = EngineSelection("system-tts"), sourceLanguage = "ar", targetLanguage = "en")
            try {
                controller.verify(card.id, picked)
                controller.state.first { it.transfer == null }
                val beforeUse = modelsState(listOf(card), emptyMap(), original, null, ModelsFilter(onlyInstalled = true), 0,
                    controller.state.value.verifiedImports, controller.state.value.issues)
                val row = beforeUse.groups.flatMap { it.rows }.single()
                assertEquals(ModelStatus.INSTALLED, row.status)
                assertEquals("Use", row.actionLabel)
                assertFalse(row.inUse)
                assertNull(original.speech, "Checking an import must not activate a stage")
                assertEquals(verified, controller.verifiedSelection(card.id))

                // This is the same explicit Use transition the production action invokes.
                val selected = original.useVerifiedModel(card.stage, requireNotNull(controller.verifiedSelection(card.id)))
                assertEquals(verified, selected.speech)
                assertEquals(original.translation, selected.translation)
                assertEquals(original.voice, selected.voice)
                controller.refresh(selected)
                controller.state.first { !it.refreshing }
                assertEquals(listOf(picked, picked), checkedPaths)
                val afterUse = modelsState(listOf(card), emptyMap(), selected, null, ModelsFilter(), 0,
                    controller.state.value.verifiedImports, controller.state.value.issues)
                assertTrue(afterUse.groups.flatMap { it.rows }.single().inUse)
            } finally { controller.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun aFileThatFailsRealCatalogVerificationShowsTheIssueAndCannotBeUsed() = runTest {
        val directory = Files.createTempDirectory("hearth-bad-model-")
        try {
            val library = DesktopModelLibrary(directory)
            val card = library.catalog.first { it.stage == PipelineStage.STT && it.archive == null && it.artifacts.size == 1 }
            val bad = Files.write(directory.resolve("bad-model.bin"), byteArrayOf(1, 2, 3))
            val errors = mutableListOf<String>()
            val controller = DesktopModelController(library, backgroundScope, errors::add)
            try {
                controller.verify(card.id, bad)
                controller.state.first { it.transfer == null }
                assertNull(controller.verifiedSelection(card.id))
                val view = modelsState(listOf(card), emptyMap(), PipelineConfig(), null, ModelsFilter(), 0,
                    controller.state.value.verifiedImports, controller.state.value.issues)
                val row = view.groups.flatMap { it.rows }.single()
                assertEquals(ModelStatus.BROKEN, row.status)
                assertFalse(row.inUse)
                assertNotNull(row.issue)
                assertTrue(errors.single().contains("pinned model"))
                assertFalse(view.issues.isEmpty())
            } finally { controller.close() }
        } finally { directory.toFile().deleteRecursively() }
    }
}

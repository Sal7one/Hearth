package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopModelControllerTest {
    @Test fun startupRestoresRecordedReaderWithOnePinnedVerification() = runTest {
        val directory = Files.createTempDirectory("hearth-desktop-reading-startup")
        try {
            val library = DesktopModelLibrary(directory)
            val reader = library.find(selectedReadingModel(emptyMap()))
            val path = directory.resolve("external-reader")
            val route = EngineSelection(reader.engineId, reader.id, path.toString())
            library.downloads.imported(reader.id, path)
            val verified = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val controller = DesktopModelController(library, backgroundScope, errors::add) { id, checked ->
                assertEquals(path, checked)
                verified.add(id)
                route
            }
            val savedReader = controller.restoreReadingSelection(reader.id, path.toString())
            assertEquals(reader.id, savedReader)
            assertTrue(controller.state.value.transfers[reader.id]?.importing == true)
            controller.refresh(PipelineConfig(), startupReaderId = savedReader)
            controller.state.first { !it.refreshing && it.transfers.isEmpty() }
            assertEquals(listOf(reader.id), verified)
            assertEquals(route, controller.verifiedSelection(reader.id))
            assertTrue(errors.isEmpty())
            controller.close()
        } finally {
            withContext(Dispatchers.IO) { directory.toFile().deleteRecursively() }
        }
    }

    @Test fun startupShowsManagedReaderCheckWithoutAnImportTransfer() = runTest {
        val directory = Files.createTempDirectory("hearth-desktop-managed-reader-startup")
        try {
            val library = DesktopModelLibrary(directory)
            val reader = library.find(selectedReadingModel(emptyMap()))
            val path = directory.resolve("models").resolve(reader.id).toString()
            val controller = DesktopModelController(library, backgroundScope, { error(it) }) { _, _ ->
                error("A managed reader must not run an independent import")
            }
            val savedReader = controller.restoreReadingSelection(reader.id, path)
            controller.refresh(PipelineConfig(), startupReaderId = savedReader)
            assertEquals(path, controller.state.value.verifyingSelections[reader.id])
            assertTrue(controller.state.value.transfers.isEmpty())
            controller.state.first { !it.refreshing }
            assertFalse(reader.id in controller.state.value.verifyingSelections)
            controller.close()
        } finally {
            withContext(Dispatchers.IO) { directory.toFile().deleteRecursively() }
        }
    }

    @Test fun damagedSpeechImportDoesNotHideAnIndependentlyHealthyTranslator() = runTest {
        val directory = Files.createTempDirectory("hearth-desktop-model-ui")
        try {
            val library = DesktopModelLibrary(directory)
            val speech = library.catalog.first { it.stage == PipelineStage.STT }
            val translation = library.catalog.first { it.stage == PipelineStage.TRANSLATION }
            val healthy = EngineSelection(translation.engineId, translation.id, directory.resolve("translator.gguf").toString())
            val errors = mutableListOf<String>()
            val controller = DesktopModelController(library, backgroundScope, errors::add) { id, _ ->
                if (id == speech.id) error("Speech checksum differs from the pinned model")
                else healthy
            }
            controller.refresh(PipelineConfig(
                speech = EngineSelection(speech.engineId, speech.id, directory.resolve("broken.ggml").toString()),
                translation = healthy,
            ))
            controller.state.first { !it.refreshing }
            assertEquals(mapOf(translation.id to healthy), controller.state.value.verifiedImports)
            assertEquals("Speech checksum differs from the pinned model", controller.state.value.issues[speech.id])
            assertTrue(errors.single().contains(speech.name))
            assertFalse(translation.id in controller.state.value.issues)
            controller.close()
        } finally {
            withContext(Dispatchers.IO) { directory.toFile().deleteRecursively() }
        }
    }
}

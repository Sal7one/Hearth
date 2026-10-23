package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.desktop.data.*
import com.sal7one.hearth.desktop.ui.screens.downloads.*
import com.sal7one.hearth.desktop.ui.screens.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopModelJobsTest {
    @Test fun aPausedDownloadSurvivesControllerAndJournalRestartWithoutLosingItsPrefix() = runTest {
        val directory = Files.createTempDirectory("hearth-durable-model-queue-")
        try {
            val library = DesktopModelLibrary(directory)
            val card = library.catalog.first()
            val first = DesktopModelController(library, backgroundScope, { fail(it) }, downloadModel = { _, progress ->
                progress(ModelInstallProgress(ModelInstallPhase.DOWNLOADING, 50, 100))
                awaitCancellation()
            })
            first.download(card.id); runCurrent()
            first.pause(card.id); runCurrent()
            assertTrue(first.state.value.transfers.getValue(card.id).paused)
            first.close()
            val restored = DesktopModelLibrary(directory)
            val second = DesktopModelController(restored, backgroundScope, { fail(it) }, downloadModel = { id, _ ->
                EngineSelection(card.engineId, id)
            })
            try {
                assertTrue(second.state.value.transfers.getValue(card.id).paused)
                assertEquals(50L, second.state.value.transfers.getValue(card.id).downloaded)
                second.resumeAll(); runCurrent()
                assertTrue(second.state.value.transfers.isEmpty())
                assertEquals(DesktopQueuePhase.COMPLETE, restored.downloads.state.value.queue.single().phase)
            } finally { second.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun unselectedExternalImportsAreRememberedAndReverifiedAtTheNextLaunch() = runTest {
        val directory = Files.createTempDirectory("hearth-import-provenance-")
        try {
            val library = DesktopModelLibrary(directory)
            val card = library.catalog.first()
            val picked = directory.resolve("external-model.bin")
            val verified = EngineSelection(card.engineId, card.id, picked.toString())
            val first = DesktopModelController(library, backgroundScope, { fail(it) }, verifyModel = { _, _ -> verified })
            first.verify(card.id, picked); runCurrent(); first.close()
            val checked = mutableListOf<java.nio.file.Path>()
            val second = DesktopModelController(DesktopModelLibrary(directory), backgroundScope, { fail(it) }, verifyModel = { _, path ->
                checked.add(path); verified
            })
            try {
                assertNull(second.verifiedSelection(card.id), "A saved path alone must never mark an import ready")
                second.refresh(PipelineConfig())
                second.state.first { !it.refreshing }
                assertEquals(listOf(picked), checked)
                assertEquals(verified, second.verifiedSelection(card.id))
            } finally { second.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun anOlderRefreshCannotEraseANewerSuccessfulImport() = runTest {
        val directory = Files.createTempDirectory("hearth-model-refresh-race-")
        try {
            val library = DesktopModelLibrary(directory)
            val card = library.catalog.first()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val errors = mutableListOf<String>()
            val fresh = EngineSelection(card.engineId, card.id, directory.resolve("fresh.bin").toString())
            val controller = DesktopModelController(library, backgroundScope, errors::add, verifyModel = { _, path ->
                if (path.fileName.toString() == "old.bin") {
                    entered.complete(Unit)
                    release.await()
                    error("old path was removed")
                }
                fresh
            })
            try {
                controller.refresh(PipelineConfig(speech = fresh.copy(modelPath = directory.resolve("old.bin").toString())))
                entered.await()
                controller.verify(card.id, directory.resolve("fresh.bin"))
                runCurrent()
                assertEquals(fresh, controller.verifiedSelection(card.id))
                release.complete(Unit)
                controller.state.first { !it.refreshing }
                assertEquals(fresh, controller.verifiedSelection(card.id))
                assertTrue(errors.isEmpty())
                assertFalse(card.id in controller.state.value.issues)
            } finally { release.complete(Unit); controller.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun twoDownloadsQueueTheThirdWhileAnImportAndSetupRemainAvailable() = runTest {
        val directory = Files.createTempDirectory("hearth-model-jobs-")
        try {
            val library = DesktopModelLibrary(directory)
            val cards = library.catalog.take(4)
            val release = CompletableDeferred<Unit>()
            val started = mutableListOf<String>()
            val controller = DesktopModelController(library, backgroundScope, { fail(it) }, downloadModel = { id, progress ->
                started += id
                progress(ModelInstallProgress(ModelInstallPhase.DOWNLOADING, 5, 10))
                release.await()
                EngineSelection(library.find(id).engineId, id)
            }, verifyModel = { id, path -> EngineSelection(library.find(id).engineId, id, path.toString()) })
            try {
                cards.take(3).forEach { controller.download(it.id) }
                controller.verify(cards[3].id, directory.resolve("existing.bin"))
                runCurrent()
                assertEquals(cards.take(2).map { it.id }, started)
                assertTrue(controller.state.value.transfers.getValue(cards[2].id).queued)
                assertEquals("existing.bin", java.nio.file.Path.of(requireNotNull(controller.verifiedSelection(cards[3].id)).modelPath).fileName.toString())
                controller.checkSetupImportAvailable()
                controller.reconnectImportedSetup(PipelineConfig())

                val view = modelsState(cards, emptyMap(), PipelineConfig(), controller.state.value.transfers.values, ModelsFilter(), 0,
                    controller.state.value.verifiedImports)
                val rows = view.groups.flatMap { it.rows }.associateBy { it.id }
                assertEquals("Downloading", rows.getValue(cards[0].id).actionLabel)
                assertEquals("Queued", rows.getValue(cards[2].id).actionLabel)
                assertEquals("Use", rows.getValue(cards[3].id).actionLabel)
                assertTrue(rows.getValue(cards[3].id).actionEnabled)

                controller.cancel(cards[2].id)
                runCurrent()
                assertFalse(cards[2].id in controller.state.value.transfers)
                assertEquals(2, controller.state.value.transfers.size)
                assertEquals(cards.take(2).map { it.id }, started, "Cancelling a queued job must not run it")
                release.complete(Unit)
                runCurrent()
                assertTrue(controller.state.value.transfers.isEmpty())
                assertTrue(controller.state.value.notice.orEmpty().contains("installed"))
            } finally { controller.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun cancelWaitsForCleanupBeforeFreeingItsSlotAndNeverCancelsAnotherModel() = runTest {
        val directory = Files.createTempDirectory("hearth-model-cancel-")
        try {
            val library = DesktopModelLibrary(directory)
            val cards = library.catalog.take(3)
            val cleanup = CompletableDeferred<Unit>()
            val started = mutableListOf<String>()
            val controller = DesktopModelController(library, backgroundScope, { fail(it) }, downloadModel = { id, _ ->
                started += id
                try { awaitCancellation() }
                finally { if (id == cards[0].id) withContext(NonCancellable) { cleanup.await() } }
            })
            try {
                cards.forEach { controller.download(it.id) }
                runCurrent()
                controller.cancel(cards[0].id)
                runCurrent()
                assertTrue(controller.state.value.transfers.getValue(cards[0].id).cancelling)
                controller.download(cards[0].id)
                runCurrent()
                assertEquals(cards.take(2).map { it.id }, started, "No duplicate or replacement while the old owner is draining")
                cleanup.complete(Unit)
                runCurrent()
                assertEquals(cards.map { it.id }, started)
                assertFalse(cards[0].id in controller.state.value.transfers)
                assertFalse(controller.state.value.transfers.getValue(cards[1].id).cancelling)
            } finally { cleanup.complete(Unit); controller.close() }
            assertTrue(controller.state.value.transfers.isEmpty())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun unpackingAndVerificationHaveIndependentProgressAndFailureDoesNotStopAnotherJob() = runTest {
        val directory = Files.createTempDirectory("hearth-model-phases-")
        try {
            val library = DesktopModelLibrary(directory)
            val cards = library.catalog.take(2)
            val extract = CompletableDeferred<Unit>()
            val verify = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val errors = mutableListOf<String>()
            val controller = DesktopModelController(library, backgroundScope, errors::add, downloadModel = { id, progress ->
                if (id == cards[1].id) awaitCancellation()
                progress(ModelInstallProgress(ModelInstallPhase.DOWNLOADING, 100, 100))
                extract.await()
                progress(ModelInstallProgress(ModelInstallPhase.EXTRACTING, 30, 200, "decoder.onnx"))
                verify.await()
                progress(ModelInstallProgress(ModelInstallPhase.VERIFYING, 40, 200, "decoder.onnx"))
                finish.await()
                error("Model integrity verification failed")
            })
            try {
                cards.forEach { controller.download(it.id) }
                runCurrent()
                assertEquals("Downloading", controller.state.value.transfers.getValue(cards[0].id).label,
                    "Receiving the last compressed byte does not mean verification has begun")
                extract.complete(Unit); runCurrent()
                var view = downloadsState(cards, emptyMap(), controller.state.value.transfers.values, 0)
                assertEquals(0.15f, view.downloading.first().progress)
                assertTrue(view.downloading.first().detail.startsWith("Unpacking"))
                verify.complete(Unit); runCurrent()
                view = downloadsState(cards, emptyMap(), controller.state.value.transfers.values, 0)
                assertTrue(view.downloading.first().detail.startsWith("Verifying"))
                assertEquals(0.2f, view.downloading.first().progress)
                finish.complete(Unit); runCurrent()
                view = downloadsState(cards, emptyMap(), controller.state.value.transfers.values, 0, controller.state.value.issues)
                assertEquals(cards[0].id, view.broken.single().id)
                assertEquals(cards[1].id, view.downloading.single().id)
                assertTrue(errors.single().contains("integrity"))
            } finally { controller.close() }
        } finally { directory.toFile().deleteRecursively() }
    }
}

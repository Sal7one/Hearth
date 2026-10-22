package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.PipelineStage
import kotlinx.coroutines.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopModelConcurrencyTest {
    @Test fun failedRepairImmediatelyInvalidatesAPreviouslyReadyInstallation() = runBlocking {
        val directory = Files.createTempDirectory("hearth-failed-model-repair-")
        try {
            val bytes = byteArrayOf(1, 2, 3)
            // A disallowed scheme fails before any request can leave the process.
            val card = DesktopModelCard("model", "Model", "test", PipelineStage.STT, "whisper", setOf("en"),
                "https://example.invalid", "test", "", emptyMap(),
                listOf(DesktopModelArtifact("model.bin", "http://example.invalid/model.bin", 3, DesktopModelLibrary.digest(bytes))))
            val root = directory.resolve("models/model"); Files.createDirectories(root)
            val file = Files.write(root.resolve("model.bin"), bytes)
            val library = DesktopModelLibrary(directory, listOf(card))
            library.refresh()
            assertNotNull(library.installedSelection(card.id))
            Files.write(file, byteArrayOf(9, 8, 7))
            assertFailsWith<IllegalStateException> { library.download(card.id) }
            assertFalse(library.installations.value.getValue(card.id).ready)
            assertNull(library.installedSelection(card.id))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun aDifferentImportAndDownloadCompleteWhileOneModelOwnsItsInstaller() = runBlocking {
        val directory = Files.createTempDirectory("hearth-model-locks-")
        val release = CountDownLatch(1)
        try {
            val bytes = byteArrayOf(1, 2, 3)
            val cards = listOf("first", "second").map { id ->
                DesktopModelCard(id, id, "test", PipelineStage.STT, "whisper", setOf("en"), "https://example.invalid", "test", "", emptyMap(),
                    listOf(DesktopModelArtifact("model.bin", "https://example.invalid/model.bin", bytes.size.toLong(), DesktopModelLibrary.digest(bytes))))
            }
            cards.forEach { card -> Files.createDirectories(directory.resolve("models/${card.id}")); Files.write(directory.resolve("models/${card.id}/model.bin"), bytes) }
            val library = DesktopModelLibrary(directory, cards)
            val started = CompletableDeferred<Unit>()
            val first = async(Dispatchers.IO) {
                library.download(cards[0].id, activity = {
                    if (started.complete(Unit)) check(release.await(10, TimeUnit.SECONDS))
                })
            }
            try {
                withTimeout(5000) { started.await() }
                val imported = withTimeout(5000) { library.verify(cards[1].id, directory.resolve("models/second/model.bin")) }
                assertEquals(cards[1].id, imported.modelId)
                // Already verified files need no HTTP call, including a second installation.
                withTimeout(5000) { library.download(cards[1].id) }
                assertFalse(first.isCompleted)
            } finally { release.countDown() }
            first.await()
            assertTrue(library.installations.value.values.all { it.ready })
            assertEquals(2, library.installations.value.size, "Concurrent completion must preserve both entries")
        } finally { release.countDown(); directory.toFile().deleteRecursively() }
    }
}

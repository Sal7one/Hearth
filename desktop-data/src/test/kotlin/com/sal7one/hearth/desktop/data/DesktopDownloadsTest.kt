package com.sal7one.hearth.desktop.data

import com.sal7one.hearth.core.PipelineStage
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopDownloadsTest {
    @Test fun hundredsOfQueueEntriesOptionsImportsAndMeasurementsSurviveRestart() {
        val directory = Files.createTempDirectory("hearth-download-journal-")
        try {
            val store = DesktopDownloadStore(directory)
            val options = DesktopDownloadOptions(DesktopDownloadBackend.HTTP, 6, 12, 2_000_000, 5)
            store.options(options)
            repeat(300) { store.queue(DesktopQueueEntry("model-$it", DesktopQueuePhase.WAITING, it.toLong(), 500)) }
            store.queue(DesktopQueueEntry("model-1", DesktopQueuePhase.FAILED, issue = "HTTP 503"))
            store.imported("external", directory.resolve("external.onnx"))
            store.measured(DesktopTransferMeasurement("model-2", "model.bin", "http", 1024, 1000, 512, 1, 100))
            val restored = DesktopDownloadStore(directory).state.value
            assertEquals(options, restored.options)
            assertEquals(300, restored.queue.size)
            assertEquals(DesktopQueuePhase.PAUSED, restored.queue.first().phase, "An interrupted process restores as paused, not a fake running transfer")
            assertEquals("HTTP 503", restored.queue[1].issue)
            assertEquals(directory.resolve("external.onnx").toString(), restored.imports["external"])
            assertEquals(1024L, restored.measurements.single().bytesPerSecond)
            val csv = directory.resolve("benchmarks.csv")
            store.exportMeasurements(csv)
            assertTrue(Files.readString(csv).contains("network_bytes,elapsed_ms,resumed_bytes"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun malformedJournalIsReportedAndRetainedForRecovery() {
        val directory = Files.createTempDirectory("hearth-download-damaged-")
        try {
            Files.writeString(directory.resolve("download-queue.json"), "broken")
            val store = DesktopDownloadStore(directory)
            assertTrue(store.state.value.issue.orEmpty().contains("could not be read"))
            store.options(DesktopDownloadOptions.Fast)
            assertTrue(Files.list(directory).use { it.anyMatch { file -> file.fileName.toString().startsWith("download-queue.damaged-") } })
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun interruptedHttpTransferResumesItsExactPinnedPrefix() = runBlocking {
        val directory = Files.createTempDirectory("hearth-http-resume-")
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val ranges = Collections.synchronizedList(mutableListOf<String>())
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.bin") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range").orEmpty(); ranges += range
            val start = range.removePrefix("bytes=").substringBefore('-').toLongOrNull()?.toInt() ?: 0
            exchange.responseHeaders.add("ETag", "\"fixture-v1\"")
            if (range.isNotEmpty()) exchange.responseHeaders.add("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
            exchange.sendResponseHeaders(if (range.isEmpty()) 200 else 206, (bytes.size - start).toLong())
            try {
                exchange.responseBody.use { output ->
                    if (requests.getAndIncrement() == 0) output.write(bytes, 0, bytes.size / 2)
                    else output.write(bytes, start, bytes.size - start)
                }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val artifact = DesktopModelArtifact("model.bin", "http://127.0.0.1:${server.address.port}/model.bin", bytes.size.toLong(), DesktopModelLibrary.digest(bytes))
            val target = directory.resolve("partial.bin")
            val transfer = DesktopArtifactTransfer(allowLoopbackForTests = true)
            assertFailsWith<Exception> { transfer.download(artifact, target, DesktopDownloadOptions(backend = DesktopDownloadBackend.HTTP, retries = 0)) {} }
            val prefix = Files.size(target)
            assertTrue(prefix in 1 until bytes.size)
            val result = transfer.download(artifact, target, DesktopDownloadOptions(backend = DesktopDownloadBackend.HTTP, retries = 0)) {}
            assertEquals("bytes=$prefix-", ranges.last())
            assertEquals(prefix, result.resumedBytes)
            assertEquals(bytes.size - prefix, result.networkBytes)
            assertEquals(artifact.sha256, DesktopModelLibrary.digest(target))
            assertEquals(1, result.connections, "Plain HTTP must never claim parallel aria2 connections")
        } finally { server.stop(0); directory.toFile().deleteRecursively() }
    }

    @Test fun hashingOneModelReleasesItsNetworkSlotForAnotherModel() = runBlocking {
        val directory = Files.createTempDirectory("hearth-transfer-lanes-")
        val bytes = ByteArray(1024) { it.toByte() }
        val secondRequested = CountDownLatch(1)
        val releaseHash = CountDownLatch(1)
        val hashing = CompletableDeferred<Unit>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model") { exchange ->
            if (exchange.requestURI.query == "second") secondRequested.countDown()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val cards = listOf("first", "second").map { id -> DesktopModelCard(id, id, "fixture", PipelineStage.STT,
                "whisper", setOf("en"), "https://example.invalid", "test", "", emptyMap(), listOf(
                    DesktopModelArtifact("model.bin", "http://127.0.0.1:${server.address.port}/model?$id", bytes.size.toLong(), DesktopModelLibrary.digest(bytes)))) }
            val library = DesktopModelLibrary(directory, cards, DesktopArtifactTransfer(allowLoopbackForTests = true))
            library.configureDownloads(DesktopDownloadOptions(backend = DesktopDownloadBackend.HTTP, simultaneousFiles = 1))
            val first = async(Dispatchers.IO) { library.download("first", activity = {
                if (it.phase == ModelInstallPhase.VERIFYING && hashing.complete(Unit)) check(releaseHash.await(10, TimeUnit.SECONDS))
            }) }
            withTimeout(5000) { hashing.await() }
            val second = async(Dispatchers.IO) { library.download("second") }
            try { assertTrue(secondRequested.await(5, TimeUnit.SECONDS), "A queued hash must not hold a network permit or block an empty model's preflight") }
            finally { releaseHash.countDown() }
            first.await(); second.await()
            assertEquals(2, library.installations.value.values.count { it.ready })
            assertEquals(2, library.downloads.state.value.measurements.size)
        } finally { releaseHash.countDown(); server.stop(0); directory.toFile().deleteRecursively() }
    }

    @Test fun productionTransportRejectsPlainHttpBeforeMakingARequest() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("hearth-http-policy-")
        try {
            assertFailsWith<IllegalStateException> {
                DesktopArtifactTransfer().download(DesktopModelArtifact("model", "http://127.0.0.1:1/model", 1, "0".repeat(64)), directory.resolve("part"), DesktopDownloadOptions()) {}
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}

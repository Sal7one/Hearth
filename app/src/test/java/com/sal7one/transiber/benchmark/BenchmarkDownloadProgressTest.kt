package com.sal7one.transiber.benchmark

import android.app.DownloadManager
import com.sal7one.transiber.downloads.FileDownload
import com.sal7one.transiber.translation.MarianPackage
import org.junit.Assert.*
import org.junit.Test

class BenchmarkDownloadProgressTest {
    private fun row(id: Long, model: String, phase: String, bytes: Long, error: String = "") = FileDownload(
        id, model, when (phase) {
            "Complete", "Installed" -> DownloadManager.STATUS_SUCCESSFUL
            "Failed" -> DownloadManager.STATUS_FAILED
            "Paused" -> DownloadManager.STATUS_PAUSED
            else -> DownloadManager.STATUS_RUNNING
        }, 0, bytes, 100, phase = phase, error = error, modelId = model,
    )

    @Test fun marianShowsAllPartsAndTheCurrentTransfer() {
        val pair = requireNotNull(MarianPackage.find("marian-en-ar"))
        val model = SuggestedModel(pair.id, pair.id, pair.downloadBytes, "marian")
        val rows = listOf(
            row(-1, pair.parts[0].id, "Complete", 100),
            row(-2, pair.parts[1].id, "Downloading", 50),
            row(-3, pair.parts[2].id, "Waiting", 0),
        )
        val progress = requireNotNull(BenchmarkDownloadProgress.forModel(model, rows))
        assertEquals(4, progress.expectedFiles)
        assertEquals(1, progress.doneFiles)
        assertTrue(progress.active)
        assertFalse(progress.complete)
        assertEquals("Downloading", progress.currentPhase)
        assertEquals(150L, progress.bytes)
        assertEquals(pair.downloadBytes, progress.total)
        assertNull(BenchmarkDownloadProgress.forModel(model, emptyList()))
    }

    @Test fun newestRecordControlsTheVisibleStateAndFailureIsPreserved() {
        val model = SuggestedModel("speech-id", "Speech", 100L, "speech")
        val progress = requireNotNull(BenchmarkDownloadProgress.forModel(model, listOf(
            row(-1, model.id, "Installed", 100),
            row(-2, model.id, "Failed", 35, "HTTP 503"),
        )))
        assertTrue(progress.failed)
        assertFalse(progress.complete)
        assertEquals(listOf("HTTP 503"), progress.errors)
        assertEquals(0.35f, progress.fraction!!, 0.001f)
    }
    @Test fun audarRequiresBothDecoderAndEncoderRatherThanAnAggregateTransfer() {
        val group = requireNotNull(com.sal7one.transiber.models.SpeechComponents.find("audar-asr-v1-flash-q4_k_m"))
        val model = SuggestedModel(group.id, "Audar", group.parts.sumOf { it.bytes }, "speech")
        val first = row(-1, group.parts.first().id, "Installed", 100)
        val partial = requireNotNull(BenchmarkDownloadProgress.forModel(model, listOf(first)))
        assertEquals(2, partial.expectedFiles)
        assertFalse(partial.complete)
        val complete = requireNotNull(BenchmarkDownloadProgress.forModel(model,
            listOf(first, row(-2,group.parts.last().id,"Installed",100))))
        assertTrue(complete.complete)
    }
    @Test fun ct2TracksEveryPinnedFileRatherThanReportingOneFileAsTheModel() {
        val spec = requireNotNull(com.sal7one.common_jni.translation.Ct2Catalog.find("ct2-quickmt-en-ar"))
        val parts = com.sal7one.transiber.translation.Ct2Package.parts(spec)
        val model = SuggestedModel(spec.id, spec.label, spec.bytes, "ct2")
        val partial = requireNotNull(BenchmarkDownloadProgress.forModel(model,
            listOf(row(-1, parts.first().id, "Installed", parts.first().file.bytes))))
        assertEquals(spec.files.size, partial.expectedFiles)
        assertFalse(partial.complete)
        val complete = requireNotNull(BenchmarkDownloadProgress.forModel(model,
            parts.mapIndexed { index, part -> row(-index.toLong() - 1, part.id, "Installed", part.file.bytes) }))
        assertTrue(complete.complete)
        assertEquals(spec.bytes, complete.bytes)
    }

    @Test fun voiceBundleIncludesStylesAndRuntimeAssets() {
        val assets = com.sal7one.transiber.voice.VoiceCatalog.familyAssets("supertonic")
        val model = SuggestedModel("supertonic", "Supertonic 3", assets.sumOf { it.bytes }, "voice")
        val all = assets.mapIndexed { index, asset -> row(-index.toLong() - 1, asset.id, "Installed", asset.bytes) }
        val partial = requireNotNull(BenchmarkDownloadProgress.forModel(model, all.dropLast(1)))
        assertEquals(assets.size, partial.expectedFiles)
        assertFalse(partial.complete)
        assertTrue(requireNotNull(BenchmarkDownloadProgress.forModel(model, all)).complete)
    }

}

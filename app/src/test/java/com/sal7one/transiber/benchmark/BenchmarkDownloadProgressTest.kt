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
}

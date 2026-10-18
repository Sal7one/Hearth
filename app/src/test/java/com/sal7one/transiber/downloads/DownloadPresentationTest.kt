package com.sal7one.transiber.downloads

import android.app.DownloadManager
import org.junit.Assert.*
import org.junit.Test

class DownloadPresentationTest {
    private fun item(phase: String = "", status: Int = DownloadManager.STATUS_RUNNING, bytes: Long = 100,
        total: Long = 100) = FileDownload(-1, "model", status, 0, bytes, total, phase)

    @Test fun completedTransferIsNotShownAsCompletedInstallation() {
        for ((phase, expected) in listOf("Verifying" to DownloadStage.VERIFYING, "Installing" to DownloadStage.INSTALLING)) {
            val record = item(phase, DownloadManager.STATUS_SUCCESSFUL)
            assertEquals(expected, record.displayStage())
            assertNull(record.transferFraction())
            assertEquals(0, record.queuePriority())
        }
        assertEquals(DownloadStage.READY, item("Complete").displayStage())
        assertEquals(DownloadStage.INSTALLED, item("Installed").displayStage())
    }

    @Test fun systemDownloadsAndInterruptedTransfersHaveUsefulStates() {
        assertEquals(DownloadStage.PAUSED, item(status = DownloadManager.STATUS_PAUSED).displayStage())
        assertEquals(DownloadStage.WAITING, item(status = DownloadManager.STATUS_PENDING).displayStage())
        assertEquals(DownloadStage.READY, item(status = DownloadManager.STATUS_SUCCESSFUL).displayStage())
        assertEquals(DownloadStage.FAILED, item("Interrupted").displayStage())
        assertEquals(1, item("Interrupted").queuePriority())
    }

    @Test fun progressIsBoundedAndOnlyDescribesTransferBytes() {
        assertEquals(0.5f, item("Downloading", bytes = 50).transferFraction()!!, 0f)
        assertEquals(1f, item("Downloading", bytes = 150).transferFraction()!!, 0f)
        assertEquals(0f, item("Downloading", bytes = -1).transferFraction()!!, 0f)
        assertNull(item("Downloading", total = -1).transferFraction())
        assertNull(item("Paused").transferFraction())
        assertNull(item("Installed").transferFraction())
    }
}

package com.sal7one.transiber.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadTransferResultsTest {
    @Test fun resultsCompareActualTransferredBytesRatherThanWholeResumedFile() {
        val record = FileDownload(-1, "model", 8, 0, 1_000_000, 1_000_000,
            phase = "Installed", backend = "http", transferMillis = 1000, transferredBytes = 100_000)
        val result = transferResults(listOf(record)).single()
        assertEquals(100_000.0, result.bytesPerSecond, 0.0)
        assertEquals(100_000L, result.transferredBytes)
    }
    @Test fun unmeasuredAndLegacyRecordsAreExcludedAndFastestComesFirst() {
        val base = FileDownload(-1, "model", 8, 0, 100, 100, phase = "Complete", backend = "aria2", transferMillis = 1000, transferredBytes = 100)
        val results = transferResults(listOf(base, base.copy(id = -2, title = "fast", transferredBytes = 200), base.copy(transferMillis = 0), base.copy(backend = "")))
        assertEquals(listOf("fast", "model"), results.map { it.title })
    }
    @Test fun csvEscapesNamesAndNeutralizesSpreadsheetFormulas() {
        val result = DownloadTransferResult("=bad,\"title\"", "model", "http", 1, 100, 1000, 2, "Installed")
        val csv = transferResultsCsv(listOf(result))
        assertTrue(csv.contains("\"'=bad,\"\"title\"\"\""))
        assertTrue(csv.contains(",100,1000,100.00,2,"))
    }
    @Test fun policyRejectsInvalidIndependentLimits() {
        assertThrows(IllegalArgumentException::class.java) { DownloadQueuePolicy(simultaneousFiles = 0) }
        assertThrows(IllegalArgumentException::class.java) { DownloadQueuePolicy(connectionsPerFile = 17) }
        assertThrows(IllegalArgumentException::class.java) { DownloadQueuePolicy(backend = "shell") }
        assertEquals(4, DownloadQueuePolicy.FAST.simultaneousFiles)
        assertEquals(8, DownloadQueuePolicy.FAST.connectionsPerFile)
    }
}

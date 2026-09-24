package com.sal7one.transiber.downloads

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileDownloadsReuseTest {
    private fun record(id: Long, phase: String, model: String = "model", url: String = "https://example.org/model") =
        JSONObject().put("id", id).put("phase", phase).put("model", model).put("url", url)

    @Test fun modelUsesItsExistingTransferIncludingCompletedAndFailedRecords() {
        val url = "https://example.org/model"
        assertEquals(-2L, reusableDownload(listOf(record(-1, "Complete"), record(-2, "Downloading")), url, "model")?.getLong("id"))
        assertEquals(-1L, reusableDownload(listOf(record(-1, "Installed")), url, "model")?.getLong("id"))
        assertEquals(-1L, reusableDownload(listOf(record(-1, "Complete")), url, "model")?.getLong("id"))
        assertEquals(-1L, reusableDownload(listOf(record(-1, "Failed")), url, "model")?.getLong("id"))
        assertNull(reusableDownload(listOf(record(-1, "Installed")), "https://example.org/new-revision", "model"))
    }

    @Test fun completedDirectFileCanBeDownloadedAgain() {
        val records = listOf(record(-1, "Complete", model = ""))
        assertNull(reusableDownload(records, "https://example.org/model", ""))
        assertEquals(-2L, reusableDownload(records + record(-2, "Paused", model = ""),
            "https://example.org/model", "")?.getLong("id"))
    }
}

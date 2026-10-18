package com.sal7one.transiber.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSourceUrlsTest {
    @Test fun onlyPublicHttpsLinksWithoutEmbeddedCredentialsCanBeOpened() {
        assertTrue(isSafeModelSourceUrl("https://huggingface.co/org/model/tree/abc123"))
        assertTrue(isSafeModelSourceUrl("https://github.com/org/repo#license"))
        listOf("http://example.com", "file:///model.gguf", "intent://download", "https://key@example.com/model",
            "https://", "https://bad host/model").forEach { assertFalse(it, isSafeModelSourceUrl(it)) }
    }

    @Test fun pinnedWeightSourcesOpenAReadableFilePageRatherThanStartingADownload() {
        assertEquals("https://huggingface.co/org/model/blob/abc123/model.Q4.gguf",
            modelSourceBrowserUrl("https://huggingface.co/org/model/resolve/abc123/model.Q4.gguf"))
        assertEquals("https://huggingface.co/org/model/tree/abc123",
            modelSourceBrowserUrl("https://huggingface.co/org/model/tree/abc123"))
    }
}

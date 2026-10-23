package com.sal7one.hearth.desktop

import kotlin.test.*

class DesktopReadingModelsTest {
    @Test fun launchRestoresTheExactReadingBundleRatherThanAlwaysTheCompactOne() {
        readingModelIds.forEach { id -> assertEquals(id,selectedReadingModel(mapOf("reading-model-id" to id))) }
        assertEquals("manga-page",selectedReadingModel(emptyMap()))
        assertEquals("manga-page",selectedReadingModel(mapOf("reading-model-id" to "untrusted-unknown-model")))
        assertEquals("manga-page",selectedReadingModel(mapOf("reading-model-path" to "/old/compact/path")))
    }
}

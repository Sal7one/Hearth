package com.sal7one.hearth.desktop

import com.sal7one.hearth.core.vision.MangaArtifacts
import kotlin.test.*

class DesktopReadingModelsTest {
    @Test fun launchRestoresTheExactReadingBundleRatherThanAlwaysTheCompactOne() {
        readingModelIds.forEach { id -> assertEquals(id,selectedReadingModel(mapOf("reading-model-id" to id))) }
        assertEquals(MangaArtifacts.lineProfileId,selectedReadingModel(emptyMap()))
        assertEquals(MangaArtifacts.lineProfileId,selectedReadingModel(mapOf("reading-model-id" to "untrusted-unknown-model")))
        assertEquals("manga-page",selectedReadingModel(mapOf("reading-model-path" to "/old/compact/path")))
        assertEquals(setOf("ja", "zh"), readingModelLanguages(MangaArtifacts.lineProfileId))
        assertEquals(setOf("ja"), readingModelLanguages("manga-koharu-page"))
    }

    @Test fun firstReadPersistsItsFastBundleAlongsideTheImportedPath() {
        val settings = readingModelSettings(MangaArtifacts.lineProfileId, "/fast/models")
        assertEquals(MangaArtifacts.lineProfileId, selectedReadingModel(settings))
        assertEquals("/fast/models", settings["reading-model-path"])
    }
}

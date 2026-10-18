package com.sal7one.transiber.settings

import com.sal7one.common_jni.translation.TranslationCatalog
import org.junit.Assert.*
import org.junit.Test
import android.app.DownloadManager
import com.sal7one.transiber.downloads.*

class TranslationBrowserPresentationTest {
    private fun row(id: Long, model: String, phase: String) = FileDownload(id, model, when (phase) {
        "Installed", "Complete" -> DownloadManager.STATUS_SUCCESSFUL
        "Failed" -> DownloadManager.STATUS_FAILED
        else -> DownloadManager.STATUS_RUNNING
    }, 0, 100, 100, phase = phase, modelId = model)

    @Test fun downloadedHalfOfPivotIsPartialAndStaleFailedCopiesDoNotPoisonCompleteFiles() {
        val needed = setOf("first", "second")
        val half = translationDownloadState(needed, listOf(row(-1, "first", "Installed")))
        assertNull(half.stage)
        assertEquals(1, half.completed)
        assertEquals(2, half.total)
        val copies = translationDownloadState(needed, listOf(row(-1, "first", "Failed"), row(-2, "first", "Complete"), row(-3, "first", "Complete"), row(-4, "second", "Complete")))
        assertEquals(DownloadStage.READY, copies.stage)
        assertEquals(2, copies.completed)
        assertNull(copies.failure)
        val installedFirst = translationDownloadState(needed, listOf(row(-1, "second", "Downloading")), setOf("first"))
        assertEquals(DownloadStage.TRANSFERRING, installedFirst.stage)
        assertEquals(1, installedFirst.completed)
        assertTrue(installedFirst.active)
    }

    @Test fun missingOrTruncatedMarianFilesAreNotAvailableFromSavedRegistryMetadata() {
        val root = java.nio.file.Files.createTempDirectory("translation-browser").toFile()
        try {
            val pair = com.sal7one.transiber.translation.MarianPackage.pairs.first().let { it.copy(parts = it.parts.map { part -> part.copy(bytes = 2) }) }
            assertFalse(translationFilesPresent(pair, root))
            pair.parts.forEach { java.io.File(root, it.fileName).writeBytes(byteArrayOf(1, 2)) }
            assertTrue(translationFilesPresent(pair, root))
            java.io.File(root, pair.parts.first().fileName).writeBytes(byteArrayOf(1))
            assertFalse(translationFilesPresent(pair, root))
        } finally { root.deleteRecursively() }
    }
    @Test fun routeDiskBudgetCountsEachAtomicPairOnceRatherThanOncePerFile() {
        val pair = com.sal7one.transiber.translation.MarianPackage.pairs.first()
        assertEquals(pair.downloadBytes, translationInstallationBytes(pair.parts))
        val route = com.sal7one.transiber.translation.MarianCascade.routes.first()
        assertEquals(route.downloadBytes, translationInstallationBytes(route.first.parts + route.second.parts))
        assertEquals(route.downloadBytes, translationInstallationBytes(route.first.parts + route.second.parts + route.first.parts))
    }
    @Test fun everySupportedArtifactRemainsReachableAndPrecisionsShareACard() {
        val items = translationBrowserItems()
        assertEquals(TranslationCatalog.models.map { it.id }.toSet(), items.flatMap { it.variants }.map { it.id }.toSet())
        assertEquals(1, items.count { it.variants.any { model -> model.family == "hy-mt2" } })
        assertEquals("index-translate", items.first().variants.first().family)
        assertEquals(items.size, items.map { it.id }.distinct().size)
    }
    @Test fun directionFiltersDoNotAdvertiseSameLanguageOrUnavailableCompactPairs() {
        val items = translationBrowserItems()
        assertTrue(items.filter { it.matches("", "ar", "fr", "compact") }.all { it.route != null })
        assertTrue(items.none { it.matches("", "ar", "ar", "any") })
        assertTrue(items.filter { it.matches("", "ar", "fr", "text") }.all { !it.compact })
        assertTrue(items.none { it.matches("", "ja", "ar", "compact") })
        assertTrue(items.any { it.matches("Index", "ur-Latn", "en", "text") })
        assertTrue(items.none { it.matches("doesnotexist", "any", "any", "any") })
    }
    @Test fun missingSavedSelectionDoesNotDisplaceAnInstalledPrecision() {
        val variants = TranslationCatalog.models.filter { it.family == "hy-mt2" }
        val installed = setOf("hy-mt2-q6")
        assertEquals("hy-mt2-q6", preferredTranslationVariant(variants, installed, "hy-mt2-q8").id)
        assertEquals("hy-mt2-q4", preferredTranslationVariant(variants, emptySet(), "hy-mt2-q8").id)
        assertEquals("hy-mt2-q6", preferredTranslationVariant(variants, installed, "hy-mt2-q6").id)
        assertTrue(translationBrowserItems().first { it.variants.any { model -> model.family == "hy-mt2" } }.installed(installed))
    }
}

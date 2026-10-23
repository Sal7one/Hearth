package com.sal7one.transiber.ocr

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class OcrPolicyTest {
    @Test fun automaticMangaAddsOnlyTheDetectorAndRetainsTheManualProfile() {
        val manual = OcrCatalog.profile("manga")
        val page = OcrCatalog.profile("manga-page")
        assertEquals("manga",page.family)
        assertFalse(page.live)
        assertEquals(2,manual.assets.size)
        assertEquals(3,page.assets.size)
        assertTrue(page.assets.containsAll(manual.assets))
        assertEquals(11120765L,(page.assets.toSet()-manual.assets.toSet()).single().bytes)
    }
    @Test fun arabicRestoresLogicalOrderWithoutReversingNumbersOrLatinRuns() {
        assertEquals("مرحبا بكم",OcrText.logical("مكب ابحرم",true))
        assertEquals("مرحبا 123",OcrText.logical("123 ابحرم",true))
        assertEquals("Welcome 123",OcrText.logical("Welcome 123",false))
    }
    @Test fun liveSettlesButCaptureIsImmediate() {
        val policy=OcrStability()
        assertFalse(policy.observe("first",false));assertTrue(policy.observe("first",false));assertFalse(policy.observe("first",false))
        assertFalse(policy.observe("noise",false));assertFalse(policy.observe("first",false));assertTrue(policy.observe("first",false))
        assertTrue(policy.observe("captured",true))
        assertTrue(policy.observe("captured",true))
        assertFalse(policy.observe("captured",false))
    }
    @Test fun blankFramesSettleAndNewViewCanReturnToPriorText() {
        val policy=OcrStability()
        assertTrue(policy.observe("hello",true));assertFalse(policy.observe("",false));assertTrue(policy.observe("",false))
        assertFalse(policy.observe("hello",false));assertTrue(policy.observe("hello",false))
    }
    @Test fun recognizerGroupsDeclareTheirActualLanguagesAndPinnedAssets() {
        assertTrue(setOf("ar","fa","ur").all { it in OcrCatalog.profile("arabic").languages })
        assertTrue("ru" in OcrCatalog.profile("cyrillic").languages)
        assertFalse("ar" in OcrCatalog.profile("cjk").languages)
        assertEquals(OcrCatalog.assets.size,OcrCatalog.assets.map{it.id}.distinct().size)
        // RF-DETR (148 MB) and LaMa (207 MB) are explicit quality artifacts; all
        // acquisition remains bounded by the exact selected catalog byte count.
        OcrCatalog.assets.forEach {assertTrue(it.revision.matches(Regex("[a-f0-9]{40}")));assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")));assertTrue(it.url.contains(it.revision));assertTrue(it.bytes in 1..256L*1024*1024)}
    }
    @Test fun koharuQualityAndCleanupAreSeparatePinnedProfilesWithHonestWeightTerms() {
        val compact=OcrCatalog.profile("manga-page")
        val quality=OcrCatalog.profile("manga-koharu-page")
        val cleanup=OcrCatalog.profile("manga-koharu-clean-page")
        assertEquals(setOf(OcrCatalog.koharuDetector),quality.assets.toSet()-compact.assets.toSet())
        assertEquals(setOf(OcrCatalog.mangaInpainter),cleanup.assets.toSet()-quality.assets.toSet())
        assertEquals(148442003L,OcrCatalog.koharuDetector.bytes)
        assertEquals(207482644L,OcrCatalog.mangaInpainter.bytes)
        assertTrue(OcrCatalog.koharuDetector.license.contains("Other"))
        assertTrue(OcrCatalog.koharuDetector.license.contains("academic"))
        assertEquals("Apache-2.0",OcrCatalog.mangaInpainter.license)
        for(profile in listOf(quality,cleanup)) {
            assertEquals("manga",profile.family);assertEquals(setOf("ja"),profile.languages);assertFalse(profile.live)
            assertTrue(profile.description.contains("Manga109"));assertTrue(profile.description.contains("CPU"))
            assertTrue(profile.assets.contains(OcrCatalog.comicDetector)) // fast outlines remain part of the complete bundle.
        }
    }
    @Test fun invalidImportCannotPublishOrOverwriteExistingModel() {
        val root=Files.createTempDirectory("ocr-import-test").toFile()
        try {
            val models=OcrModels(root);val original=models.file(OcrCatalog.detector);original.writeText("existing fixture")
            val result=runCatching {models.import(ByteArrayInputStream("invalid model".toByteArray()))}
            assertTrue(result.isFailure);assertEquals("existing fixture",original.readText());assertFalse(root.listFiles()!!.any{it.extension=="part"})
            assertFalse(models.ready(OcrCatalog.profile("latin")))
        }finally{root.deleteRecursively()}
    }
    @Test fun cancelledImportRemovesStaging() {
        val root=Files.createTempDirectory("ocr-cancel-test").toFile()
        try {assertTrue(runCatching{OcrModels(root).import(ByteArrayInputStream(byteArrayOf(1))){throw java.io.IOException("cancel fixture")}}.isFailure);assertTrue(root.listFiles()!!.isEmpty())}finally{root.deleteRecursively()}
    }
}

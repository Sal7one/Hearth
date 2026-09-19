package com.sal7one.transiber.ocr

import org.junit.Assert.*
import org.junit.Test

class ReadingSelectionTest {
    @Test fun widePageLetterboxMapsToActualPixels() {
        assertEquals(PixelCrop(100,0,300,200),ReadingSelection.crop(400,200,400f,600f,100f,200f,300f,400f))
        assertNull(ReadingSelection.crop(400,200,400f,600f,0f,0f,100f,100f))
    }
    @Test fun reverseDragAndOutsideBoundsAreClipped() {
        assertEquals(PixelCrop(0,0,400,200),ReadingSelection.crop(400,200,400f,600f,900f,900f,-100f,-100f))
        assertEquals(PixelCrop(0,0,200,400),ReadingSelection.crop(200,400,600f,400f,200f,0f,400f,400f))
    }
    @Test fun invalidAndAccidentalTinyRegionsDoNotStartInference() {
        assertNull(ReadingSelection.crop(100,100,100f,100f,1f,1f,3f,3f))
        assertNull(ReadingSelection.crop(100,100,0f,100f,0f,0f,100f,100f))
        assertNull(ReadingSelection.crop(100,100,100f,100f,Float.NaN,0f,100f,100f))
    }
    @Test fun specialistPackagesRequireEveryAsset() {
        assertEquals(2,OcrCatalog.profile("manga").assets.size)
        assertEquals(3,OcrCatalog.profile("meiki").assets.size)
        assertFalse(OcrCatalog.profile("manga").live)
        assertTrue(OcrCatalog.profile("cjk").live)
        val root=kotlin.io.path.createTempDirectory().toFile()
        try {
            val models=OcrModels(root);val profile=OcrCatalog.profile("meiki")
            java.io.RandomAccessFile(models.file(profile.asset),"rw").use {it.setLength(profile.asset.bytes)}
            assertFalse(models.ready(profile));assertEquals(2,models.missing(profile).size)
        } finally {root.deleteRecursively()}
    }
}

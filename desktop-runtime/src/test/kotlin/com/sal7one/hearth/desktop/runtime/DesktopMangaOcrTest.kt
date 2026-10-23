package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.ocr.ComicNative
import com.sal7one.common_jni.ocr.JapaneseNative
import com.sal7one.hearth.core.vision.*
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class DesktopMangaOcrTest {
    @Test fun nativeContractsRejectInvalidModelsPixelsAndRetiredHandles() {
        assumeTrue("Stage the host OCR helper first", DesktopMangaOcr.runtimeIssue() == null)
        val folder = Files.createTempDirectory("hearth-ocr-contract")
        try {
            val bad = folder.resolve("غير صالح🙂.onnx")
            Files.write(bad,byteArrayOf(0))
            assertFailsWith<IllegalStateException> { ComicNative.create(bad.toString().toByteArray()) }
            assertFailsWith<IllegalStateException> { ComicNative.createKoharu(bad.toString().toByteArray()) }
            assertFailsWith<IllegalStateException> { ComicNative.createInpainter(bad.toString().toByteArray()) }
            assertFailsWith<IllegalStateException> { JapaneseNative.create(1,bad.toString().toByteArray(),bad.toString().toByteArray(),byteArrayOf()) }
            assertFailsWith<IllegalStateException> { ComicNative.detect(0,intArrayOf(0),1,1) }
            assertFailsWith<IllegalStateException> { ComicNative.inpaint(0,intArrayOf(0),1,1,intArrayOf(0,0,1)) }
            ComicNative.cancel(0); ComicNative.destroy(0)
        } finally { folder.toFile().deleteRecursively() }
    }

    /** Deliberately opt-in: real pinned weights and an owner-supplied page stay outside Git. */
    @Test fun realPinnedMangaPagePublishesGeometryBeforeRecognition() {
        val modelPath = System.getenv("HEARTH_MANGA_MODELS")
        val pagePath = System.getenv("HEARTH_MANGA_PAGE")
        assumeTrue("Set HEARTH_MANGA_MODELS and HEARTH_MANGA_PAGE for real inference",modelPath != null && pagePath != null)
        val image = ImageIO.read(Path.of(requireNotNull(pagePath)).toFile())
        val pixels = image.getRGB(0,0,image.width,image.height,null,0,image.width)
        var detectedAt = 0L
        var firstDetectedAt = 0L
        var regions = emptyList<OcrRegion>()
        val start = System.nanoTime()
        val folder = Files.createTempDirectory("hearth-manga-native")
        try {
            DesktopNativeLibraries.configure(folder)
            DesktopMangaOcr(Path.of(requireNotNull(modelPath))).use { reader ->
                val loaded = System.nanoTime()
                val result = reader.recognize(pixels,image.width,image.height,object:PageOcrProgress {
                    override fun detected(found: List<OcrRegion>) {
                        regions = found; detectedAt = System.nanoTime()
                        if(firstDetectedAt==0L)firstDetectedAt=detectedAt
                        assertTrue(found.isNotEmpty())
                    }
                    override fun recognized(text: List<PageText>) {
                        assertTrue(detectedAt > 0)
                        assertTrue(text.all {item -> regions.any {it.bounds==item.region.bounds}})
                    }
                })
                assertTrue(result.size >= 2,"Use a real multi-bubble Japanese page")
                assertTrue(result.any {it.text.any {c -> c.code in 0x3040..0x9fff}})
                println("MANGA_REAL load_ms=${(loaded-start)/1_000_000} first_outline_ms=${(firstDetectedAt-loaded)/1_000_000} detection_ms=${(detectedAt-loaded)/1_000_000} total_ms=${(System.nanoTime()-loaded)/1_000_000} regions=${regions.size} recognized=${result.size} mask_regions=${result.count {it.region.textMask!=null}} flat_cleanup=${result.count {it.region.paint!=null && it.region.paint?.patch==null}} neural_cleanup=${result.count {it.region.paint?.patch!=null}}")
                result.forEach {println("MANGA_TEXT ${it.region.bounds}: ${it.text}")}
                assertTrue(reader.recognize(IntArray(640*640) {-1},640,640,object:PageOcrProgress{}).isEmpty())
                reader.cancel()
                assertFailsWith<IllegalStateException> { reader.recognize(pixels,image.width,image.height,object:PageOcrProgress{}) }
            }
        } finally { folder.toFile().deleteRecursively() }
    }
}

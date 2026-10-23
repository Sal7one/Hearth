package com.sal7one.common_jni.ocr

import com.sal7one.hearth.core.vision.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Lightweight comic layout detection with the existing resident Manga OCR reader. CPU ONNX Runtime. */
class MangaPageEngine(detector: File, reader: OcrEngine) : OcrEngine {
    private val page = MangaPageOcr(ComicTextDetector(detector), object : MangaRegionReader {
        override fun read(pixels: IntArray, width: Int, height: Int) = reader.recognize(pixels,width,height).joinToString("\n") { it.text }
        override fun cancel() = reader.cancel()
        override fun close() = reader.close()
    })
    override val preservesRegions = true
    override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int) =
        recognizePage(pixels,width,height,maxSide,object : PageOcrProgress {})
    override fun recognizePage(pixels: IntArray, width: Int, height: Int, maxSide: Int, progress: PageOcrProgress) =
        page.recognize(pixels,width,height,progress).map { it.toOcrLine() }
    override fun cancel() = page.cancel()
    override fun close() = page.close()
}

/** Detector confidence is not an OCR transcription-quality score. */
fun PageText.toOcrLine() = region.bounds.let { OcrLine(it.x,it.y,it.width,it.height,text,region.confidence) }

class ComicTextDetector(model: File) : ComicRegionDetector {
    private val handle = AtomicLong(ComicNative.create(model.absolutePath.toByteArray(Charsets.UTF_8)))
    override fun detect(pixels: IntArray, width: Int, height: Int): List<ComicDetection> {
        val id = handle.get(); check(id != 0L) { "Comic detector is closed" }
        require(width in 1..2048 && height in 1..2048 && pixels.size == width * height)
        return ComicNative.detect(id,pixels,width,height).map {
            check(it.size == 6) { "Invalid comic detector result" }
            ComicDetection(OcrRect(it[0],it[1],it[2],it[3]),it[5],it[4]/10000f)
        }
    }
    override fun cancel() { handle.get().takeIf { it != 0L }?.let(ComicNative::cancel) }
    override fun close() { handle.getAndSet(0L).takeIf { it != 0L }?.let(ComicNative::destroy) }
}
internal object ComicNative {
    init { System.loadLibrary("common_jni") }
    external fun create(model: ByteArray): Long
    external fun detect(handle: Long, pixels: IntArray, width: Int, height: Int): Array<IntArray>
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

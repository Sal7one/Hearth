package com.sal7one.common_jni.ocr

import com.sal7one.hearth.core.vision.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Lightweight comic layout detection with the existing resident Manga OCR reader. CPU ONNX Runtime. */
class MangaPageEngine(detector: File, reader: OcrEngine, koharu: Boolean = false, preview: File? = null, cleanup: File? = null) : OcrEngine {
    private val page: MangaPageOcr
    init {
        val full=ComicTextDetector(detector,koharu)
        var quick: ComicTextDetector?=null
        var inpainter: NativeMangaInpainter?=null
        try {
            quick=preview?.let {ComicTextDetector(it)}
            inpainter=cleanup?.let {NativeMangaInpainter(it)}
            page = MangaPageOcr(full, object : MangaRegionReader {
        override fun read(pixels: IntArray, width: Int, height: Int) = reader.recognize(pixels,width,height).joinToString("\n") { it.text }
        override fun cancel() = reader.cancel()
        override fun close() = reader.close()
            },quick,if(koharu)180_000 else 45_000,inpainter)
        } catch(error: Throwable) {try{full.close()}finally{try{quick?.close()}finally{inpainter?.close()}};throw error}
    }
    override val preservesRegions = true
    override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int) =
        recognizePage(pixels,width,height,maxSide,object : PageOcrProgress {})
    override fun recognizePage(pixels: IntArray, width: Int, height: Int, maxSide: Int, progress: PageOcrProgress) =
        page.recognize(pixels,width,height,progress).map { it.toOcrLine() }
    override fun cancel() = page.cancel()
    override fun close() = page.close()
}
private class NativeMangaInpainter(model: File): MangaPageInpainter {
    private val handle=AtomicLong(ComicNative.createInpainter(model.absolutePath.toByteArray(Charsets.UTF_8)))
    override fun inpaint(pixels: IntArray,width: Int,height: Int,mask: ComicMask): MangaPatch {
        val id=handle.get();check(id!=0L){"Manga inpainter is closed"}
        return MangaPatch.fromNative(ComicNative.inpaint(id,pixels,width,height,mask.nativeSpans()))
    }
    override fun cancel(){handle.get().takeIf {it!=0L}?.let(ComicNative::cancel)}
    override fun close(){handle.getAndSet(0).takeIf {it!=0L}?.let(ComicNative::destroy)}
}

/** Detector confidence is not an OCR transcription-quality score. */
fun PageText.toOcrLine() = region.bounds.let { OcrLine(it.x,it.y,it.width,it.height,text,region.confidence,region) }

class ComicTextDetector(model: File,koharu: Boolean = false) : ComicRegionDetector {
    private val handle = AtomicLong(model.absolutePath.toByteArray(Charsets.UTF_8).let { if(koharu)ComicNative.createKoharu(it) else ComicNative.create(it) })
    override fun detect(pixels: IntArray, width: Int, height: Int): List<ComicDetection> {
        val id = handle.get(); check(id != 0L) { "Comic detector is closed" }
        require(width in 1..2048 && height in 1..2048 && pixels.size == width * height)
        return ComicNative.detect(id,pixels,width,height).map { ComicDetection.fromNative(it,width,height) }
    }
    override fun cancel() { handle.get().takeIf { it != 0L }?.let(ComicNative::cancel) }
    override fun close() { handle.getAndSet(0L).takeIf { it != 0L }?.let(ComicNative::destroy) }
}
internal object ComicNative {
    init { System.loadLibrary("common_jni") }
    external fun create(model: ByteArray): Long
    external fun createKoharu(model: ByteArray): Long
    external fun createInpainter(model: ByteArray): Long
    external fun inpaint(handle: Long,pixels: IntArray,width: Int,height: Int,mask: IntArray): IntArray
    external fun detect(handle: Long, pixels: IntArray, width: Int, height: Int): Array<IntArray>
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

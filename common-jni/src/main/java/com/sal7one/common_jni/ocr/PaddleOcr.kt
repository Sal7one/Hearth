package com.sal7one.common_jni.ocr

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Capture-independent PaddleOCR: caller supplies upright ARGB pixels and a pinned dictionary. */
data class OcrLine(val x: Int, val y: Int, val width: Int, val height: Int, val text: String, val confidence: Float)
class PaddleOcr(detector: File, recognizer: File, private val dictionary: List<String>) : OcrEngine {
    private val handle = AtomicLong(PaddleNative.create(detector.absolutePath.toByteArray(), recognizer.absolutePath.toByteArray(), dictionary.size))
    @Synchronized override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int): List<OcrLine> {
        val id = handle.get(); check(id != 0L) { "OCR model is closed" }
        require(width in 1..2048 && height in 1..2048 && pixels.size == width * height)
        return PaddleNative.recognize(id, pixels, width, height, maxSide).map { row ->
            check(row.size >= 5 && row.drop(5).all { it in dictionary.indices }) { "OCR returned invalid dictionary indices" }
            OcrLine(row[0],row[1],row[2],row[3],row.drop(5).joinToString("") { dictionary[it] }.trim(),row[4]/10000f)
        }.filter { it.text.isNotBlank() }
    }
    override fun cancel() { handle.get().takeIf { it != 0L }?.let(PaddleNative::cancel) }
    override fun close() { handle.getAndSet(0).takeIf { it != 0L }?.let(PaddleNative::destroy) }
}
internal object PaddleNative {
    init { System.loadLibrary("common_jni") }
    external fun create(detector: ByteArray, recognizer: ByteArray, classes: Int): Long
    external fun recognize(handle: Long, pixels: IntArray, width: Int, height: Int, maxSide: Int): Array<IntArray>
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

package com.sal7one.common_jni.ocr

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Manga: one selected bubble. Meiki: detected horizontal/vertical Japanese lines. */
class JapaneseOcr(kind: Int, first: File, second: File, third: File? = null, private val vocabulary: List<String>? = null) : OcrEngine {
    private val handle: AtomicLong
    init {
        require(kind in 1..2)
        require(kind != 1 || vocabulary?.size == 6144) { "Manga OCR requires its matching 6,144-token vocabulary" }
        require(kind != 2 || third != null) { "Meiki requires detector, horizontal and vertical readers" }
        handle = AtomicLong(JapaneseNative.create(kind, first.absolutePath.toByteArray(), second.absolutePath.toByteArray(), third?.absolutePath?.toByteArray() ?: byteArrayOf()))
    }
    override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int): List<OcrLine> {
        val id = handle.get(); check(id != 0L) { "OCR model is closed" }
        require(width in 1..2048 && height in 1..2048 && pixels.size == width * height)
        return JapaneseNative.recognize(id, pixels, width, height).map { row ->
            require(row.size >= 5) { "Invalid OCR line" }
            OcrLine(row[0], row[1], row[2], row[3], OcrTokens.decode(row.drop(5), vocabulary), row[4] / 10000f)
        }.filter { it.text.isNotBlank() }
    }
    override fun cancel() { handle.get().takeIf { it != 0L }?.let(JapaneseNative::cancel) }
    override fun close() { handle.getAndSet(0).takeIf { it != 0L }?.let(JapaneseNative::destroy) }
}

/** Validates native vocabulary IDs/code points before constructing display text. */
object OcrTokens {
    fun decode(tokens: List<Int>, vocabulary: List<String>?): String = buildString {
        for (id in tokens) {
            if (vocabulary != null) {
                require(id in vocabulary.indices) { "OCR token is outside the model vocabulary" }
                if (id !in 0..3) append(vocabulary[id].removePrefix("##"))
            } else {
                require(Character.isValidCodePoint(id) && id !in 0xD800..0xDFFF && id >= 32) { "Meiki returned an invalid character code" }
                append(String(Character.toChars(id)))
            }
        }
    }.trim()
}
internal object JapaneseNative {
    init { System.loadLibrary("common_jni") }
    external fun create(kind: Int, first: ByteArray, second: ByteArray, third: ByteArray): Long
    external fun recognize(handle: Long, pixels: IntArray, width: Int, height: Int): Array<IntArray>
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

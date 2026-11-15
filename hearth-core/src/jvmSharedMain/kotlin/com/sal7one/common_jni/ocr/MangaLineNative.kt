package com.sal7one.common_jni.ocr

/** The platform loads its OCR library before opening the shared JVM reader. */
internal object MangaLineNative {
    external fun create(detector: ByteArray, recognizer: ByteArray, workers: Int): Long
    external fun detect(handle: Long, pixels: IntArray, width: Int, height: Int, maxSide: Int): Array<IntArray>
    external fun recognize(handle: Long, pixels: IntArray, width: Int, height: Int, boxes: IntArray): Array<IntArray>
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

package com.sal7one.common_jni.ocr

/** Upright ARGB input shared by camera, imported pages and region selection. */
interface OcrEngine : AutoCloseable {
    fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int = 640): List<OcrLine>
    fun cancel()
}

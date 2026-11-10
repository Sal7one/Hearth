package com.sal7one.common_jni.ocr

import com.sal7one.hearth.core.vision.MangaLinePageReader
import com.sal7one.hearth.core.vision.PageOcrProgress
import java.io.File

class MangaLineOcr(detector: File, recognizer: File, dictionary: File,
    workers: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) : OcrEngine {
    init { System.loadLibrary("common_jni") }
    private val reader = MangaLinePageReader(detector.toPath(), recognizer.toPath(), dictionary.toPath(), workers)

    override val preservesRegions = true
    override fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int) =
        recognizePage(pixels, width, height, maxSide, object : PageOcrProgress {})

    override fun recognizePage(pixels: IntArray, width: Int, height: Int, maxSide: Int, progress: PageOcrProgress) =
        reader.recognize(pixels, width, height, progress).map { it.toOcrLine() }

    override fun cancel() = reader.cancel()
    override fun close() = reader.close()
}

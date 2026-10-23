package com.sal7one.common_jni.ocr

import com.sal7one.hearth.core.vision.OcrRect
import com.sal7one.hearth.core.vision.OcrRegion
import com.sal7one.hearth.core.vision.PageOcrProgress
import com.sal7one.hearth.core.vision.PageText

/** Upright ARGB input shared by camera, imported pages and region selection. */
interface OcrEngine : AutoCloseable {
    /** Whole bubble/block geometry must survive translation without another adjacency merge. */
    val preservesRegions: Boolean get() = false
    fun recognize(pixels: IntArray, width: Int, height: Int, maxSide: Int = 640): List<OcrLine>
    fun recognizePage(pixels: IntArray, width: Int, height: Int, maxSide: Int, progress: PageOcrProgress): List<OcrLine> {
        if (!progress.isCurrent()) return emptyList()
        return recognize(pixels, width, height, maxSide).also { lines ->
            if (progress.isCurrent()) {
                val page = lines.map { PageText(OcrRegion(OcrRect(it.x,it.y,it.width,it.height),it.confidence),it.text) }
                progress.detected(page.map { it.region }); progress.recognized(page)
            }
        }
    }
    fun cancel()
}

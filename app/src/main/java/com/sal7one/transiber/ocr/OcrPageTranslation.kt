package com.sal7one.transiber.ocr

import com.sal7one.common_jni.ocr.OcrLine

internal data class TranslatedOcrBox(val line: OcrLine, val translation: String)

/** Keeps each response attached to its source box; never guesses alignment from generated newlines. */
internal object OcrPageTranslation {
    suspend fun run(
        lines: List<OcrLine>, current: () -> Boolean,
        translate: suspend (String) -> String, publish: (List<TranslatedOcrBox>) -> Unit,
    ) {
        check(lines.size <= 64 && lines.sumOf { it.text.length.toLong() } <= 3000) {
            "Page exceeds 64 text boxes or 3,000 characters. Draw a smaller area; original text is preserved."
        }
        val results = mutableListOf<TranslatedOcrBox>()
        for (line in lines) {
            if (!current()) return
            val translated = translate(line.text)
            if (!current()) return
            check(translated.isNotBlank()) { "Translation returned empty text for: ${line.text}" }
            results += TranslatedOcrBox(line, translated)
            publish(results.toList())
        }
    }
}

/** Maps crop-local OCR geometry onto a fitted full-page image, including its letterbox offsets. */
internal object OcrPageLayout {
    fun box(line: OcrLine, crop: PixelCrop, imageWidth: Int, imageHeight: Int,
            viewWidth: Int, viewHeight: Int, coverEdges: Boolean = false): PixelCrop? {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0 ||
            crop.left < 0 || crop.top < 0 || crop.right > imageWidth || crop.bottom > imageHeight ||
            crop.width <= 0 || crop.height <= 0 || line.width <= 0 || line.height <= 0) return null
        // Detectors can trim antialiased glyph edges and trailing CJK punctuation.
        // Clip the small cover margin to the user's crop, not the entire screen.
        val vertical=line.height>line.width
        val glyph=minOf(line.width,line.height)
        val px = if(coverEdges) kotlin.math.ceil(glyph * if(vertical).18 else .5).toLong() else 0L
        val py = if(coverEdges) kotlin.math.ceil(glyph * if(vertical).5 else .18).toLong() else 0L
        val left = (line.x.toLong()-px).coerceIn(0, crop.width.toLong()) + crop.left
        val top = (line.y.toLong()-py).coerceIn(0, crop.height.toLong()) + crop.top
        val right = (line.x.toLong() + line.width + px).coerceIn(0, crop.width.toLong()) + crop.left
        val bottom = (line.y.toLong() + line.height + py).coerceIn(0, crop.height.toLong()) + crop.top
        if (right <= left || bottom <= top) return null
        val scale = minOf(viewWidth.toDouble() / imageWidth, viewHeight.toDouble() / imageHeight)
        val dx = (viewWidth - imageWidth * scale) / 2
        val dy = (viewHeight - imageHeight * scale) / 2
        return PixelCrop(kotlin.math.floor(dx + left * scale).toInt(), kotlin.math.floor(dy + top * scale).toInt(),
            kotlin.math.ceil(dx + right * scale).toInt(), kotlin.math.ceil(dy + bottom * scale).toInt())
    }
}

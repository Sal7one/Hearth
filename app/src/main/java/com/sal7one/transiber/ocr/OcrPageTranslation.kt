package com.sal7one.transiber.ocr

import com.sal7one.common_jni.ocr.OcrLine

internal data class TranslatedOcrBox(val line: OcrLine, val translation: String)

/** Join adjacent lines from one reading block before invoking a text translator.
 * Keep distant balloons separate and retain their union geometry for the overlay.
 */
internal object OcrReadingGroups {
    fun group(lines: List<OcrLine>): List<OcrLine> {
        if (lines.size < 2) return lines
        val groups = ArrayList<OcrLine>(lines.size)
        var first = lines.first()
        var last = first
        var count = 1
        for (next in lines.drop(1)) {
            if (count < 4 && first.text.length + next.text.length + 1 <= 400 && adjacent(last, next)) {
                val left = minOf(first.x.toLong(), next.x.toLong())
                val top = minOf(first.y.toLong(), next.y.toLong())
                val right = maxOf(first.x.toLong() + first.width, next.x.toLong() + next.width)
                val bottom = maxOf(first.y.toLong() + first.height, next.y.toLong() + next.height)
                if (right - left <= Int.MAX_VALUE && bottom - top <= Int.MAX_VALUE) {
                    first = first.copy(x = left.toInt(), y = top.toInt(), width = (right - left).toInt(),
                        height = (bottom - top).toInt(), text = first.text + "\n" + next.text,
                        confidence = minOf(first.confidence, next.confidence))
                    last = next
                    count++
                } else {
                    groups += first
                    first = next
                    last = next
                    count = 1
                }
            } else {
                groups += first
                first = next
                last = next
                count = 1
            }
        }
        groups += first
        return groups
    }

    private fun adjacent(a: OcrLine, b: OcrLine): Boolean {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return false
        val aVertical = a.height.toLong() * 2 > a.width.toLong() * 3
        val bVertical = b.height.toLong() * 2 > b.width.toLong() * 3
        val vertical = aVertical && bVertical
        if (vertical) {
            // Japanese columns are read right to left. Do not join columns from
            // different panels whose vertical extents barely intersect.
            val overlap = minOf(a.y.toLong() + a.height, b.y.toLong() + b.height) - maxOf(a.y, b.y)
            val gap = a.x.toLong() - (b.x.toLong() + b.width)
            return b.x < a.x && overlap * 2 >= minOf(a.height, b.height) &&
                gap >= -minOf(a.width, b.width) / 4 && gap <= maxOf(a.width, b.width)
        }
        if (aVertical || bVertical) return false
        val overlap = minOf(a.x.toLong() + a.width, b.x.toLong() + b.width) - maxOf(a.x, b.x)
        val gap = b.y.toLong() - (a.y.toLong() + a.height)
        return b.y > a.y && overlap * 2 >= minOf(a.width, b.width) &&
            gap >= -minOf(a.height, b.height) / 4 && gap <= maxOf(a.height, b.height)
    }
}

/** Keeps each response attached to its source box; never guesses alignment from generated newlines. */
internal object OcrPageTranslation {
    suspend fun run(
        lines: List<OcrLine>, current: () -> Boolean,
        translate: suspend (String) -> String, publish: (List<TranslatedOcrBox>) -> Unit,
        cached: (String) -> String? = { null },
    ) {
        check(lines.size <= 64 && lines.sumOf { it.text.length.toLong() } <= 3000) {
            "Page exceeds 64 text boxes or 3,000 characters. Draw a smaller area; original text is preserved."
        }
        if (!current()) return
        // Publish every known box before starting any slow new inference. Geometry is
        // always from this capture, even when its words came from an earlier page.
        val results = lines.map { line -> cached(line.text)?.takeIf { it.isNotBlank() }?.let { TranslatedOcrBox(line, it) } }.toMutableList()
        if (results.any { it != null } && current()) publish(results.filterNotNull())
        for ((index, line) in lines.withIndex()) {
            if (!current()) return
            if (results[index] != null) continue
            val translated = cached(line.text)?.takeIf { it.isNotBlank() } ?: translate(line.text)
            if (!current()) return
            check(translated.isNotBlank()) { "Translation returned empty text for: ${line.text}" }
            results[index] = TranslatedOcrBox(line, translated)
            publish(results.filterNotNull())
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

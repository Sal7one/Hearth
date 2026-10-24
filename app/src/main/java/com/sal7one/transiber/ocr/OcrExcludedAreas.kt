package com.sal7one.transiber.ocr

/** Ignore text mostly inside an explicit user exclusion, without modifying screenshot pixels. */
internal object OcrExcludedAreas {
    fun containsText(x: Int, y: Int, width: Int, height: Int, excluded: List<PixelCrop>): Boolean {
        if (width <= 0 || height <= 0) return false
        val right = x.toLong() + width
        val bottom = y.toLong() + height
        val area = width.toLong() * height
        return excluded.any { crop ->
            val overlapWidth = (minOf(right, crop.right.toLong()) - maxOf(x.toLong(), crop.left.toLong())).coerceAtLeast(0)
            val overlapHeight = (minOf(bottom, crop.bottom.toLong()) - maxOf(y.toLong(), crop.top.toLong())).coerceAtLeast(0)
            overlapWidth * overlapHeight * 2 >= area
        }
    }

    fun localize(excluded: List<PixelCrop>, capture: PixelCrop): List<PixelCrop> = excluded.mapNotNull { crop ->
        val left = maxOf(crop.left, capture.left)
        val top = maxOf(crop.top, capture.top)
        val right = minOf(crop.right, capture.right)
        val bottom = minOf(crop.bottom, capture.bottom)
        if (right <= left || bottom <= top) null
        else PixelCrop(left - capture.left, top - capture.top, right - capture.left, bottom - capture.top)
    }
}

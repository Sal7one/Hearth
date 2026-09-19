package com.sal7one.transiber.ocr

import kotlin.math.ceil
import kotlin.math.floor

internal data class PixelCrop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Maps a drawn rectangle through ContentScale.Fit; letterboxing is never OCR input. */
internal object ReadingSelection {
    fun crop(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float,
             left: Float, top: Float, right: Float, bottom: Float): PixelCrop? {
        if (imageWidth < 1 || imageHeight < 1 || listOf(viewWidth,viewHeight,left,top,right,bottom).any { !it.isFinite() } || viewWidth <= 0 || viewHeight <= 0) return null
        val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
        val dx = (viewWidth - imageWidth * scale) / 2
        val dy = (viewHeight - imageHeight * scale) / 2
        val x1 = floor((minOf(left,right)-dx)/scale).toInt().coerceIn(0,imageWidth)
        val y1 = floor((minOf(top,bottom)-dy)/scale).toInt().coerceIn(0,imageHeight)
        val x2 = ceil((maxOf(left,right)-dx)/scale).toInt().coerceIn(0,imageWidth)
        val y2 = ceil((maxOf(top,bottom)-dy)/scale).toInt().coerceIn(0,imageHeight)
        return PixelCrop(x1,y1,x2,y2).takeIf { it.width >= 8 && it.height >= 8 }
    }
}

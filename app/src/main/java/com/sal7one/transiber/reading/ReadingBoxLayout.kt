package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
import kotlin.math.abs
import kotlin.math.roundToInt

/** Drawing preferences never change OCR geometry, region identities, or translation cache keys. */
internal data class ReadingBoxStyle(
    val replaceText: Boolean,
    val widthPercent: Int,
    val heightPercent: Int,
    val background: Int,
    val foreground: Int,
    val opacity: Int,
    val textSizeSp: Float,
) {
    companion object {
        fun from(settings: ReadingOverlaySettings) = settings.bounded().let {
            ReadingBoxStyle(it.replaceText,it.boxWidthPercent,it.boxHeightPercent,it.boxBackgroundArgb,
                it.boxTextArgb,it.boxOpacity,it.textSizeSp)
        }
    }
}

/** Keep requested boxes onscreen, preferring nearby free space over covering another label. */
internal object ReadingBoxLayout {
    fun place(source: PixelCrop, requestedWidth: Int, requestedHeight: Int, width: Int, height: Int,
              occupied: List<PixelCrop>): PixelCrop? {
        if(width<=0 || height<=0 || source.width<=0 || source.height<=0)return null
        val w=requestedWidth.coerceIn(1,width);val h=requestedHeight.coerceIn(1,height)
        val anchorX=((source.left.toLong()+source.right-w)/2).toInt().coerceIn(0,width-w)
        val anchorY=source.top.coerceIn(0,height-h)
        fun at(x: Int,y: Int)=PixelCrop(x.coerceIn(0,width-w),y.coerceIn(0,height-h),
            x.coerceIn(0,width-w)+w,y.coerceIn(0,height-h)+h)
        val candidates=linkedSetOf(at(anchorX,anchorY))
        occupied.forEach {other ->
            candidates+=at(anchorX,other.bottom+1)
            candidates+=at(anchorX,other.top-h-1)
            candidates+=at(other.right+1,anchorY)
            candidates+=at(other.left-w-1,anchorY)
        }
        fun overlap(box: PixelCrop)=occupied.sumOf {other ->
            maxOf(0,minOf(box.right,other.right)-maxOf(box.left,other.left)).toLong()*
                maxOf(0,minOf(box.bottom,other.bottom)-maxOf(box.top,other.top))
        }
        return candidates.minWithOrNull(compareBy<PixelCrop> {overlap(it)}
            .thenBy {abs(it.left.toLong()-anchorX)+abs(it.top.toLong()-anchorY)})
    }

    fun scaled(value: Int,percent: Int)=((value.toLong()*percent)/100.0).roundToInt().coerceAtLeast(1)
}

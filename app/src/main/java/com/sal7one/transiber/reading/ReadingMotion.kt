package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
import java.nio.ByteBuffer
import kotlin.math.abs

/** Samples visible reader content while excluding Hearth controls and replacement pixels. */
internal class ReadingMotion {
    private var previous: IntArray?=null
    private var previousMask=BooleanArray(WIDTH*HEIGHT)
    fun reset() { previous=null }
    fun observe(argb: IntArray,imageWidth: Int,imageHeight: Int,excluded: List<PixelCrop>): Boolean {
        require(argb.size==WIDTH*HEIGHT && imageWidth>0 && imageHeight>0)
        val mask=BooleanArray(argb.size) {i->
            val x=(i%WIDTH+.5)*imageWidth/WIDTH;val y=(i/WIDTH+.5)*imageHeight/HEIGHT
            excluded.any {x>=it.left && x<it.right && y>=it.top && y<it.bottom}
        }
        val grey=IntArray(argb.size) {i->val p=argb[i];(((p shr 16)and 255)*3+((p shr 8)and 255)*6+(p and 255))/10}
        val old=previous
        var changed=0;var visible=0
        if(old!=null)for(i in grey.indices)if(!mask[i]&&!previousMask[i]) {
            visible++;if(abs(grey[i]-old[i])>=8)changed++
        }
        previous=grey;previousMask=mask
        return visible>=argb.size/8 && changed>=maxOf(8,visible/200)
    }
    companion object { const val WIDTH=64;const val HEIGHT=128 }
}

/** Read only the pixels needed for motion detection from a padded RGBA capture plane. */
internal object ReadingMotionSampler {
    fun sample(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): IntArray {
        require(width > 0 && height > 0 && pixelStride == 4 && rowStride >= width * pixelStride)
        require((height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride + 3 < buffer.limit())
        return IntArray(ReadingMotion.WIDTH * ReadingMotion.HEIGHT) { index ->
            val x = ((index % ReadingMotion.WIDTH + .5) * width / ReadingMotion.WIDTH).toInt().coerceAtMost(width - 1)
            val y = ((index / ReadingMotion.WIDTH + .5) * height / ReadingMotion.HEIGHT).toInt().coerceAtMost(height - 1)
            val offset = y * rowStride + x * pixelStride
            val red = buffer.get(offset).toInt() and 255
            val green = buffer.get(offset + 1).toInt() and 255
            val blue = buffer.get(offset + 2).toInt() and 255
            -0x1000000 or (red shl 16) or (green shl 8) or blue
        }
    }
}

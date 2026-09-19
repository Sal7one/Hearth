package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
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

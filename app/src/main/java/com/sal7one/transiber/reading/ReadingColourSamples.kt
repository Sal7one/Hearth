package com.sal7one.transiber.reading

import android.graphics.Bitmap
import android.graphics.Color
import com.sal7one.transiber.ocr.PixelCrop
import kotlin.math.ceil
import kotlin.math.sqrt

internal data class ReadingSampledColours(val background: Int,val foreground: Int)

/** One bounded clean-capture colour grid, never the OCR bitmap or a history of screenshots. */
internal class ReadingColourSamples private constructor(
    private val pixels: IntArray, private val sampleWidth: Int, private val sampleHeight: Int,
    val sourceWidth: Int,val sourceHeight: Int,
) {
    val retainedPixelCount get()=pixels.size
    private val colours=LinkedHashMap<PixelCrop,ReadingSampledColours?>(16,.75f,true)

    fun sample(area: PixelCrop): ReadingSampledColours? {
        if(colours.containsKey(area))return colours[area]
        return sampleUncached(area).also {result ->
            colours[area]=result
            while(colours.size>128)colours.remove(colours.entries.iterator().next().key)
        }
    }
    private fun sampleUncached(area: PixelCrop): ReadingSampledColours? {
        val left=(area.left.toLong()*sampleWidth/sourceWidth).toInt().coerceIn(0,sampleWidth)
        val right=ceil(area.right.toDouble()*sampleWidth/sourceWidth).toInt().coerceIn(0,sampleWidth)
        val top=(area.top.toLong()*sampleHeight/sourceHeight).toInt().coerceIn(0,sampleHeight)
        val bottom=ceil(area.bottom.toDouble()*sampleHeight/sourceHeight).toInt().coerceIn(0,sampleHeight)
        if(right<=left || bottom<=top)return null
        val counts=IntArray(4096);val red=LongArray(4096);val green=LongArray(4096);val blue=LongArray(4096)
        val stride=maxOf(1,ceil(sqrt((right-left).toDouble()*(bottom-top)/4096)).toInt())
        for(y in top until bottom step stride)for(x in left until right step stride) {
            val pixel=pixels[y*sampleWidth+x]
            val r=Color.red(pixel);val g=Color.green(pixel);val b=Color.blue(pixel)
            val key=(r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
            counts[key]++;red[key]+=r;green[key]+=g;blue[key]+=b
        }
        val backgroundKey=counts.indices.maxByOrNull {counts[it]} ?: return null
        if(counts[backgroundKey]==0)return null
        fun colour(key: Int)=Color.rgb((red[key]/counts[key]).toInt(),(green[key]/counts[key]).toInt(),(blue[key]/counts[key]).toInt())
        val background=colour(backgroundKey)
        val foregroundKey=counts.indices.filter {counts[it]>0 && contrast(background,colour(it))>=3.0}
            .maxByOrNull {counts[it]}
        val fallback=if(contrast(background,Color.BLACK)>=contrast(background,Color.WHITE))Color.BLACK else Color.WHITE
        return ReadingSampledColours(background,foregroundKey?.let(::colour) ?: fallback)
    }

    companion object {
        const val MAX_AXIS=512
        fun from(bitmap: Bitmap): ReadingColourSamples {
            require(!bitmap.isRecycled && bitmap.width>0 && bitmap.height>0)
            val scale=minOf(1.0,MAX_AXIS.toDouble()/maxOf(bitmap.width,bitmap.height))
            val width=maxOf(1,(bitmap.width*scale).toInt());val height=maxOf(1,(bitmap.height*scale).toInt())
            val thumbnail=if(width==bitmap.width && height==bitmap.height)bitmap else Bitmap.createScaledBitmap(bitmap,width,height,false)
            return try {
                ReadingColourSamples(IntArray(width*height).also {thumbnail.getPixels(it,0,width,0,0,width,height)},
                    width,height,bitmap.width,bitmap.height)
            } finally {if(thumbnail!==bitmap)thumbnail.recycle()}
        }
        private fun contrast(a: Int,b: Int): Double {
            fun luminance(colour: Int): Double {
                fun channel(value: Int)= (value/255.0).let {if(it<=.04045)it/12.92 else Math.pow((it+.055)/1.055,2.4)}
                return .2126*channel(Color.red(colour))+.7152*channel(Color.green(colour))+.0722*channel(Color.blue(colour))
            }
            val x=luminance(a);val y=luminance(b)
            return (maxOf(x,y)+.05)/(minOf(x,y)+.05)
        }
    }
}

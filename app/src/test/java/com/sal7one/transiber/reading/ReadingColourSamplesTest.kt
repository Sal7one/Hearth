package com.sal7one.transiber.reading

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.sal7one.transiber.ocr.PixelCrop
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingColourSamplesTest {
    @Test fun samplesActualPaperAndColouredInkRatherThanForcingWhiteAndBlack() {
        val background=Color.rgb(238,224,198);val ink=Color.rgb(120,16,32)
        val source=Bitmap.createBitmap(240,120,Bitmap.Config.ARGB_8888).apply {eraseColor(background)}
        try {
            Canvas(source).drawRect(20f,35f,200f,42f,Paint().apply {color=ink})
            val sampled=ReadingColourSamples.from(source).sample(PixelCrop(10,20,220,70))!!
            assertEquals(background,sampled.background)
            assertEquals(ink,sampled.foreground)
            assertEquals(background,source.getPixel(0,0))
            assertEquals(ink,source.getPixel(30,38))
        } finally {source.recycle()}
    }
    @Test fun darkPageRetainsLightInkAndEmptyUniformPageChoosesContrastingFallback() {
        val background=Color.rgb(24,30,36);val ink=Color.rgb(214,240,246)
        val source=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888).apply {eraseColor(background)}
        try {
            Canvas(source).drawRect(20f,35f,170f,43f,Paint().apply {color=ink})
            val samples=ReadingColourSamples.from(source)
            assertEquals(ReadingSampledColours(background,ink),samples.sample(PixelCrop(10,20,180,70)))
            assertSame("Progressive redraws and style sliders reuse this source region's palette",
                samples.sample(PixelCrop(10,20,180,70)),samples.sample(PixelCrop(10,20,180,70)))
            assertEquals(ReadingSampledColours(background,Color.WHITE),samples.sample(PixelCrop(0,0,10,10)))
            assertNull(samples.sample(PixelCrop(-40,-40,-1,-1)))
        } finally {source.recycle()}
    }
    @Test fun cleanCaptureRetentionIsBoundedAndIndependentOfWorkerBitmapOwnership() {
        val source=Bitmap.createBitmap(2000,1000,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(40,70,100))}
        val samples=try {ReadingColourSamples.from(source)} finally {source.recycle()}
        assertTrue(samples.retainedPixelCount<=ReadingColourSamples.MAX_AXIS*ReadingColourSamples.MAX_AXIS)
        assertEquals(2000,samples.sourceWidth);assertEquals(1000,samples.sourceHeight)
        assertEquals(Color.rgb(40,70,100),samples.sample(PixelCrop(1800,800,2000,1000))!!.background)
    }
    @Test fun regionPaletteCacheIsBoundedToOneCaptureAndAReplacementImageGetsFreshColours() {
        val source=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.YELLOW)}
        try {
            val samples=ReadingColourSamples.from(source)
            repeat(180) {x->samples.sample(PixelCrop(x,0,x+10,10))}
            val entries=org.robolectric.util.ReflectionHelpers.getField<Map<*,*>>(samples,"colours")
            assertEquals(128,entries.size)
            source.eraseColor(Color.BLUE)
            assertEquals(Color.YELLOW,samples.sample(PixelCrop(0,0,10,10))!!.background)
            assertEquals(Color.BLUE,ReadingColourSamples.from(source).sample(PixelCrop(0,0,10,10))!!.background)
        } finally {source.recycle()}
    }
}

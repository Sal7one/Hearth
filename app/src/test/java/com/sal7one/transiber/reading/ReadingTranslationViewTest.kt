package com.sal7one.transiber.reading

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.sal7one.common_jni.ocr.OcrLine
import com.sal7one.hearth.core.vision.*
import com.sal7one.transiber.ocr.PixelCrop
import com.sal7one.transiber.ocr.TranslatedOcrBox
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The same real Android view is used over imported camera pages and cross-app captures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingTranslationViewTest {
    @Test @Config(qualifiers="xxhdpi") fun sampledNativeHighlightPixelsStayInsideTheirMotionExclusionsAtPhoneScale() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view=ReadingTranslationView(activity.get()){}
        assertEquals(3f,view.resources.displayMetrics.density,.001f)
        val crop=PixelCrop(0,59,720,1504)
        // Odd source coordinates place stroke edges between physical/capture pixels.
        // This reproduces the 29-region, 1080x2400 phone publication that looked like scrolling.
        val regions=List(29) {index->
            OcrRegion(OcrRect(23+index%3,12+index*50,670-index%5*7,23+index%3),.9f)
        }
        try {
            layoutPhoneOverlay(view)
            view.updateRegions(regions,720,1600,crop)
            layoutPhoneOverlay(view)
            val pixels=capturePhoneOverlay(view,Color.WHITE)
            val exclusions=view.motionExclusions()
            assertRenderedChangesExcluded(pixels,Color.WHITE,exclusions)
            val samples=sampleCapture(pixels)
            val motion=ReadingMotion()
            assertFalse(motion.observe(IntArray(samples.size){Color.WHITE},720,1600,emptyList()))
            assertFalse("Publishing the actual native teal outlines must not look like page movement",
                motion.observe(samples,720,1600,emptyList(),painted=exclusions,paintStable=false))
        } finally {activity.pause().stop().destroy()}
    }

    @Test @Config(qualifiers="xxhdpi") fun sampledNativeLabelBackgroundUsesTheSameRoundedBoundsAsTheActualView() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view=ReadingTranslationView(activity.get()){}
        val crop=PixelCrop(11,59,709,1504)
        val boxes=List(12) {index->
            TranslatedOcrBox(OcrLine(13+index%3,3+index*105,635-index%4*9,35+index%3,
                "An article about coffee",.9f),"مقال عن القهوة وطرق تحضيرها")
        }
        try {
            layoutPhoneOverlay(view)
            view.update(boxes,720,1600,crop)
            layoutPhoneOverlay(view)
            val pixels=capturePhoneOverlay(view,Color.BLACK)
            val exclusions=view.motionExclusions()
            assertRenderedChangesExcluded(pixels,Color.BLACK,exclusions)
            val samples=sampleCapture(pixels)
            val motion=ReadingMotion()
            assertFalse(motion.observe(IntArray(samples.size){Color.BLACK},720,1600,emptyList()))
            assertFalse("Publishing native translated labels must not look like scrolling",
                motion.observe(samples,720,1600,emptyList(),painted=exclusions,paintStable=false))
        } finally {activity.pause().stop().destroy()}
    }

    @Test @Config(qualifiers="xxhdpi") fun fractionalCleanupProjectionKeepsPatchAndRleFringesExcludedWithoutPaintingTheirHoles() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val crop=PixelCrop(11,59,709,1504)
        try {
            for(patched in listOf(false,true)) {
                val view=ReadingTranslationView(activity.get()){}
                val runs=buildList {
                    repeat(61) {row->addAll(listOf(50+row,50,34,50+row,117,34))}
                }.toIntArray()
                val bounds=OcrRect(50,50,101,61)
                val paint=MangaPaint(ComicMask(runs,crop.width,crop.height),OcrRect(4,4,20,20),Color.WHITE,Color.BLACK,
                    if(patched)MangaPatch(bounds,IntArray(bounds.width*bounds.height){Color.WHITE}) else null)
                val region=OcrRegion(OcrRect(5,5,15,10),.9f,paint=paint)
                val box=TranslatedOcrBox(OcrLine(5,5,15,10,"coffee",.9f,region),"")
                try {
                    layoutPhoneOverlay(view)
                    view.update(listOf(box),720,1600,crop)
                    layoutPhoneOverlay(view)
                    val exclusions=view.motionExclusions()
                    assertTrue("Keep the tight cleanup bounds as well as any demonstrated projection fringe",
                        exclusions.contains(PixelCrop(61,109,162,170)))
                    val pixels=capturePhoneOverlay(view,Color.BLACK)
                    assertRenderedChangesExcluded(pixels,Color.BLACK,exclusions)
                    if(!patched)assertEquals("A real hole between the two RLE spans must remain the original page",
                        Color.BLACK,pixels[130*720+110])
                } finally {view.update(emptyList())}
            }
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun swipingWithinALargeTranslatedParagraphNeverOpensTheOptionsButTapAndAccessibilityStillWork() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        var opened=0
        val view=ReadingTranslationView(activity.get()){opened++}
        activity.get().setContentView(view)
        val box=TranslatedOcrBox(OcrLine(0,0,300,300,"A long coffee article",.9f),"مقال طويل عن القهوة")
        try {
            view.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY))
            view.layout(0,0,300,400)
            view.update(listOf(box),300,400,PixelCrop(0,0,300,400))
            view.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY))
            view.layout(0,0,300,400)
            val label=view.getChildAt(0)
            assertTrue(label.height>ViewConfiguration.get(activity.get()).scaledTouchSlop*4)
            var time=SystemClock.uptimeMillis()
            fun touch(action: Int,y: Float) {
                val event=MotionEvent.obtain(time,time+10,action,100f,y,0)
                try {view.dispatchTouchEvent(event)} finally {event.recycle()}
                time+=20
                shadowOf(Looper.getMainLooper()).idle()
            }
            touch(MotionEvent.ACTION_DOWN,40f);touch(MotionEvent.ACTION_UP,40f)
            assertEquals(1,opened)
            touch(MotionEvent.ACTION_DOWN,40f)
            touch(MotionEvent.ACTION_MOVE,200f) // Deliberately remains within this same paragraph.
            touch(MotionEvent.ACTION_UP,200f)
            assertEquals("Scrolling within translated text must not become an options click",1,opened)
            touch(MotionEvent.ACTION_DOWN,40f);touch(MotionEvent.ACTION_UP,40f)
            assertEquals("A completed swipe must not poison the next deliberate tap",2,opened)
            label.performClick()
            assertEquals("Accessibility / keyboard clicks retain full-text access",3,opened)
            touch(MotionEvent.ACTION_DOWN,40f);touch(MotionEvent.ACTION_CANCEL,40f);touch(MotionEvent.ACTION_UP,40f)
            assertEquals(3,opened)
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun cachedFlatMaskPreservesHolesAndUntranslatedBalloonsAcrossRedrawAndReattach() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLACK)}
        val view=ReadingTranslationView(activity.get()){}
        val host=FrameLayout(activity.get()).apply {
            addView(ImageView(context).apply {setImageBitmap(source)},FrameLayout.LayoutParams(100,100))
            addView(view,FrameLayout.LayoutParams(100,100))
        }
        activity.get().setContentView(host)
        val runs=buildList {repeat(10) {y -> addAll(listOf(10+y,10,4,10+y,18,4))}}.toIntArray()
        val mask=ComicMask(runs,100,100)
        val paint=MangaPaint(mask,OcrRect(50,50,40,40),Color.WHITE,Color.BLACK)
        val region=OcrRegion(OcrRect(10,10,12,10),.9f,paint=paint)
        val untranslated=OcrRegion(OcrRect(40,10,10,10),.9f)
        val box=TranslatedOcrBox(OcrLine(10,10,12,10,"日本語",.9f,region),"read")
        try {
            fun layout() {
                host.measure(View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY))
                host.layout(0,0,100,100);shadowOf(Looper.getMainLooper()).idle()
            }
            fun checkPixels() {
                val image=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
                try {
                    host.draw(Canvas(image))
                    assertEquals(Color.WHITE,image.getPixel(12,15))
                    assertEquals(Color.BLACK,image.getPixel(16,15)) // A real hole in the mask remains art.
                    assertNotEquals(Color.WHITE,image.getPixel(45,15)) // No translation: no cleanup.
                }finally{image.recycle()}
            }
            layout();view.updateRegions(listOf(region,untranslated),100,100,PixelCrop(0,0,100,100));view.update(listOf(box));layout()
            assertTrue(view.motionExclusions().contains(PixelCrop(10,10,22,20))) // Tight RLE cleanup bounds, not its full page size.
            repeat(5) {checkPixels()}
            host.removeView(view);host.addView(view,FrameLayout.LayoutParams(100,100));layout();checkPixels()
            view.showOriginal=true;layout()
            val original=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
            try {host.draw(Canvas(original));assertEquals(Color.BLACK,original.getPixel(12,15))}finally{original.recycle()}
            assertEquals(Color.BLACK,source.getPixel(12,15))
        } finally {activity.pause().stop().destroy();source.recycle()}
    }

    @Test fun neuralPatchAndTypesettingDisappearForOriginalWithoutMutatingThePhoto() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLACK)}
        var opened: TranslatedOcrBox?=null
        val view=ReadingTranslationView(activity.get()){opened=it}
        val host=FrameLayout(activity.get()).apply {
            addView(ImageView(context).apply {setImageBitmap(source)},FrameLayout.LayoutParams(100,100))
            addView(view,FrameLayout.LayoutParams(100,100))
        }
        activity.get().setContentView(host)
        val mask=ComicMask(IntArray(30).also {out -> repeat(10){out[it*3]=10+it;out[it*3+1]=10;out[it*3+2]=10}},100,100)
        val paint=MangaPaint(mask,OcrRect(30,30,40,40),0,Color.BLACK,
            MangaPatch(OcrRect(10,10,10,10),IntArray(100){Color.WHITE}))
        val region=OcrRegion(OcrRect(10,10,10,10),.9f,paint=paint)
        val box=TranslatedOcrBox(OcrLine(10,10,10,10,"日本語",.9f,region),"ترجمة عربية")
        try {
            fun layout() {
                host.measure(View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY))
                host.layout(0,0,100,100);shadowOf(Looper.getMainLooper()).idle()
            }
            fun pixel():Int=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).let {image ->
                try {host.draw(Canvas(image));image.getPixel(15,15)}finally{image.recycle()}
            }
            layout();view.updateRegions(listOf(region),100,100,PixelCrop(0,0,100,100));view.update(listOf(box));layout()
            assertEquals(Color.WHITE,pixel());assertEquals(1,view.childCount)
            view.getChildAt(0).performClick();assertSame(box,opened)
            view.showOriginal=true;layout()
            assertEquals(Color.BLACK,pixel());assertEquals(0,view.childCount)
            assertEquals(Color.BLACK,source.getPixel(15,15))
            view.showOriginal=false;layout()
            assertEquals(Color.WHITE,pixel());assertEquals(1,view.childCount)
        } finally {activity.pause().stop().destroy();source.recycle()}
    }

    @Test fun motionMasksIncludeActualCleanupOutsideTextAndCropOffsetsButOriginalHasNone() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val source=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLACK)}
        val view=ReadingTranslationView(activity.get()){}
        val host=FrameLayout(activity.get()).apply {
            addView(ImageView(context).apply {setImageBitmap(source)},FrameLayout.LayoutParams(200,200))
            addView(view,FrameLayout.LayoutParams(200,200))
        }
        activity.get().setContentView(host)
        val mask=ComicMask(intArrayOf(50,50,20),100,100)
        val paint=MangaPaint(mask,OcrRect(4,4,20,20),Color.WHITE,Color.BLACK,
            MangaPatch(OcrRect(50,50,20,20),IntArray(400){Color.WHITE}))
        val region=OcrRegion(OcrRect(5,5,15,10),.9f,paint=paint)
        val pending=OcrRegion(OcrRect(75,10,10,10),.9f)
        val box=TranslatedOcrBox(OcrLine(5,5,15,10,"source",.9f,region),"read")
        fun layout() {
            host.measure(View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.EXACTLY))
            host.layout(0,0,200,200);shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            layout();view.updateRegions(listOf(region,pending),200,200,PixelCrop(40,60,140,160));view.update(listOf(box));layout()
            val exclusions=view.motionExclusions()
            assertTrue(exclusions.contains(PixelCrop(90,110,110,130))) // Cleanup is separate from the source line and label.
            assertTrue(exclusions.any {it.left<=44 && it.top<=64 && it.right>=64 && it.bottom>=84})
            assertTrue(exclusions.any {it.left<115 && it.top<70 && it.right>125 && it.bottom>80}) // Outline stroke too.
            val drawn=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888)
            try {host.draw(Canvas(drawn));assertEquals(Color.WHITE,drawn.getPixel(100,120))}finally{drawn.recycle()}
            assertEquals(Color.BLACK,source.getPixel(100,120))
            view.showOriginal=true
            assertTrue(view.motionExclusions().isEmpty())
            view.showOriginal=false
            assertEquals(exclusions,view.motionExclusions())
        } finally {activity.pause().stop().destroy();source.recycle()}
    }

    @Test fun textSizeControlsUpdateExistingLabelsOnceWithoutChangingCleanupOrSourcePixels() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLACK)}
        val view=ReadingTranslationView(activity.get()){}
        val host=FrameLayout(activity.get()).apply {
            addView(ImageView(context).apply {setImageBitmap(source)},FrameLayout.LayoutParams(100,100))
            addView(view,FrameLayout.LayoutParams(100,100))
        }
        activity.get().setContentView(host)
        val mask=ComicMask(intArrayOf(95,95,3),100,100)
        val paint=MangaPaint(mask,OcrRect(5,5,80,70),Color.WHITE,Color.BLACK,
            MangaPatch(OcrRect(90,90,10,10),IntArray(100){Color.WHITE}))
        val region=OcrRegion(OcrRect(10,10,30,30),.9f,paint=paint)
        val box=TranslatedOcrBox(OcrLine(10,10,30,30,"source",.9f,region),"Hi")
        fun layout() {
            host.measure(View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY))
            host.layout(0,0,100,100);shadowOf(Looper.getMainLooper()).idle()
        }
        fun sp(value: Float)=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,value,view.resources.displayMetrics).toInt()
        try {
            layout();view.update(listOf(box),100,100,PixelCrop(0,0,100,100));layout()
            val label=view.getChildAt(0) as TextView
            assertEquals(sp(10f),TextViewCompat.getAutoSizeMinTextSize(label))
            assertEquals(sp(24f),TextViewCompat.getAutoSizeMaxTextSize(label))
            val exclusions=view.motionExclusions()
            view.textSizeSp=28f;layout()
            assertSame(label,view.getChildAt(0))
            assertEquals(sp(34f),TextViewCompat.getAutoSizeMaxTextSize(label))
            assertFalse(label.isLayoutRequested)
            view.textSizeSp=28f
            assertFalse(label.isLayoutRequested) // Re-emitting the same preference does no UI work.
            assertEquals(exclusions,view.motionExclusions())
            view.textSizeSp=0f;assertEquals(14f,view.textSizeSp)
            assertEquals(sp(17f),TextViewCompat.getAutoSizeMaxTextSize(label))
            view.textSizeSp=Float.NaN;assertEquals(20f,view.textSizeSp)
            view.textSizeSp=100f;assertEquals(34f,view.textSizeSp)
            layout()
            val drawn=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
            try {host.draw(Canvas(drawn));assertEquals(Color.WHITE,drawn.getPixel(95,95))}finally{drawn.recycle()}
            assertEquals(Color.BLACK,source.getPixel(95,95))
        } finally {activity.pause().stop().destroy();source.recycle()}
    }

    private fun layoutPhoneOverlay(view: ReadingTranslationView) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1080,2400)
    }

    /** Real native Canvas publication, window alpha, then MediaProjection-sized resampling. */
    private fun capturePhoneOverlay(view: ReadingTranslationView,background: Int): IntArray {
        val screen=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
        try {
            screen.eraseColor(background)
            val canvas=Canvas(screen)
            val layer=canvas.saveLayerAlpha(null,204) // The real touch-through window opacity is .8.
            view.draw(canvas)
            canvas.restoreToCount(layer)
            val capture=Bitmap.createScaledBitmap(screen,720,1600,true)
            try {
                return IntArray(720*1600).also {capture.getPixels(it,0,720,0,0,720,1600)}
            } finally {capture.recycle()}
        } finally {screen.recycle()}
    }

    private fun assertRenderedChangesExcluded(pixels: IntArray,background: Int,exclusions: List<PixelCrop>) {
        val mask=BooleanArray(pixels.size)
        exclusions.forEach {bounds->
            for(y in bounds.top until bounds.bottom) {
                mask.fill(true,y*720+bounds.left,y*720+bounds.right)
            }
        }
        fun grey(color: Int)=(Color.red(color)*3+Color.green(color)*6+Color.blue(color))/10
        val original=grey(background)
        var changed=0
        var escaped=0
        var firstEscape=-1
        pixels.forEachIndexed {index,color->
            if(kotlin.math.abs(grey(color)-original)>=8) {
                changed++
                if(!mask[index]) {escaped++;if(firstEscape<0)firstEscape=index}
            }
        }
        assertTrue("The native rendering fixture must publish substantial visible text/highlights",changed>1000)
        assertEquals("$escaped native pixels escape exclusions; first source pixel " +
            "(${firstEscape%720},${firstEscape/720})",0,escaped)
    }

    /** Exercise the same 64x128 padded RGBA sampler used by the live service. */
    private fun sampleCapture(pixels: IntArray): IntArray {
        val rowStride=720*4+16
        val buffer=ByteBuffer.allocate(rowStride*1600)
        repeat(ReadingMotion.WIDTH*ReadingMotion.HEIGHT) {index->
            val x=((index%ReadingMotion.WIDTH+.5)*720/ReadingMotion.WIDTH).toInt()
            val y=((index/ReadingMotion.WIDTH+.5)*1600/ReadingMotion.HEIGHT).toInt()
            val color=pixels[y*720+x]
            val offset=y*rowStride+x*4
            buffer.put(offset,Color.red(color).toByte())
            buffer.put(offset+1,Color.green(color).toByte())
            buffer.put(offset+2,Color.blue(color).toByte())
            buffer.put(offset+3,0xff.toByte())
        }
        return ReadingMotionSampler.sample(buffer,720,1600,rowStride,4)
    }
}

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
                view.applyAppearance(ReadingOverlaySettings(replaceText=true))
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
        view.applyAppearance(ReadingOverlaySettings(replaceText=true))
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
        view.applyAppearance(ReadingOverlaySettings(replaceText=true))
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
        view.applyAppearance(ReadingOverlaySettings(replaceText=true))
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
        view.applyAppearance(ReadingOverlaySettings(replaceText=true))
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
            assertNull(label.ellipsize)
            assertEquals(Int.MAX_VALUE,label.maxLines)
            assertTrue(label.textSize>=sp(14f))
            val exclusions=view.motionExclusions()
            view.textSizeSp=28f;layout()
            assertSame(label,view.getChildAt(0))
            assertTrue(label.textSize>=sp(14f))
            assertTrue(label.textSize<=sp(34f)+1)
            assertFalse(label.isLayoutRequested)
            view.textSizeSp=28f
            assertFalse(label.isLayoutRequested) // Re-emitting the same preference does no UI work.
            assertEquals("Short text keeps the same source geometry while its preferred font changes",exclusions,view.motionExclusions())
            view.textSizeSp=0f;assertEquals(14f,view.textSizeSp)
            assertTrue(label.textSize<=sp(17f)+1)
            view.textSizeSp=Float.NaN;assertEquals(20f,view.textSizeSp)
            view.textSizeSp=100f;assertEquals(34f,view.textSizeSp)
            layout()
            val drawn=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
            try {host.draw(Canvas(drawn));assertEquals(Color.WHITE,drawn.getPixel(95,95))}finally{drawn.recycle()}
            assertEquals(Color.BLACK,source.getPixel(95,95))
        } finally {activity.pause().stop().destroy();source.recycle()}
    }

    @Test fun sourceReplacementIsOffUntilExplicitlyEnabledAndCanBeDisabledWithoutAnotherTranslation() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view=ReadingTranslationView(activity.get()){}
        val paint=MangaPaint(ComicMask(intArrayOf(90,90,5),100,100),OcrRect(5,5,25,15),Color.WHITE,Color.BLACK,
            MangaPatch(OcrRect(90,90,5,5),IntArray(25){Color.WHITE}))
        val region=OcrRegion(OcrRect(5,5,25,15),1f,paint=paint)
        val box=TranslatedOcrBox(OcrLine(5,5,25,15,"Coffee",1f,region),"قهوة")
        try {
            activity.get().setContentView(view);layoutOverlay(view,100,100)
            view.update(listOf(box),100,100,PixelCrop(0,0,100,100));layoutOverlay(view,100,100)
            fun pixel(): Int {
                val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLACK)}
                return try {view.draw(Canvas(bitmap));bitmap.getPixel(92,92)} finally {bitmap.recycle()}
            }
            assertEquals(Color.BLACK,pixel())
            assertFalse(view.motionExclusions().any {it.left<=90 && it.top<=90 && it.right>=95 && it.bottom>=95})
            view.applyAppearance(ReadingOverlaySettings(replaceText=true));layoutOverlay(view,100,100)
            assertEquals(Color.WHITE,pixel())
            assertTrue(view.motionExclusions().contains(PixelCrop(90,90,95,95)))
            val label=view.getChildAt(0)
            view.applyAppearance(ReadingOverlaySettings());layoutOverlay(view,100,100)
            assertEquals(Color.BLACK,pixel());assertSame(label,view.getChildAt(0))
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun tinyEdgeBoxKeepsEveryArabicCharacterAndScrollsWithoutOpeningOptions() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        var opened=0
        val view=ReadingTranslationView(activity.get()){opened++}
        val translated=List(100){"القهوة العربية تحتاج إلى ماء ساخن وطحن مناسب."}.joinToString(" ")
        val box=TranslatedOcrBox(OcrLine(270,290,10,10,"Coffee article",1f),translated)
        try {
            activity.get().setContentView(view);layoutOverlay(view,280,300)
            view.applyAppearance(ReadingOverlaySettings(boxWidthPercent=50,boxHeightPercent=50))
            view.update(listOf(box),280,300,PixelCrop(0,0,280,300));layoutOverlay(view,280,300)
            val label=view.getChildAt(0) as TextView
            assertEquals(translated,label.text.toString());assertNull(label.ellipsize);assertEquals(Int.MAX_VALUE,label.maxLines)
            assertTrue(label.left>=0 && label.top>=0 && label.right<=280 && label.bottom<=300)
            assertTrue("A narrow paragraph must not automatically occupy the whole viewport",label.height<150)
            assertEquals(translated.length,label.layout.getLineEnd(label.layout.lineCount-1))
            assertTrue(label.layout.lineCount>2)
            assertTrue(label.layout.height>label.height)
            for(i in 0 until label.layout.lineCount)assertEquals(0,label.layout.getEllipsisCount(i))
            assertEquals(-1,label.layout.getParagraphDirection(0))
            val start=SystemClock.uptimeMillis()
            fun touch(action: Int,y: Float) {
                val event=MotionEvent.obtain(start,start+20,action,label.width/2f,y,0)
                try {label.dispatchTouchEvent(event)} finally {event.recycle()}
            }
            touch(MotionEvent.ACTION_DOWN,label.height-2f);touch(MotionEvent.ACTION_MOVE,2f);touch(MotionEvent.ACTION_UP,2f)
            assertTrue("The complete paragraph remains readable by vertical scrolling",label.scrollY>0)
            assertEquals(0,opened)
            label.performClick();assertEquals(1,opened)
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun styleChangesReuseTheTranslationAndLabelsWhileWidthHeightAndColoursUpdate() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view=ReadingTranslationView(activity.get()){}
        val box=TranslatedOcrBox(OcrLine(80,50,100,50,"Coffee",1f),"ترجمة القهوة")
        try {
            activity.get().setContentView(view);layoutOverlay(view,400,300)
            view.update(listOf(box),400,300,PixelCrop(0,0,400,300));layoutOverlay(view,400,300)
            val label=view.getChildAt(0) as TextView
            val originalBounds=view.renderedLabels().single().bounds
            val settings=ReadingOverlaySettings(boxWidthPercent=200,boxHeightPercent=200,
                boxBackgroundArgb=Color.rgb(20,40,60),boxTextArgb=Color.rgb(240,210,120),boxOpacity=60)
            view.applyAppearance(settings);layoutOverlay(view,400,300)
            assertSame(label,view.getChildAt(0));assertEquals(box.translation,label.text.toString())
            val drawn=view.renderedLabels().single()
            assertTrue(drawn.bounds.width>originalBounds.width);assertTrue(drawn.bounds.height>originalBounds.height)
            assertEquals(settings.boxTextArgb,drawn.foreground)
            assertEquals(Color.argb(153,20,40,60),drawn.background)
            assertEquals(box.line,OcrLine(80,50,100,50,"Coffee",1f))
            view.applyAppearance(settings)
            assertFalse("Repeated preference snapshots do not request another traversal",label.isLayoutRequested)
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun replacementErasesOriginalInkOutsideAShrunkenLabelAtFullOpacityWithSampledAndFallbackColours() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val sampledColour=Color.rgb(230,220,200)
        val fallbackColour=Color.rgb(255,170,85)
        val source=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888).apply {
            eraseColor(sampledColour)
            for(y in 78 until 150)for(x in 88 until 95)setPixel(x,y,Color.BLACK)
        }
        val box=TranslatedOcrBox(OcrLine(80,70,240,90,"Original coffee article",1f),"Translated")
        try {
            for(sampled in listOf(false,true)) {
                val view=ReadingTranslationView(activity.get()){}
                activity.get().setContentView(view);layoutOverlay(view,400,300)
                if(sampled)view.setSourceImage(source)
                view.applyAppearance(ReadingOverlaySettings(replaceText=true,boxWidthPercent=50,
                    boxHeightPercent=50,boxOpacity=10,boxBackgroundArgb=fallbackColour))
                view.update(listOf(box),400,300,PixelCrop(0,0,400,300));layoutOverlay(view,400,300)
                val label=view.renderedLabels().single()
                assertEquals(255,Color.alpha(label.background))
                assertTrue("The original ink fixture must lie outside the resized translation label",
                    90<label.bounds.left || 90>=label.bounds.right || 100<label.bounds.top || 100>=label.bounds.bottom)
                val image=source.copy(Bitmap.Config.ARGB_8888,true)
                try {
                    view.draw(Canvas(image))
                    val expected=if(sampled)sampledColour else fallbackColour
                    assertEquals("Cleanup must cover original ink, even at saved 10% box opacity",expected,image.getPixel(90,100))
                    assertEquals("Uncovered artwork remains the source image",sampledColour,image.getPixel(20,20))
                    assertTrue(view.motionExclusions().any {it.left<=90 && it.right>90 && it.top<=100 && it.bottom>100})
                } finally {image.recycle()}
                assertEquals("Replacement never mutates the captured source",Color.BLACK,source.getPixel(90,100))
            }
        } finally {activity.pause().stop().destroy();source.recycle()}
    }

    @Test fun expandedNeighbouringBoxesAvoidEachOtherAndExposeTheirActualMotionBounds() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view=ReadingTranslationView(activity.get()){}
        val boxes=listOf(10,22,34).map {y->TranslatedOcrBox(OcrLine(30,y,100,8,"Line $y",1f),"Translated line $y")}
        try {
            activity.get().setContentView(view);layoutOverlay(view,320,300)
            view.applyAppearance(ReadingOverlaySettings(boxWidthPercent=150,boxHeightPercent=200))
            view.update(boxes,320,300,PixelCrop(0,0,320,300));layoutOverlay(view,320,300)
            val bounds=view.renderedLabels().map {it.bounds}
            assertEquals(3,bounds.size)
            bounds.forEachIndexed {index,box ->
                assertTrue(box.left>=0 && box.top>=0 && box.right<=320 && box.bottom<=300)
                bounds.drop(index+1).forEach {other ->
                    assertTrue(box.right<=other.left || other.right<=box.left || box.bottom<=other.top || other.bottom<=box.top)
                }
                assertTrue(view.motionExclusions().any {it.left<=box.left && it.top<=box.top && it.right>=box.right && it.bottom>=box.bottom})
            }
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun narrowLongParagraphStaysCompactAndHeightSliderIncreasesTheVisibleText() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view=ReadingTranslationView(activity.get()){}
        val text=List(40){"A paragraph about coffee brewing and water temperature."}.joinToString(" ")
        val boxes=listOf(TranslatedOcrBox(OcrLine(20,20,100,12,"Coffee article",1f),text),
            TranslatedOcrBox(OcrLine(20,100,100,12,"Search",1f),"بحث"))
        try {
            activity.get().setContentView(view);layoutOverlay(view,320,500)
            view.update(boxes,320,500,PixelCrop(0,0,320,500));layoutOverlay(view,320,500)
            val label=view.getChildAt(0) as TextView
            val compactHeight=label.height
            assertTrue(compactHeight<125);assertTrue(label.layout.height>label.height)
            assertTrue(view.renderedLabels().let {it[0].bounds.bottom<=it[1].bounds.top || it[1].bounds.bottom<=it[0].bounds.top})
            view.applyAppearance(ReadingOverlaySettings(boxHeightPercent=300));layoutOverlay(view,320,500)
            assertSame(label,view.getChildAt(0));assertTrue(label.height>compactHeight)
            assertTrue(label.height<250);assertEquals(text,label.text.toString())
            assertEquals(text.length,label.layout.getLineEnd(label.layout.lineCount-1))
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun laterProgressiveBoxDoesNotRelayoutOrResetTheParagraphBeingRead() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        var opened=0
        val view=ReadingTranslationView(activity.get()){opened++}
        val first=TranslatedOcrBox(OcrLine(10,10,160,20,"Coffee article",1f),
            List(40){"Coffee brewing needs a suitable grind size and hot water."}.joinToString(" "))
        val next=TranslatedOcrBox(OcrLine(10,200,160,20,"Another paragraph",1f),"A later translated paragraph")
        try {
            activity.get().setContentView(view);layoutOverlay(view,300,500)
            view.update(listOf(first),300,500,PixelCrop(0,0,300,500));layoutOverlay(view,300,500)
            val label=view.getChildAt(0) as TextView
            scrollParagraph(label)
            val held=label.scrollY
            assertTrue(held>0)
            val geometry=label.layoutParams
            view.update(listOf(first,next))
            assertSame("An unchanged paragraph keeps its existing geometry object",geometry,label.layoutParams)
            layoutOverlay(view,300,500)
            repeat(3){label.onPreDraw()} // TextView may bring content into view on multiple frames.
            assertSame(label,view.getChildAt(0));assertEquals(held,label.scrollY)
            assertEquals(first.translation.length,label.layout.getLineEnd(label.layout.lineCount-1))
            assertEquals(0,opened)
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun scrollingParagraphHonoursTextSizeChangesInsteadOfAlwaysUsingTheMinimumFont() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view=ReadingTranslationView(activity.get()){}
        val text=List(60){"القهوة العربية تحتاج إلى درجة طحن مناسبة وماء ساخن."}.joinToString(" ")
        val box=TranslatedOcrBox(OcrLine(20,20,160,20,"Coffee article",1f),text)
        try {
            activity.get().setContentView(view);layoutOverlay(view,320,500)
            view.applyAppearance(ReadingOverlaySettings(textSizeSp=14f))
            view.update(listOf(box),320,500,PixelCrop(0,0,320,500));layoutOverlay(view,320,500)
            val label=view.getChildAt(0) as TextView
            val small=label.textSize
            assertTrue(label.layout.height>label.height)
            scrollParagraph(label);val held=label.scrollY;assertTrue(held>0)
            view.applyAppearance(ReadingOverlaySettings(textSizeSp=34f));layoutOverlay(view,320,500)
            repeat(3){label.onPreDraw()}
            assertSame(label,view.getChildAt(0));assertTrue(label.textSize>small*2)
            assertTrue(label.layout.height>label.height);assertEquals(held,label.scrollY)
            assertEquals(text,label.text.toString());assertEquals(text.length,label.layout.getLineEnd(label.layout.lineCount-1))
            val beforeScroll=label.scrollY;scrollParagraph(label);assertTrue(label.scrollY>beforeScroll)
            assertNull(label.ellipsize)
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun matchedMangaLabelKeepsCleanupPatchAndBubbleArtworkVisible() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view=ReadingTranslationView(activity.get()){}
        val paint=MangaPaint(ComicMask(intArrayOf(10,10,10),100,100),OcrRect(30,30,40,40),Color.WHITE,Color.BLACK,
            MangaPatch(OcrRect(10,10,10,10),IntArray(100){Color.WHITE}))
        val box=TranslatedOcrBox(OcrLine(10,10,10,10,"Coffee",1f,OcrRegion(OcrRect(10,10,10,10),1f,paint=paint)),"A")
        try {
            activity.get().setContentView(view);layoutOverlay(view,100,100)
            view.applyAppearance(ReadingOverlaySettings(replaceText=true))
            view.update(listOf(box),100,100,PixelCrop(0,0,100,100));layoutOverlay(view,100,100)
            assertEquals(Color.TRANSPARENT,view.renderedLabels().single().background)
            val image=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.MAGENTA)}
            try {
                view.draw(Canvas(image))
                assertEquals("Mask cleanup is still applied",Color.WHITE,image.getPixel(15,15))
                assertEquals("The label's padding must not cover artwork with sampled white",Color.MAGENTA,image.getPixel(32,32))
            } finally {image.recycle()}
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun cleanupRefinementKeepsTheHeldLabelButClickUsesTheNewestRegion() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        var opened: TranslatedOcrBox?=null
        val view=ReadingTranslationView(activity.get()){opened=it}
        val paint=MangaPaint(ComicMask(intArrayOf(20,20,40),300,500),OcrRect(10,10,160,20),Color.WHITE,Color.BLACK)
        val region=OcrRegion(OcrRect(10,10,160,20),.8f,paint=paint)
        val first=TranslatedOcrBox(OcrLine(10,10,160,20,"Coffee article",.8f,region),
            List(40){"The source bubble is translated while cleanup finishes."}.joinToString(" "))
        try {
            activity.get().setContentView(view);layoutOverlay(view,300,500)
            view.applyAppearance(ReadingOverlaySettings(replaceText=true))
            view.update(listOf(first),300,500,PixelCrop(0,0,300,500));layoutOverlay(view,300,500)
            val label=view.getChildAt(0) as TextView
            scrollParagraph(label);val held=label.scrollY;assertTrue(held>0)
            val refinedRegion=region.copy(confidence=.95f,paint=paint.copy(background=Color.BLACK,foreground=Color.WHITE))
            val refined=first.copy(line=first.line.copy(confidence=.95f,region=refinedRegion))
            view.update(listOf(refined));layoutOverlay(view,300,500)
            repeat(3){label.onPreDraw()}
            assertEquals(1,view.childCount);assertSame(label,view.getChildAt(0));assertEquals(held,label.scrollY)
            assertEquals(Color.WHITE,label.currentTextColor)
            assertEquals(first.translation,label.text.toString())
            assertEquals(Color.TRANSPARENT,view.renderedLabels().single().background)
            label.performClick();assertSame(refined,opened)
        } finally {activity.pause().stop().destroy()}
    }

    @Test @Config(qualifiers="xxhdpi") fun sampledGenericReplacementAndExpandedLabelsExcludeEveryPaintedPixel() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val source=Bitmap.createBitmap(720,1445,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(230,220,200))}
        val view=ReadingTranslationView(activity.get()){}
        try {
            layoutPhoneOverlay(view)
            view.setSourceImage(source)
            view.applyAppearance(ReadingOverlaySettings(replaceText=true,boxWidthPercent=180,boxHeightPercent=170))
            val boxes=listOf(TranslatedOcrBox(OcrLine(400,1200,100,25,"Coffee article",1f),"مقالة عن القهوة وطرق تحضيرها"))
            view.update(boxes,720,1600,PixelCrop(0,59,720,1504));layoutPhoneOverlay(view)
            assertEquals(Color.rgb(230,220,200),Color.rgb(Color.red(view.renderedLabels().single().background),
                Color.green(view.renderedLabels().single().background),Color.blue(view.renderedLabels().single().background)))
            val pixels=capturePhoneOverlay(view,Color.BLACK)
            assertRenderedChangesExcluded(pixels,Color.BLACK,view.motionExclusions())
            assertEquals(Color.rgb(230,220,200),source.getPixel(0,0))
        } finally {activity.pause().stop().destroy();source.recycle()}
    }

    private fun layoutOverlay(view: ReadingTranslationView,width: Int,height: Int) {
        // An attached MATCH_PARENT content view is otherwise resized by the Activity's
        // pending decor traversal after the manual layout, invalidating this viewport.
        val params=view.layoutParams ?: FrameLayout.LayoutParams(width,height)
        if(params.width!=width || params.height!=height) {
            params.width=width;params.height=height;view.layoutParams=params
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        view.layout(0,0,width,height);shadowOf(Looper.getMainLooper()).idle()
    }

    private fun scrollParagraph(label: TextView) {
        val start=SystemClock.uptimeMillis()
        fun touch(action: Int,y: Float) {
            val event=MotionEvent.obtain(start,start+20,action,label.width/2f,y,0)
            try {label.dispatchTouchEvent(event)} finally {event.recycle()}
        }
        touch(MotionEvent.ACTION_DOWN,label.height-2f);touch(MotionEvent.ACTION_MOVE,2f);touch(MotionEvent.ACTION_UP,2f)
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

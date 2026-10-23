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
}

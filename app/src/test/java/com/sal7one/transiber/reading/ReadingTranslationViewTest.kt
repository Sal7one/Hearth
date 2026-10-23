package com.sal7one.transiber.reading

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
}

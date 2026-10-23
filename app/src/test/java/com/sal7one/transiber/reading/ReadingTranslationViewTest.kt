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

package com.sal7one.transiber.reading

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.sal7one.transiber.ocr.*

/** Transparent live replacement layer. Window flags control touch pass-through; the handle is separate. */
internal class ReadingTranslationView(
    context: Context,
    private val onBox: (TranslatedOcrBox) -> Unit,
) : FrameLayout(context) {
    private var boxes=emptyList<TranslatedOcrBox>()
    private var imageWidth=1
    private var imageHeight=1
    private var crop=PixelCrop(0,0,1,1)
    var showOriginal=false
        set(value) { field=value; rebuild() }
    init { setBackgroundColor(Color.TRANSPARENT) }
    fun update(value: List<TranslatedOcrBox>, width: Int=imageWidth, height: Int=imageHeight, area: PixelCrop=crop) {
        if(boxes!=value || imageWidth!=width || imageHeight!=height || crop!=area) {
            boxes=value;imageWidth=width;imageHeight=height;crop=area;rebuild()
        }
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        // Cached translations can arrive before first layout. Adding children inside
        // onSizeChanged leaves them unmeasured until another UI event; defer a traversal.
        post { if(isAttachedToWindow)rebuild() }
    }
    private fun rebuild() {
        removeAllViews()
        if(!showOriginal)boxes.forEach { box ->
            val bounds=OcrPageLayout.box(box.line,crop,imageWidth,imageHeight,width,height,coverEdges=true) ?: return@forEach
            val label=TextView(context).apply {
                text=box.translation
                setTextColor(Color.BLACK);setBackgroundColor(Color.WHITE)
                gravity=Gravity.CENTER; textDirection=View.TEXT_DIRECTION_FIRST_STRONG
                includeFontPadding=false;ellipsize=TextUtils.TruncateAt.END
                val minPx=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,10f,resources.displayMetrics)
                maxLines=maxOf(1,(bounds.height/(minPx*1.25f)).toInt())
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,10,24,1,TypedValue.COMPLEX_UNIT_SP)
                contentDescription="${box.line.text}. ${box.translation}. Open full text and read aloud"
                isFocusable=true
                setOnClickListener {onBox(box)}
            }
            addView(label,LayoutParams(bounds.width,bounds.height).apply {leftMargin=bounds.left;topMargin=bounds.top})
        }
        invalidate()
    }
}

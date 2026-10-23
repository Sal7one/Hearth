package com.sal7one.transiber.reading

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.RectF
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.sal7one.transiber.ocr.*
import com.sal7one.hearth.core.vision.OcrRegion
import com.sal7one.hearth.core.vision.MangaPaint
import com.sal7one.hearth.core.vision.OcrRect
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap

/** Transparent live replacement layer. Window flags control touch pass-through; the handle is separate. */
internal class ReadingTranslationView(
    context: Context,
    private val onBox: (TranslatedOcrBox) -> Unit,
) : FrameLayout(context) {
    private var boxes=emptyList<TranslatedOcrBox>()
    private var regions=emptyList<OcrRegion>()
    private val outline=Paint(Paint.ANTI_ALIAS_FLAG)
    private val labels=LinkedHashMap<TranslatedOcrBox, TextView>()
    private data class PreparedCleanup(val bounds: OcrRect,val image: Bitmap,val background: Int?)
    // Identity keys avoid repeatedly hashing a large immutable RLE mask. The same
    // paint instance is shared by progressive OCR/translation snapshots.
    private val cleanupImages=IdentityHashMap<MangaPaint,PreparedCleanup>()
    private val cleanupPaint=Paint().apply {isFilterBitmap=false}
    private var translatedBounds=emptySet<OcrRect>()
    private var imageWidth=1
    private var imageHeight=1
    private var crop=PixelCrop(0,0,1,1)
    var showOriginal=false
        set(value) { if(field!=value) { field=value; render(true) } }
    init { setBackgroundColor(Color.TRANSPARENT);setWillNotDraw(false) }
    fun updateRegions(value: List<OcrRegion>, width: Int=imageWidth, height: Int=imageHeight, area: PixelCrop=crop) {
        val changed=imageWidth!=width || imageHeight!=height || crop!=area
        regions=value;imageWidth=width;imageHeight=height;crop=area
        if(changed)render(true)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if(showOriginal)return
        val density=resources.displayMetrics.density
        val scale=minOf(width.toFloat()/imageWidth,height.toFloat()/imageHeight)
        val dx=(width-imageWidth*scale)/2;val dy=(height-imageHeight*scale)/2
        // Every mask/patch is prepared once when its immutable paint arrives.
        // Redraws issue one bitmap operation per translated bubble, rather than
        // thousands of Kotlin-to-Canvas calls or allocating pixels inside onDraw.
        boxes.forEach { box -> box.line.region?.paint?.let { paint ->
            val prepared=cleanupImages[paint] ?: return@let
            val r=prepared.bounds
            prepared.background?.let {cleanupPaint.color=it}
            canvas.drawBitmap(prepared.image,null,RectF(dx+(r.x+crop.left)*scale,dy+(r.y+crop.top)*scale,
                dx+(r.right+crop.left)*scale,dy+(r.bottom+crop.top)*scale),if(prepared.background!=null)cleanupPaint else null)
        } }
        regions.forEach { region ->
            val rect=region.bounds
            if(rect in translatedBounds)return@forEach
            val bounds=OcrPageLayout.box(region,crop,imageWidth,imageHeight,width,height) ?: return@forEach
            outline.style=Paint.Style.FILL;outline.color=Color.argb(22,45,212,191)
            canvas.drawRoundRect(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat(),4*density,4*density,outline)
            outline.style=Paint.Style.STROKE;outline.strokeWidth=2*density;outline.color=Color.rgb(45,212,191)
            canvas.drawRoundRect(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat(),4*density,4*density,outline)
        }
    }
    fun update(value: List<TranslatedOcrBox>, width: Int=imageWidth, height: Int=imageHeight, area: PixelCrop=crop) {
        if(boxes!=value || imageWidth!=width || imageHeight!=height || crop!=area) {
            val layoutChanged=imageWidth!=width || imageHeight!=height || crop!=area
            boxes=value;imageWidth=width;imageHeight=height;crop=area;render(layoutChanged)
        }
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        // Cached translations can arrive before first layout. Adding children inside
        // onSizeChanged leaves them unmeasured until another UI event; defer a traversal.
        post { if(isAttachedToWindow)render(true) }
    }
    private fun render(layoutChanged: Boolean) {
        val paints=Collections.newSetFromMap(IdentityHashMap<MangaPaint,Boolean>())
        boxes.mapNotNullTo(paints) {it.line.region?.paint}
        cleanupImages.entries.removeAll { (paint,prepared) -> if(paint !in paints){prepared.image.recycle();true}else false }
        paints.forEach { paint -> if(!cleanupImages.containsKey(paint))cleanupImages[paint]=prepareCleanup(paint) }
        translatedBounds=boxes.map {OcrRect(it.line.x,it.line.y,it.line.width,it.line.height)}.toSet()
        invalidate()
        if(showOriginal) {
            if(labels.isNotEmpty()) { removeAllViews();labels.clear() }
            return
        }
        val wanted=boxes.toSet()
        val old=labels.entries.iterator()
        while(old.hasNext()) {
            val entry=old.next()
            if(entry.key !in wanted) { removeView(entry.value);old.remove() }
        }
        boxes.forEach { box ->
            val bounds=OcrPageLayout.box(box.line,crop,imageWidth,imageHeight,width,height,coverEdges=true)
            if(bounds==null) {
                labels.remove(box)?.let(::removeView)
                return@forEach
            }
            val existing=labels[box]
            if(existing!=null) {
                if(layoutChanged) existing.layoutParams=LayoutParams(bounds.width,bounds.height).apply {leftMargin=bounds.left;topMargin=bounds.top}
                return@forEach
            }
            val label=TextView(context).apply {
                text=box.translation
                setTextColor(box.line.region?.paint?.foreground ?: Color.BLACK)
                setBackgroundColor(if(box.line.region?.paint!=null)Color.TRANSPARENT else Color.WHITE)
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
            labels[box]=label
        }
    }
    private fun prepareCleanup(paint: MangaPaint): PreparedCleanup {
        paint.patch?.let {patch ->
            return PreparedCleanup(patch.bounds,Bitmap.createBitmap(patch.copyPixels(),patch.bounds.width,patch.bounds.height,Bitmap.Config.ARGB_8888),null)
        }
        var left=paint.mask.width;var right=0;var top=paint.mask.height;var bottom=0
        paint.mask.forEachSpan { y,x,length -> left=minOf(left,x);right=maxOf(right,x+length);top=minOf(top,y);bottom=maxOf(bottom,y+1) }
        val bounds=OcrRect(left,top,right-left,bottom-top)
        val bitmap=Bitmap.createBitmap(bounds.width,bounds.height,Bitmap.Config.ALPHA_8)
        val pixels=ByteArray(bitmap.rowBytes*bounds.height)
        paint.mask.forEachSpan { y,x,length ->
            val offset=(y-top)*bitmap.rowBytes+x-left
            pixels.fill(0xff.toByte(),offset,offset+length)
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        return PreparedCleanup(bounds,bitmap,paint.background)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        render(true)
    }
    override fun onDetachedFromWindow() {
        cleanupImages.values.forEach {it.image.recycle()};cleanupImages.clear()
        super.onDetachedFromWindow()
    }
}

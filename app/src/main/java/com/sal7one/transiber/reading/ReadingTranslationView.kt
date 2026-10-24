package com.sal7one.transiber.reading

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import com.sal7one.transiber.ocr.*
import com.sal7one.hearth.core.vision.OcrRegion
import com.sal7one.hearth.core.vision.MangaPaint
import com.sal7one.hearth.core.vision.OcrRect
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class ReadingRenderedLabel(val bounds: PixelCrop,val text: String,val foreground: Int,
    val background: Int,val scrollY: Int)

/** Transparent live replacement layer. Window flags control touch pass-through; the handle is separate. */
internal class ReadingTranslationView(
    context: Context,
    private val onBox: (TranslatedOcrBox) -> Unit,
) : FrameLayout(context) {
    private var boxes=emptyList<TranslatedOcrBox>()
    private var regions=emptyList<OcrRegion>()
    private val outline=Paint(Paint.ANTI_ALIAS_FLAG)
    private data class LabelKey(val x: Int,val y: Int,val width: Int,val height: Int,val source: String,val translation: String)
    private fun TranslatedOcrBox.labelKey()=LabelKey(line.x,line.y,line.width,line.height,line.text,translation)
    private val labels=LinkedHashMap<LabelKey, TextView>()
    private data class PreparedCleanup(val bounds: OcrRect,val image: Bitmap,val background: Int?)
    // Identity keys avoid repeatedly hashing a large immutable RLE mask. The same
    // paint instance is shared by progressive OCR/translation snapshots.
    private val cleanupImages=IdentityHashMap<MangaPaint,PreparedCleanup>()
    private val cleanupPaint=Paint().apply {isFilterBitmap=false}
    private var translatedBounds=emptySet<OcrRect>()
    private var imageWidth=1
    private var imageHeight=1
    private var crop=PixelCrop(0,0,1,1)
    private var style=ReadingBoxStyle.from(ReadingOverlaySettings())
    private val labelBackgrounds=LinkedHashMap<LabelKey,Int>()
    private val genericCleanup=LinkedHashMap<TranslatedOcrBox,Pair<PixelCrop,Int>>()
    var sourceColours: ReadingColourSamples?=null
        set(value) {if(field!==value){field=value;render()}}
    fun setSourceImage(bitmap: Bitmap) {sourceColours=ReadingColourSamples.from(bitmap)}
    fun applyAppearance(settings: ReadingOverlaySettings) {
        val next=ReadingBoxStyle.from(settings)
        if(style==next)return
        style=next
        textSizeSp=next.textSizeSp
        render()
    }
    var textSizeSp=20f
        set(value) {
            val bounded=value.takeIf {it.isFinite()}?.coerceIn(14f,34f) ?: 20f
            if(field==bounded)return
            field=bounded
            style=style.copy(textSizeSp=bounded)
            render()
        }
    var showOriginal=false
        set(value) { if(field!=value) { field=value; render() } }
    init { setBackgroundColor(Color.TRANSPARENT);setWillNotDraw(false) }
    fun updateRegions(value: List<OcrRegion>, width: Int=imageWidth, height: Int=imageHeight, area: PixelCrop=crop) {
        val changed=imageWidth!=width || imageHeight!=height || crop!=area
        regions=value;imageWidth=width;imageHeight=height;crop=area
        if(changed)render()
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
        genericCleanup.values.forEach {(bounds,colour) ->
            cleanupPaint.color=colour
            canvas.drawRect(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat(),cleanupPaint)
        }
        boxes.forEach { box -> box.line.region?.paint?.takeIf {style.replaceText}?.let { paint ->
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
            boxes=value;imageWidth=width;imageHeight=height;crop=area;render()
        }
    }
    /**
     * Source-image pixels changed by this layer, independent of its screen scale.
     * A cleanup mask/patch can extend well beyond the OCR line or label. Excluding
     * only text boxes makes our own cleanup look like another page scrolling.
     * Visibility is controlled by the service, which also retains disappearance masks.
     */
    fun motionExclusions(): List<PixelCrop> {
        if(showOriginal || imageWidth<=0 || imageHeight<=0)return emptyList()
        val excluded=LinkedHashSet<PixelCrop>()
        labels.values.mapNotNullTo(excluded) {label ->
            val bounds=label.layoutParams as LayoutParams
            viewBoundsToSource(PixelCrop(bounds.leftMargin,bounds.topMargin,
                bounds.leftMargin+bounds.width,bounds.topMargin+bounds.height),1f)
        }
        genericCleanup.values.forEach {(bounds,_) ->viewBoundsToSource(bounds,1f)?.let(excluded::add)}
        regions.forEach {region ->
            if(region.bounds !in translatedBounds) {
                OcrPageLayout.box(region,crop,imageWidth,imageHeight,width,height)
                    ?.let {bounds->viewBoundsToSource(bounds,resources.displayMetrics.density+2f)}
                    ?.let(excluded::add)
            }
        }
        cleanupImages.values.forEach {prepared ->sourceBounds(prepared.bounds)?.let(excluded::add)}
        return excluded.toList()
    }
    /**
     * onDraw rounds the fitted region before stroking it. The stroke then extends
     * half its width past that rounded edge, with an antialias fringe; capture can
     * resample one more physical pixel. Exclude those actual View pixels before
     * transforming back, including any letterbox offset, to avoid self-motion.
     */
    private fun viewBoundsToSource(bounds: PixelCrop,fringe: Float): PixelCrop? {
        if(width<=0 || height<=0)return null
        val scale=minOf(width.toDouble()/imageWidth,height.toDouble()/imageHeight)
        val dx=(width-imageWidth*scale)/2
        val dy=(height-imageHeight*scale)/2
        return PixelCrop(
            floor((bounds.left-fringe-dx)/scale).toInt().coerceIn(0,imageWidth),
            floor((bounds.top-fringe-dy)/scale).toInt().coerceIn(0,imageHeight),
            ceil((bounds.right+fringe-dx)/scale).toInt().coerceIn(0,imageWidth),
            ceil((bounds.bottom+fringe-dy)/scale).toInt().coerceIn(0,imageHeight),
        ).takeIf {it.width>0 && it.height>0}
    }
    private fun sourceBounds(bounds: OcrRect): PixelCrop? {
        if(imageWidth<=0 || imageHeight<=0)return null
        val left=(bounds.x.toLong()+crop.left).coerceIn(0,imageWidth.toLong()).toInt()
        val top=(bounds.y.toLong()+crop.top).coerceIn(0,imageHeight.toLong()).toInt()
        val right=(bounds.right+crop.left).coerceIn(0,imageWidth.toLong()).toInt()
        val bottom=(bounds.bottom+crop.top).coerceIn(0,imageHeight.toLong()).toInt()
        return PixelCrop(left,top,right,bottom).takeIf {it.width>0 && it.height>0}
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        // Cached translations can arrive before first layout. Adding children inside
        // onSizeChanged leaves them unmeasured until another UI event; defer a traversal.
        post { if(isAttachedToWindow)render() }
    }
    private fun render() {
        val paints=Collections.newSetFromMap(IdentityHashMap<MangaPaint,Boolean>())
        if(style.replaceText)boxes.mapNotNullTo(paints) {it.line.region?.paint}
        cleanupImages.entries.removeAll { (paint,prepared) -> if(paint !in paints){prepared.image.recycle();true}else false }
        paints.forEach { paint -> if(!cleanupImages.containsKey(paint))cleanupImages[paint]=prepareCleanup(paint) }
        translatedBounds=boxes.map {OcrRect(it.line.x,it.line.y,it.line.width,it.line.height)}.toSet()
        invalidate()
        genericCleanup.clear()
        if(showOriginal) {
            if(labels.isNotEmpty()) { removeAllViews();labels.clear();labelBackgrounds.clear() }
            return
        }
        // Paint/cleanup and confidence can refine independently of translated text.
        // Keep the reader's existing view while updating those drawing details.
        val wanted=boxes.map {it.labelKey()}.toSet()
        val old=labels.entries.iterator()
        while(old.hasNext()) {
            val entry=old.next()
            if(entry.key !in wanted) { removeView(entry.value);labelBackgrounds.remove(entry.key);old.remove() }
        }
        val occupied=mutableListOf<PixelCrop>()
        boxes.forEach { box ->
            val key=box.labelKey()
            val line=if(style.replaceText)box.line else box.line.copy(region=null)
            val sourceBounds=OcrPageLayout.box(line,crop,imageWidth,imageHeight,width,height,coverEdges=true)
            if(sourceBounds==null) {
                labels.remove(key)?.let(::removeView)
                labelBackgrounds.remove(key)
                return@forEach
            }
            val existing=labels[key]
            val label=existing ?: ReadingTranslatedTextView(context).apply {
                text=box.translation
                gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;textDirection=View.TEXT_DIRECTION_FIRST_STRONG
                includeFontPadding=false;ellipsize=null;maxLines=Int.MAX_VALUE
                setHorizontallyScrolling(false)
                val padding=(3*resources.displayMetrics.density).roundToInt()
                setPadding(padding,padding,padding,padding)
                isVerticalScrollBarEnabled=true;overScrollMode=View.OVER_SCROLL_NEVER
                isFocusable=true
            }
            label.contentDescription="${box.line.text}. ${box.translation}. Open full text and read aloud"
            label.setOnClickListener {onBox(box)}
            val sampled=if(style.replaceText)box.line.region?.paint?.let {
                ReadingSampledColours(it.background,it.foreground)
            } ?: sourceColours?.sample(PixelCrop(box.line.x,box.line.y,box.line.x+box.line.width,box.line.y+box.line.height)) else null
            val foreground=sampled?.foreground ?: style.foreground
            val rawBackground=sampled?.background ?: style.background
            // Manga cleanup already reconstructs only the glyph mask/patch. A solid
            // label would hide that reconstruction and paint across the bubble's art.
            val background=if(style.replaceText && box.line.region?.paint!=null)Color.TRANSPARENT else
                Color.argb(if(style.replaceText)255 else if(rawBackground ushr 24==0)0 else (255*style.opacity/100),
                    Color.red(rawBackground),Color.green(rawBackground),Color.blue(rawBackground))
            label.setTextColor(foreground);label.setBackgroundColor(background);labelBackgrounds[key]=background
            // Erase the original location independently of a label's size or
            // overlap-avoiding placement. Missing sampling uses the saved palette.
            if(style.replaceText && box.line.region?.paint==null)genericCleanup[box]=sourceBounds to background
            val minPx=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,14f,resources.displayMetrics)
            val maxPx=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,(24f*textSizeSp/20f).coerceAtLeast(14f),resources.displayMetrics)
            // A tiny icon/label needs one readable glyph, not a wide article panel.
            val minimumWidth=ceil(maxPx).toInt()+label.paddingLeft+label.paddingRight
            val requestedWidth=ReadingBoxLayout.scaled(maxOf(sourceBounds.width,minimumWidth),style.widthPercent)
                .coerceIn(maxOf(1,minOf(width,minimumWidth)),maxOf(1,width))
            val availableWidth=maxOf(1,requestedWidth-label.paddingLeft-label.paddingRight)
            fun textHeight(size: Float): Int {
                val paint=TextPaint(label.paint).apply {textSize=size}
                return StaticLayout.Builder.obtain(label.text,0,label.text.length,paint,availableWidth)
                    .setIncludePad(false).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).build().height+label.paddingTop+label.paddingBottom
            }
            val minimumHeight=ceil(minPx*1.3f).toInt()+label.paddingTop+label.paddingBottom
            val scaledHeight=ReadingBoxLayout.scaled(maxOf(sourceBounds.height,minimumHeight),style.heightPercent)
                .coerceIn(maxOf(1,minOf(height,minimumHeight)),maxOf(1,height))
            // Modest automatic growth helps short translations. Long paragraphs stay
            // compact and scroll; the user's height slider controls how much is visible.
            val allowance=minOf(maxOf(minimumHeight,scaledHeight/2),maxOf(minimumHeight,height/5))
            val requestedHeight=maxOf(scaledHeight,minOf(textHeight(minPx),scaledHeight+allowance))
                .coerceAtMost(maxOf(1,height))
            val bounds=ReadingBoxLayout.place(sourceBounds,requestedWidth,requestedHeight,width,height,occupied)
                ?: return@forEach
            occupied+=bounds
            var size=maxPx
            // Once scrolling is necessary, the chosen font remains meaningful.
            // Only a short label which can fit at a readable size is fitted down.
            if(textHeight(minPx)<=bounds.height)
                while(size>minPx && textHeight(size)>bounds.height)size=maxOf(minPx,size-resources.displayMetrics.scaledDensity)
            if(existing!=null)(label as ReadingTranslatedTextView).preserveScrollForLayout()
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX,size)
            label.breakStrategy=Layout.BREAK_STRATEGY_HIGH_QUALITY
            val params=LayoutParams(bounds.width,bounds.height).apply {leftMargin=bounds.left;topMargin=bounds.top}
            if(existing==null)addView(label,params) else {
                val previous=label.layoutParams as LayoutParams
                if(previous.width!=params.width || previous.height!=params.height ||
                    previous.leftMargin!=params.leftMargin || previous.topMargin!=params.topMargin)
                    label.layoutParams=params
            }
            labels[key]=label
        }
    }
    fun renderedLabels(): List<ReadingRenderedLabel> = labels.map {(box,label) ->
        val bounds=label.layoutParams as LayoutParams
        ReadingRenderedLabel(PixelCrop(bounds.leftMargin,bounds.topMargin,bounds.leftMargin+bounds.width,bounds.topMargin+bounds.height),
            label.text.toString(),label.currentTextColor,labelBackgrounds[box] ?: Color.TRANSPARENT,label.scrollY)
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
        return PreparedCleanup(bounds,bitmap,paint.background or Color.BLACK)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        render()
    }
    override fun onDetachedFromWindow() {
        cleanupImages.values.forEach {it.image.recycle()};cleanupImages.clear()
        super.onDetachedFromWindow()
    }
}

/** A swipe inside a large paragraph is not a request to open the full-text controls. */
private class ReadingTranslatedTextView(context: Context) : TextView(context) {
    private val touchSlop=ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX=0f
    private var downY=0f
    private var lastY=0f
    private var dragged=false
    private var readerScrollY: Int?=null

    fun preserveScrollForLayout() {if(readerScrollY==null)readerScrollY=scrollY}
    private fun restoreScroll() {
        if(layout==null || height<=0)return
        readerScrollY?.let {
            val clamped=it.coerceIn(0,maximumScroll())
            scrollTo(0,clamped);readerScrollY=clamped
        }
    }
    private fun maximumScroll()=maxOf(0,(layout?.height ?: 0)+paddingTop+paddingBottom-height)
    override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
        super.onLayout(changed,left,top,right,bottom)
        restoreScroll()
    }
    override fun onPreDraw(): Boolean {
        val ready=super.onPreDraw()
        // TextView can bring the first line into view on more than one pre-draw.
        // The last deliberate reader position remains authoritative across frames.
        restoreScroll()
        return ready
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {readerScrollY=scrollY;downX=event.x;downY=event.y;lastY=event.y;dragged=false}
            MotionEvent.ACTION_CANCEL -> {dragged=false;return super.onTouchEvent(event)}
            MotionEvent.ACTION_MOVE,MotionEvent.ACTION_POINTER_DOWN -> {
                val dx=event.x-downX;val dy=event.y-downY
                if(!dragged && (event.actionMasked==MotionEvent.ACTION_POINTER_DOWN || dx*dx+dy*dy>touchSlop*touchSlop)) {
                    dragged=true
                    val cancel=MotionEvent.obtain(event).apply {action=MotionEvent.ACTION_CANCEL}
                    try {super.onTouchEvent(cancel)} finally {cancel.recycle()}
                }
                if(dragged) {
                    val next=((readerScrollY ?: scrollY)+lastY-event.y).roundToInt().coerceIn(0,maximumScroll())
                    scrollTo(0,next);readerScrollY=next
                    lastY=event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> if(dragged) {dragged=false;return true}
        }
        return super.onTouchEvent(event)
    }
}

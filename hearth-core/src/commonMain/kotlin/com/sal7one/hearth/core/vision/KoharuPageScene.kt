/*
 * Scene hierarchy and mask containment adapted from koharu-rs/koharu 73abcaf,
 * Copyright (c) 2025-2026 Mayo Takanashi and Koharu contributors, MIT.
 * Full license: assets/licenses/ocr/koharu-MIT.txt / THIRD_PARTY_NOTICES.md.
 */
package com.sal7one.hearth.core.vision

import kotlin.math.*

/** Immutable, bounded page-space RLE mask. No full-page bitmap is retained per instance. */
class ComicMask(spans: IntArray, val width: Int, val height: Int) {
    private val runs = spans.copyOf()
    val spanCount get() = runs.size/3
    val area: Long
    init {
        require(width in 1..2048 && height in 1..2048 && runs.size in 3..3_000_000 && runs.size%3 == 0) { "Invalid comic mask size" }
        var previousY = -1; var previousRight = 0; var sum = 0L
        for(i in runs.indices step 3) {
            val y=runs[i];val x=runs[i+1];val length=runs[i+2]
            require(y in 0 until height && x >= 0 && length > 0 && x.toLong()+length <= width &&
                (y > previousY || y == previousY && x >= previousRight)) { "Comic mask contains invalid/overlapping spans" }
            previousY=y;previousRight=x+length;sum+=length
        }
        area=sum
    }
    fun forEachSpan(block: (y: Int,x: Int,length: Int)->Unit) { for(i in runs.indices step 3) block(runs[i],runs[i+1],runs[i+2]) }
    fun nativeSpans(): IntArray = runs.copyOf()
    fun contains(x: Int,y: Int): Boolean {
        var low=0;var high=spanCount-1
        while(low<=high) {
            val middle=(low+high).ushr(1);val i=middle*3
            if(y<runs[i] || y==runs[i] && x<runs[i+1])high=middle-1
            else if(y>runs[i] || x>=runs[i+1]+runs[i+2])low=middle+1
            else return true
        }
        return false
    }
    fun intersection(other: ComicMask): Long {
        var a=0;var b=0;var total=0L
        while(a<runs.size && b<other.runs.size) {
            val ay=runs[a];val by=other.runs[b]
            if(ay<by){a+=3;continue};if(by<ay){b+=3;continue}
            val ar=runs[a+1]+runs[a+2];val br=other.runs[b+1]+other.runs[b+2]
            total+=max(0,min(ar,br)-max(runs[a+1],other.runs[b+1]))
            if(ar<=br)a+=3 else b+=3
        }
        return total
    }
    fun clippedTo(other: ComicMask): ComicMask? {
        require(width==other.width && height==other.height)
        val out=ArrayList<Int>();var a=0;var b=0
        while(a<runs.size && b<other.runs.size) {
            val ay=runs[a];val by=other.runs[b]
            if(ay<by){a+=3;continue};if(by<ay){b+=3;continue}
            val ar=runs[a+1]+runs[a+2];val br=other.runs[b+1]+other.runs[b+2]
            val start=max(runs[a+1],other.runs[b+1]);val end=min(ar,br)
            if(end>start){out+=ay;out+=start;out+=end-start}
            if(ar<=br)a+=3 else b+=3
        }
        return out.takeIf {it.isNotEmpty()}?.let {ComicMask(it.toIntArray(),width,height)}
    }
    fun translated(dx: Int,dy: Int,pageWidth: Int,pageHeight: Int): ComicMask {
        val copy=runs.copyOf();for(i in copy.indices step 3){copy[i]+=dy;copy[i+1]+=dx}
        return ComicMask(copy,pageWidth,pageHeight)
    }
    override fun equals(other: Any?) = other is ComicMask && width==other.width && height==other.height && runs.contentEquals(other.runs)
    override fun hashCode() = 31*(31*width+height)+runs.contentHashCode()
    companion object {
        fun union(masks: List<ComicMask>): ComicMask? {
            if(masks.isEmpty())return null
            val width=masks.first().width;val height=masks.first().height
            require(masks.all {it.width==width && it.height==height} && masks.sumOf {it.runs.size.toLong()} <= 3_000_000) { "Comic masks exceed safe page limits" }
            val sorted=masks.flatMap { m -> (m.runs.indices step 3).map {Triple(m.runs[it],m.runs[it+1],m.runs[it+2])} }
                .sortedWith(compareBy<Triple<Int,Int,Int>> {it.first}.thenBy {it.second})
            val out=ArrayList<Int>(sorted.size*3)
            for((y,x,length) in sorted) {
                val i=out.size-3
                if(i>=0 && out[i]==y && x<=out[i+1]+out[i+2])out[i+2]=max(out[i+1]+out[i+2],x+length)-out[i+1]
                else {out+=y;out+=x;out+=length}
            }
            return ComicMask(out.toIntArray(),width,height)
        }
    }
}

/** Flat bubbles use their measured background and native glyph mask, preserving the border/art.
 * Complex artwork retains an ordinary replacement label until a neural inpainter is installed. */
class MangaPatch(val bounds: OcrRect,pixels: IntArray) {
    private val argb=pixels.copyOf()
    init {
        require(bounds.x>=0 && bounds.y>=0 && bounds.width in 1..2048 && bounds.height in 1..2048 &&
            bounds.right<=2048 && bounds.bottom<=2048 && argb.size.toLong()==bounds.area &&
            argb.all {it.ushr(24)==0 || it.ushr(24)==255}) { "Invalid manga cleanup patch" }
    }
    fun copyPixels()=argb.copyOf()
    fun forEachPixel(block: (x: Int,y: Int,color: Int)->Unit) {
        argb.forEachIndexed { i,color -> if(color.ushr(24)!=0)block(bounds.x+i%bounds.width,bounds.y+i/bounds.width,color) }
    }
    fun translated(dx: Int,dy: Int)=MangaPatch(bounds.copy(x=bounds.x+dx,y=bounds.y+dy),argb)
    companion object {
        fun fromNative(row: IntArray): MangaPatch {
            check(row.size>=5 && row[2] in 1..2048 && row[3] in 1..2048 && row.size.toLong()==row[2].toLong()*row[3]+4) { "Invalid native cleanup patch" }
            return MangaPatch(OcrRect(row[0],row[1],row[2],row[3]),row.copyOfRange(4,row.size))
        }
    }
}
data class MangaPaint(val mask: ComicMask, val layout: OcrRect, val background: Int, val foreground: Int, val patch: MangaPatch?=null)
interface MangaPageInpainter {
    fun inpaint(pixels: IntArray,width: Int,height: Int,mask: ComicMask): MangaPatch
    fun cancel()
    fun close()
}

fun OcrRegion.translated(dx: Int,dy: Int,pageWidth: Int,pageHeight: Int): OcrRegion {
    if(dx==0 && dy==0 && (textMask==null || textMask.width==pageWidth && textMask.height==pageHeight))return this
    fun OcrRect.move()=copy(x=x+dx,y=y+dy)
    return copy(bounds=bounds.move(),bubble=bubble?.move(),panel=panel?.move(),
        textMask=textMask?.translated(dx,dy,pageWidth,pageHeight),bubbleMask=bubbleMask?.translated(dx,dy,pageWidth,pageHeight),
        paint=paint?.let {it.copy(mask=it.mask.translated(dx,dy,pageWidth,pageHeight),layout=it.layout.move(),patch=it.patch?.translated(dx,dy))})
}

/** The real full-segmentation path: class masks -> panel/bubble hierarchy -> independent OCR regions. */
object KoharuPageScene {
    fun regions(detections: List<ComicDetection>,width: Int,height: Int): List<OcrRegion> {
        require(detections.size<=128 && width in 1..2048 && height in 1..2048)
        detections.forEach { d -> require(d.label in 0..4 && d.confidence.isFinite() && d.confidence in 0f..1f &&
            d.bounds.x>=0 && d.bounds.y>=0 && d.bounds.width>0 && d.bounds.height>0 && d.bounds.right<=width && d.bounds.bottom<=height &&
            d.mask!=null && d.mask.width==width && d.mask.height==height) { "Invalid Koharu scene element" } }
        val panels=detections.filter {it.label==3};val bubbles=detections.filter {it.label==0}
        fun container(value: ComicDetection,choices: List<ComicDetection>,masks: Boolean): ComicDetection? = choices
            .filter { candidate -> if(masks) candidate.mask!!.intersection(value.mask!!).toDouble()/value.mask.area>=.9
                else intersection(candidate.bounds,value.bounds).toDouble()/value.bounds.area>=.5 }
            .minWithOrNull(compareBy<ComicDetection> {it.bounds.area}.thenBy {it.bounds.y}.thenByDescending {it.bounds.x})
        val bubblePanel=bubbles.associateWith {container(it,panels,false)}
        val grouped=linkedMapOf<ComicDetection,MutableList<ComicDetection>>()
        detections.filter {it.label==1 || it.label==2}.forEach { text ->
            val owner=container(text,bubbles,true)
            grouped.getOrPut(owner ?: text) {mutableListOf()}+=text
        }
        data class Group(val region: OcrRegion,val owner: ComicDetection?,val panel: ComicDetection?)
        val groups=grouped.map { (owner,members) ->
            val bubble=owner.takeIf {it.label==0};val panel=bubble?.let {bubblePanel[it]} ?: container(members.first(),panels,false)
            val bounds=members.map {it.bounds}.reduce(::union)
            val pad=ceil(min(bounds.width,bounds.height)*.12).toInt().coerceIn(2,8)
            val padded=OcrRect(max(0,bounds.x-pad),max(0,bounds.y-pad),
                (min(width.toLong(),bounds.right+pad)-max(0,bounds.x-pad)).toInt(),
                (min(height.toLong(),bounds.bottom+pad)-max(0,bounds.y-pad)).toInt())
            Group(OcrRegion(padded,members.minOf {it.confidence},bubble?.bounds,
                ComicMask.union(members.mapNotNull {it.mask}),bubble?.mask,panel?.bounds),bubble,panel)
        }
        // Koharu's scene has ordered roots (panels or uncontained elements), ordered
        // bubble children and ordered text children. Panel membership comes first.
        val roots=groups.groupBy {it.panel ?: it.owner ?: it.region.bounds}
        fun rootBounds(key: Any): OcrRect = when(key){is ComicDetection->key.bounds;else->key as OcrRect}
        return roots.entries.sortedWith(compareBy<Map.Entry<Any,List<Group>>> {rootBounds(it.key).y}
            .thenByDescending {rootBounds(it.key).x}).flatMap { (_,children) ->
            children.sortedWith(compareBy<Group> {(it.owner?.bounds ?: it.region.bounds).y}
                .thenByDescending {(it.owner?.bounds ?: it.region.bounds).x}).map {it.region}
        }
    }

    fun preparePaint(region: OcrRegion,pixels: IntArray,width: Int,height: Int): OcrRegion {
        val bubble=region.bubble ?: return region;val bubbleMask=region.bubbleMask ?: return region
        val textMask=region.textMask ?: return region
        require(pixels.size==width*height)
        // Bounded samples from actual bubble foreground excluding text and a two-pixel
        // edge band. Median + deviation prevents painting patterned/gradient artwork.
        val channels=Array(3){ArrayList<Int>()};var visited=0
        val step=max(1,ceil(sqrt(bubbleMask.area/16384.0)).toInt())
        bubbleMask.forEachSpan { y,x,length ->
            if(y%step==0)for(px in x until x+length step step) {
                if(!textMask.contains(px,y) && bubbleMask.contains(px-2,y) && bubbleMask.contains(px+2,y) &&
                    bubbleMask.contains(px,y-2) && bubbleMask.contains(px,y+2)) {
                    val color=pixels[y*width+px];for(c in 0..2)channels[c]+=color.ushr(16-c*8) and 255
                    visited++
                }
            }
        }
        if(visited<16)return region
        val medians=channels.map {it.sort();it[it.size/2]}
        val deviations=channels.mapIndexed { i,values -> sqrt(values.sumOf {(it-medians[i]).toDouble().pow(2)}/values.size) }
        val average=deviations.average();val spread=sqrt(deviations.sumOf {(it-average).pow(2)}/3)
        if(deviations.max() >= if(spread>1)7 else 10)return region
        val color=(0xff shl 24) or (medians[0] shl 16) or (medians[1] shl 8) or medians[2]
        val insetX=max(3,(bubble.width*.13).toInt());val insetY=max(3,(bubble.height*.13).toInt())
        val layout=OcrRect(bubble.x+insetX,bubble.y+insetY,max(1,bubble.width-2*insetX),max(1,bubble.height-2*insetY))
        val foreground=if(.2126*medians[0]+.7152*medians[1]+.0722*medians[2]>140)0xff111111.toInt() else 0xfff8f8f8.toInt()
        val targets=textMask.clippedTo(bubbleMask) ?: return region
        return region.copy(paint=MangaPaint(targets,layout,color,foreground))
    }
    private fun intersection(a: OcrRect,b: OcrRect): Long=max(0L,min(a.right,b.right)-max(a.x,b.x))*max(0L,min(a.bottom,b.bottom)-max(a.y,b.y))
    private fun union(a: OcrRect,b: OcrRect): OcrRect {val x=min(a.x,b.x);val y=min(a.y,b.y);return OcrRect(x,y,(max(a.right,b.right)-x).toInt(),(max(a.bottom,b.bottom)-y).toInt())}
}

package com.sal7one.hearth.core.vision

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

data class OcrRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Long get() = x.toLong() + width
    val bottom: Long get() = y.toLong() + height
    val area: Long get() = width.toLong() * height
}
/** RT-DETR export labels: 0 bubble, 1 text inside a bubble, 2 free-standing text. */
data class ComicDetection(val bounds: OcrRect, val label: Int, val confidence: Float, val mask: ComicMask? = null) {
    companion object {
        /** Same bounded ABI on Android and desktop: six header integers, then (y,x,length) spans. */
        fun fromNative(row: IntArray, width: Int, height: Int): ComicDetection {
            check(row.size >= 6 && (row.size-6)%3 == 0 && row.size <= 3_000_006) { "Invalid comic detector mask/result" }
            check(row[5] in 0..4 && row[4] in 0..10000) { "Invalid comic detector class/confidence" }
            return ComicDetection(OcrRect(row[0],row[1],row[2],row[3]),row[5],row[4]/10000f,
                if(row.size == 6) null else ComicMask(row.copyOfRange(6,row.size),width,height))
        }
    }
}
data class OcrRegion(val bounds: OcrRect, val confidence: Float, val bubble: OcrRect? = null,
    val textMask: ComicMask? = null, val bubbleMask: ComicMask? = null, val panel: OcrRect? = null,
    val paint: MangaPaint? = null)
data class PageText(val region: OcrRegion, val text: String)
enum class PageOcrStage { PREVIEW, SEGMENTATION, RECOGNITION, CLEANUP }

interface ComicRegionDetector {
    fun detect(pixels: IntArray, width: Int, height: Int): List<ComicDetection>
    fun cancel()
    fun close()
}
interface MangaRegionReader {
    fun read(pixels: IntArray, width: Int, height: Int): String
    fun cancel()
    fun close()
}
interface PageOcrProgress {
    /** False abandons an obsolete capture between calls, without poisoning a reusable native reader. */
    fun isCurrent(): Boolean = true
    fun stage(stage: PageOcrStage) {}
    fun detected(regions: List<OcrRegion>) {}
    fun recognized(text: List<PageText>) {}
}
interface PageOcrReader {
    fun recognize(pixels: IntArray, width: Int, height: Int, progress: PageOcrProgress): List<PageText>
    fun cancel()
    fun close()
}

/** Portable detection -> bounded text crops -> recognition. Translation remains the caller's existing stage. */
class MangaPageOcr(private val detector: ComicRegionDetector, private val reader: MangaRegionReader,
    private val preview: ComicRegionDetector? = null, private val budgetMs: Long = 45_000,
    private val inpainter: MangaPageInpainter? = null,
    private val timeSource: TimeSource = TimeSource.Monotonic) : PageOcrReader {
    init {require(budgetMs in 1..180_000)}
    override fun recognize(pixels: IntArray, width: Int, height: Int, progress: PageOcrProgress): List<PageText> {
        require(width in 1..2048 && height in 1..2048 && pixels.size == width * height) { "OCR page must be at most 2048 pixels per side" }
        if (!progress.isCurrent()) return emptyList()
        val begun = timeSource.markNow()
        fun checkBudget() {
            check(begun.elapsedNow().inWholeMilliseconds < budgetMs) { "Manga page exceeded its ${budgetMs/1000}-second reading budget. Recognized regions are preserved; draw a smaller area." }
        }
        // Stage notifications may replace a capture. Check again before native
        // work so an obsolete page cannot start another expensive inference.
        fun stage(value: PageOcrStage): Boolean {
            if(!progress.isCurrent())return false
            checkBudget()
            progress.stage(value)
            return progress.isCurrent().also {if(it)checkBudget()}
        }
        if(preview!=null) {
            if(!stage(PageOcrStage.PREVIEW))return emptyList()
            val preliminary=MangaPageLayout.regions(preview.detect(pixels,width,height),width,height)
            if(!progress.isCurrent())return emptyList()
            progress.detected(preliminary)
        }
        if(!stage(PageOcrStage.SEGMENTATION))return emptyList()
        val detections=detector.detect(pixels,width,height)
        if (!progress.isCurrent()) return emptyList()
        checkBudget()
        val candidates=MangaPageLayout.regions(detections,width,height)
        check(candidates.size <= 64) { "Page exceeds 64 text regions. Draw a smaller area." }
        val regions=candidates.map {
            if(!progress.isCurrent())return emptyList()
            KoharuPageScene.preparePaint(it,pixels,width,height)
        }
        if (!progress.isCurrent()) return emptyList()
        progress.detected(regions) // Highlight all geometry before the first slow OCR/translation call.
        if(!stage(PageOcrStage.RECOGNITION))return emptyList()
        val results = ArrayList<PageText>(regions.size)
        for (region in regions) {
            if (!progress.isCurrent()) break
            checkBudget()
            val bounds = region.bounds
            val crop = IntArray(bounds.width * bounds.height)
            repeat(bounds.height) { row ->
                val start = (bounds.y + row) * width + bounds.x
                pixels.copyInto(crop, row * bounds.width, start, start + bounds.width)
            }
            val text = normalizeMangaText(reader.read(crop, bounds.width, bounds.height))
            if (!progress.isCurrent()) break
            if (text.isNotEmpty()) {
                // Koharu's OCR and cleanup are independent page stages. Publish each
                // transcript immediately so translation can proceed while other text
                // is read; background reconstruction must never delay the first line.
                results += PageText(region, text)
                progress.recognized(results.toList())
            }
            // A native call is cooperative and can finish after the budget.
            // Keep its useful transcript, but report the overrun even for the
            // last (or blank) region instead of returning a false fast success.
            if(progress.isCurrent())checkBudget()
        }
        if (!progress.isCurrent()) return results
        val pending=results.indices.filter {results[it].region.paint==null && results[it].region.textMask!=null}
        if(inpainter!=null && pending.isNotEmpty()) {
            checkBudget()
            val masks=pending.map {results[it].region.textMask!!}
            // The native inpainter plans connected, nearby component tiles once for
            // the complete page mask. Per-bubble calls repeat inference for the same
            // context and serialize cleanup ahead of every transcript.
            check(masks.sumOf {it.bounds.area}<=4_194_304) { "Page cleanup exceeds its 16 MiB patch budget. Select a smaller area; completed regions are preserved." }
            val mask=checkNotNull(ComicMask.union(masks))
            if(!stage(PageOcrStage.CLEANUP))return results
            // Koharu fills uniform text backgrounds before neural reconstruction.
            // Use a private context copy: neighboring original lettering must not
            // contaminate a neural tile, and the captured original stays untouched.
            val flat=results.mapNotNull {it.region.paint?.takeIf {paint -> paint.patch==null}}
            val context=if(flat.isEmpty())pixels else pixels.copyOf().also {image ->
                flat.forEach {paint -> paint.mask.forEachSpan {y,x,length -> image.fill(paint.background,y*width+x,y*width+x+length)} }
            }
            if(!progress.isCurrent())return results
            val pagePatch=inpainter.inpaint(context,width,height,mask)
            if (!progress.isCurrent()) return results
            pending.forEach { index ->
                if(!progress.isCurrent())return results
                val recognized=results[index];val region=recognized.region;val textMask=region.textMask!!
                // Publish only this transcript's glyphs. Sharing an unfiltered page
                // patch would erase other bubbles before their translations arrive.
                val patch=pagePatch.clippedTo(textMask)
                var luminance=0.0;var count=0
                patch.forEachPixel {_,_,color -> luminance+=.2126*(color.ushr(16) and 255)+.7152*(color.ushr(8) and 255)+.0722*(color and 255);count++}
                check(count>0) {"Cleanup produced an empty patch"}
                results[index]=recognized.copy(region=region.copy(paint=MangaPaint(textMask,region.bounds,0,
                    if(luminance/count>140)0xff111111.toInt() else 0xfff8f8f8.toInt(),patch)))
            }
            if(progress.isCurrent())progress.recognized(results.toList())
            if(progress.isCurrent())checkBudget()
        }
        return results
    }
    override fun cancel() { try { detector.cancel() } finally { try {preview?.cancel()} finally {try{reader.cancel()}finally{inpainter?.cancel()}} } }
    override fun close() { try { detector.close() } finally { try {preview?.close()} finally {try{reader.close()}finally{inpainter?.close()}} } }
}

/** Koharu normalizes an isolated run of placeholder boxes to the intended ellipsis. */
internal fun normalizeMangaText(value: String): String {
    val text=value.trim();val visible=text.filterNot(Char::isWhitespace)
    return if(visible.length>=2 && visible.all {it=='☐' || it=='□' || it=='▢' || it=='▣' || it=='�'}) "…" else text
}

/** Bubble containment, duplicate suppression and spatial Japanese reading order. No recognition guesses. */
object MangaPageLayout {
    fun regions(detections: List<ComicDetection>, width: Int, height: Int): List<OcrRegion> {
        require(width in 1..2048 && height in 1..2048 && detections.size <= 300) { "Invalid comic detector page/output size" }
        if(detections.any { it.mask != null }) return KoharuPageScene.regions(detections,width,height)
        val valid = detections.mapNotNull { detection ->
            require(detection.label in 0..2 && detection.confidence.isFinite() && detection.confidence in 0f..1f) { "Invalid comic detector class/confidence" }
            clipped(detection.bounds, width, height)?.takeIf { detection.confidence >= .3f }?.let { detection.copy(bounds = it) }
        }.sortedByDescending { it.confidence }
        val bubbles = suppress(valid.filter { it.label == 0 })
        val texts = suppress(valid.filter { it.label != 0 })
        val groups = linkedMapOf<Int, MutableList<ComicDetection>>()
        texts.forEachIndexed { index, text ->
            val owner = if (text.label == 1) bubbles.indices.filter {
                intersection(text.bounds, bubbles[it].bounds).toDouble() / text.bounds.area >= .85
            }.minByOrNull { bubbles[it].bounds.area } else null
            // Different bubbles must never merge merely because their text boxes are adjacent.
            val key = owner ?: (-index - 1)
            groups.getOrPut(key) { mutableListOf() } += text
        }
        val regions = groups.map { (owner, members) ->
            val bubble = bubbles.getOrNull(owner)?.bounds
            val bounds = members.map { it.bounds }.reduce(::union)
            val padding = ceil(min(bounds.width, bounds.height) * .12).toInt().coerceIn(2, 8)
            val limit = bubble ?: OcrRect(0, 0, width, height)
            val left = max(bounds.x - padding, limit.x)
            val top = max(bounds.y - padding, limit.y)
            val right = min(bounds.right + padding, limit.right).toInt()
            val bottom = min(bounds.bottom + padding, limit.bottom).toInt()
            // A detected bubble can contain only part of a text box. Never clip away its glyphs.
            val padded = clipped(union(bounds, OcrRect(left, top, max(1, right-left), max(1, bottom-top))), width, height)!!
            OcrRegion(padded, members.minOf { it.confidence }, bubble)
        }
        return readingOrder(regions)
    }
    private fun clipped(rect: OcrRect, width: Int, height: Int): OcrRect? {
        if (rect.width < 1 || rect.height < 1) return null
        val left = rect.x.coerceIn(0, width); val top = rect.y.coerceIn(0, height)
        val right = rect.right.coerceIn(0, width.toLong()).toInt(); val bottom = rect.bottom.coerceIn(0, height.toLong()).toInt()
        return OcrRect(left, top, right-left, bottom-top).takeIf { it.width >= 3 && it.height >= 3 }
    }
    private fun suppress(values: List<ComicDetection>): List<ComicDetection> {
        val keep = ArrayList<ComicDetection>()
        for (candidate in values) if (keep.none { other ->
            val overlap = intersection(other.bounds, candidate.bounds)
            overlap.toDouble() / (other.bounds.area + candidate.bounds.area - overlap) > .5
        }) keep += candidate
        return keep
    }
    private fun intersection(a: OcrRect, b: OcrRect): Long =
        max(0L, min(a.right, b.right) - max(a.x, b.x)) * max(0L, min(a.bottom, b.bottom) - max(a.y, b.y))
    private fun union(a: OcrRect, b: OcrRect): OcrRect {
        val left = min(a.x, b.x); val top = min(a.y, b.y)
        return OcrRect(left, top, (max(a.right, b.right)-left).toInt(), (max(a.bottom,b.bottom)-top).toInt())
    }
    private fun readingOrder(regions: List<OcrRegion>): List<OcrRegion> {
        if (regions.size < 2) return regions
        // Whitespace cuts approximate row/panel order, then Japanese right-to-left columns.
        // This detector has no panel class; this is explicitly spatial rather than semantic order.
        fun split(vertical: Boolean): Pair<List<OcrRegion>,List<OcrRegion>>? {
            val sorted = regions.sortedBy { if (vertical) it.bounds.x else it.bounds.y }
            var edge = if (vertical) sorted.first().bounds.right else sorted.first().bounds.bottom
            var best = 0L; var at = -1
            for (i in 1 until sorted.size) {
                val start = if (vertical) sorted[i].bounds.x else sorted[i].bounds.y
                val gap = start - edge
                if (gap > best) { best = gap; at = i }
                edge = max(edge, if (vertical) sorted[i].bounds.right else sorted[i].bounds.bottom)
            }
            if (best < 8 || at < 1) return null
            val first = sorted.take(at); val second = sorted.drop(at)
            return if (vertical) second to first else first to second
        }
        val cut = split(false) ?: split(true)
        return if (cut != null) readingOrder(cut.first) + readingOrder(cut.second)
            else regions.sortedWith(compareBy<OcrRegion> { it.bounds.y }.thenByDescending { it.bounds.x })
    }
}

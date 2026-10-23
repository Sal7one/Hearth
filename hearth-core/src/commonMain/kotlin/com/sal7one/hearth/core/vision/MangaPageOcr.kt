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
data class ComicDetection(val bounds: OcrRect, val label: Int, val confidence: Float)
data class OcrRegion(val bounds: OcrRect, val confidence: Float, val bubble: OcrRect? = null)
data class PageText(val region: OcrRegion, val text: String)

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
    fun detected(regions: List<OcrRegion>) {}
    fun recognized(text: List<PageText>) {}
}
interface PageOcrReader {
    fun recognize(pixels: IntArray, width: Int, height: Int, progress: PageOcrProgress): List<PageText>
    fun cancel()
    fun close()
}

/** Portable detection -> bounded text crops -> recognition. Translation remains the caller's existing stage. */
class MangaPageOcr(private val detector: ComicRegionDetector, private val reader: MangaRegionReader) : PageOcrReader {
    override fun recognize(pixels: IntArray, width: Int, height: Int, progress: PageOcrProgress): List<PageText> {
        require(width in 1..2048 && height in 1..2048 && pixels.size == width * height) { "OCR page must be at most 2048 pixels per side" }
        if (!progress.isCurrent()) return emptyList()
        val begun = TimeSource.Monotonic.markNow()
        val regions = MangaPageLayout.regions(detector.detect(pixels, width, height), width, height)
        if (!progress.isCurrent()) return emptyList()
        progress.detected(regions) // Highlight all geometry before the first slow OCR/translation call.
        check(regions.size <= 64) { "Page exceeds 64 text regions. Draw a smaller area." }
        val results = ArrayList<PageText>(regions.size)
        for (region in regions) {
            if (!progress.isCurrent()) break
            check(begun.elapsedNow().inWholeMilliseconds < 45_000) { "Manga page exceeded its 45-second reading budget. Recognized regions are preserved; draw a smaller area." }
            val bounds = region.bounds
            val crop = IntArray(bounds.width * bounds.height)
            repeat(bounds.height) { row ->
                val start = (bounds.y + row) * width + bounds.x
                pixels.copyInto(crop, row * bounds.width, start, start + bounds.width)
            }
            val text = reader.read(crop, bounds.width, bounds.height).trim()
            if (!progress.isCurrent()) break
            if (text.isNotEmpty()) {
                results += PageText(region, text)
                progress.recognized(results.toList())
            }
        }
        return results
    }
    override fun cancel() { try { detector.cancel() } finally { reader.cancel() } }
    override fun close() { try { detector.close() } finally { reader.close() } }
}

/** Bubble containment, duplicate suppression and spatial Japanese reading order. No recognition guesses. */
object MangaPageLayout {
    fun regions(detections: List<ComicDetection>, width: Int, height: Int): List<OcrRegion> {
        require(width in 1..2048 && height in 1..2048 && detections.size <= 300) { "Invalid comic detector page/output size" }
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

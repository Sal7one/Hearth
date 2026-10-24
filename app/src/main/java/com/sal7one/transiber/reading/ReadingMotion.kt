package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil

/** Samples both uncovered content and the reader signal visible through a stable translucent caption. */
internal class ReadingMotion {
    /** Unknown saved values use the safe balanced behavior. */
    var sensitivity: String = "balanced"
    private var previous: IntArray? = null
    private var previousMask = BooleanArray(WIDTH * HEIGHT)
    private data class StablePaint(val grey: IntArray,val visible: BooleanArray,val revision: Long,
                                   val alpha: Float,val width: Int,val height: Int)
    private var stablePaint: StablePaint? = null
    fun reset() {
        previous = null
        previousMask = BooleanArray(WIDTH * HEIGHT)
        stablePaint = null
    }
    /**
     * [excluded] contains hard exclusions such as system bars and the movable handle.
     * [painted] contains caption/cleanup regions, including their disappearance frame.
     * Uncovered pixels retain the ordinary motion policy. Covered pixels provide a
     * second signal only after the owner confirms the same paint is composited and
     * stable on both frames; a new translation must never look like page navigation.
     */
    fun observe(argb: IntArray,imageWidth: Int,imageHeight: Int,excluded: List<PixelCrop>,
                painted: List<PixelCrop> = emptyList(), paintRevision: Long = 0,
                paintStable: Boolean = true, overlayAlpha: Float = .8f): Boolean {
        require(argb.size==WIDTH*HEIGHT && imageWidth>0 && imageHeight>0)
        require(overlayAlpha.isFinite() && overlayAlpha in 0f..1f)
        fun contains(crops: List<PixelCrop>,index: Int): Boolean {
            val x=(index%WIDTH+.5)*imageWidth/WIDTH;val y=(index/WIDTH+.5)*imageHeight/HEIGHT
            return crops.any {x>=it.left && x<it.right && y>=it.top && y<it.bottom}
        }
        val hard=BooleanArray(argb.size) {contains(excluded,it)}
        val covered=BooleanArray(argb.size) {contains(painted,it)}
        val mask=BooleanArray(argb.size) {hard[it] || covered[it]}
        val paintedVisible=BooleanArray(argb.size) {covered[it] && !hard[it]}
        val grey=IntArray(argb.size) {i->val p=argb[i];(((p shr 16)and 255)*3+((p shr 8)and 255)*6+(p and 255))/10}
        val old=previous
        val settings=when(sensitivity) {
            "sensitive" -> Sensitivity(6,16,200,2)
            "stable" -> Sensitivity(12,48,50,4)
            else -> Sensitivity(8,24,100,3)
        }
        val uncoveredMoved=old!=null && detectsMovement(grey,old,settings,argb.size/8) {i->!mask[i]&&!previousMask[i]}
        // At alpha .8 an unchanged translated glyph is constant, while scrolling
        // original text still changes the captured pixel by up to 51 grey levels.
        // Keep a rounding/noise margin: balanced becomes 3, sensitive 2, stable 4.
        val remaining=1f-overlayAlpha
        var baseline=stablePaint
        if(baseline!=null && (baseline.revision!=paintRevision || abs(baseline.alpha-overlayAlpha)>=.0001f ||
                    baseline.width!=imageWidth || baseline.height!=imageHeight)) {
            stablePaint=null;baseline=null
        }
        val eligible=paintStable && remaining>=.02f && paintedVisible.any {it}
        val stableBaseline=baseline
        val coveredMoved=eligible && stableBaseline!=null &&
            detectsMovement(grey,stableBaseline.grey,settings.copy(pixelDifference=maxOf(2,ceil(settings.pixelDifference*remaining*1.5f).toInt())),
                argb.size/16) {i->paintedVisible[i]&&stableBaseline.visible[i]}
        // Options and clean captures temporarily hide identical paint. Preserve its
        // last stable baseline so a completed scroll during that interval is still
        // detected immediately on return, rather than becoming the new baseline.
        if(eligible)stablePaint=StablePaint(grey,paintedVisible,paintRevision,overlayAlpha,imageWidth,imageHeight)
        previous=grey;previousMask=mask
        return uncoveredMoved || coveredMoved
    }
    private inline fun detectsMovement(grey: IntArray,old: IntArray,settings: Sensitivity,minimumVisible: Int,
                                       visibleAt: (Int)->Boolean): Boolean {
        var changed=0;var visible=0
        // A blinking cursor, spinner or a single animated badge is not a new page.
        // Real scrolling/text replacement normally changes several parts of the
        // sampled page. Count spatial coverage as well as individual pixels.
        val changedTiles=IntArray(WIDTH / TILE * (HEIGHT / TILE))
        for(i in grey.indices)if(visibleAt(i)) {
            visible++
            if(abs(grey[i]-old[i])>=settings.pixelDifference) {
                changed++
                changedTiles[(i / WIDTH / TILE) * (WIDTH / TILE) + (i % WIDTH / TILE)]++
            }
        }
        val occupied=changedTiles.count {it>=3}
        return visible>=minimumVisible && changed>=maxOf(settings.minimumPixels,visible/settings.fractionDivisor) &&
            occupied>=settings.minimumTiles
    }
    private data class Sensitivity(val pixelDifference: Int,val minimumPixels: Int,val fractionDivisor: Int,val minimumTiles: Int)
    companion object { const val WIDTH=64;const val HEIGHT=128;private const val TILE=8 }
}

/** Read only the pixels needed for motion detection from a padded RGBA capture plane. */
internal object ReadingMotionSampler {
    fun sample(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): IntArray {
        require(width > 0 && height > 0 && pixelStride == 4 && rowStride >= width * pixelStride)
        require((height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride + 3 < buffer.limit())
        return IntArray(ReadingMotion.WIDTH * ReadingMotion.HEIGHT) { index ->
            val x = ((index % ReadingMotion.WIDTH + .5) * width / ReadingMotion.WIDTH).toInt().coerceAtMost(width - 1)
            val y = ((index / ReadingMotion.WIDTH + .5) * height / ReadingMotion.HEIGHT).toInt().coerceAtMost(height - 1)
            val offset = y * rowStride + x * pixelStride
            val red = buffer.get(offset).toInt() and 255
            val green = buffer.get(offset + 1).toInt() and 255
            val blue = buffer.get(offset + 2).toInt() and 255
            -0x1000000 or (red shl 16) or (green shl 8) or blue
        }
    }
}

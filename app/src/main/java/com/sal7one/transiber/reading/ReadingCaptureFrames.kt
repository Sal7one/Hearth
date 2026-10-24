package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
import java.nio.ByteBuffer
import kotlin.math.abs

/** Classifies actual RGBA buffers while a newly attached projection surface warms up. */
internal class ReadingCaptureFrames {
    enum class Decision { STALE, BLACK, BLANK, READABLE }
    var frames = 0; private set
    var rejectedBlack = 0; private set
    var minimum = 0; private set
    var maximum = 0; private set
    private var lastTimestamp: Long? = null
    private var uniformLuminance: Int? = null
    private var uniformFrames = 0

    /**
     * Pixel padding and pixels outside the requested source area cannot admit a frame.
     * A readable page can proceed immediately; black never becomes a successful page.
     * Three advancing, matching nonblack buffers admit a genuinely blank page. A
     * single blank candidate can also be returned by the owner's bounded timeout.
     */
    fun observe(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
                crop: PixelCrop, timestamp: Long): Decision {
        require(width > 0 && height > 0 && pixelStride == 4 && rowStride >= width * pixelStride)
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= width && crop.bottom <= height && crop.width > 0 && crop.height > 0)
        require((height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride + 3 < buffer.limit())
        frames++
        val previousTimestamp = lastTimestamp
        if(timestamp > 0 && previousTimestamp != null && previousTimestamp > 0 && timestamp <= previousTimestamp)return Decision.STALE
        lastTimestamp = timestamp
        var low = 255
        var high = 0
        repeat(48) { row ->
            val y = crop.top + ((row + .5) * crop.height / 48).toInt().coerceAtMost(crop.height - 1)
            repeat(32) { column ->
                val x = crop.left + ((column + .5) * crop.width / 32).toInt().coerceAtMost(crop.width - 1)
                val offset = y * rowStride + x * pixelStride
                val red = buffer.get(offset).toInt() and 255
                val green = buffer.get(offset + 1).toInt() and 255
                val blue = buffer.get(offset + 2).toInt() and 255
                val luminance = (red * 3 + green * 6 + blue) / 10
                low = minOf(low,luminance); high = maxOf(high,luminance)
            }
        }
        minimum = low; maximum = high
        if(high <= 8) {
            rejectedBlack++;uniformLuminance=null;uniformFrames=0
            return Decision.BLACK
        }
        if(high-low > 8) {uniformLuminance=null;uniformFrames=0;return Decision.READABLE}
        val middle = (low + high) / 2
        uniformFrames = if(uniformLuminance?.let {abs(it-middle)<=3} == true && timestamp>0) uniformFrames+1 else 1
        uniformLuminance = middle
        return if(uniformFrames>=3) Decision.READABLE else Decision.BLANK
    }
    companion object {
        /** Image planes may end immediately after the last actual pixel, before row padding. */
        fun copyOpaqueArgb(buffer: ByteBuffer,width: Int,height: Int,rowStride: Int,pixelStride: Int): IntArray {
            require(width>0 && height>0 && pixelStride==4 && rowStride>=width*pixelStride)
            require((height-1).toLong()*rowStride+(width-1).toLong()*pixelStride+3<buffer.limit())
            return IntArray(width*height) {index ->
                val offset=(index/width)*rowStride+(index%width)*pixelStride
                (0xff shl 24) or ((buffer.get(offset).toInt() and 255) shl 16) or
                    ((buffer.get(offset+1).toInt() and 255) shl 8) or (buffer.get(offset+2).toInt() and 255)
            }
        }
    }

}

package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ReadingCaptureFramesTest {
    private val crop=PixelCrop(0,0,32,48)
    private fun buffer(shade: Int, padding: Int=0): ByteBuffer = ByteBuffer.allocate((32*4+padding)*48).apply {
        repeat(48) {y -> repeat(32) {x ->
            val offset=y*(32*4+padding)+x*4
            put(offset,shade.toByte());put(offset+1,shade.toByte());put(offset+2,shade.toByte());put(offset+3,255.toByte())
        }}
    }
    private fun ReadingCaptureFrames.observe(buffer: ByteBuffer,time: Long,area: PixelCrop=crop,padding: Int=0)=
        observe(buffer,32,48,32*4+padding,4,area,time)

    @Test fun blackInitializationBuffersDoNotPreventTheNextReadableFrame() {
        val frames=ReadingCaptureFrames()
        assertEquals(ReadingCaptureFrames.Decision.BLACK,frames.observe(buffer(0),1))
        assertEquals(ReadingCaptureFrames.Decision.BLACK,frames.observe(buffer(0),2))
        val content=buffer(255).apply {put(0,0);put(1,0);put(2,0)}
        assertEquals(ReadingCaptureFrames.Decision.READABLE,frames.observe(content,3))
        assertEquals(2,frames.rejectedBlack)
        assertEquals(3,frames.frames)
        assertEquals(0,frames.minimum)
        assertEquals(255,frames.maximum)
    }
    @Test fun trueBlackNeverBecomesAnEmptySuccessfulPage() {
        val frames=ReadingCaptureFrames()
        repeat(100) { assertEquals(ReadingCaptureFrames.Decision.BLACK,frames.observe(buffer(0),it.toLong()+1)) }
        assertEquals(100,frames.rejectedBlack)
    }
    @Test fun whiteInitializationWaitsForActualPageContent() {
        val frames=ReadingCaptureFrames()
        assertEquals(ReadingCaptureFrames.Decision.BLANK,frames.observe(buffer(255),1))
        assertEquals(ReadingCaptureFrames.Decision.BLANK,frames.observe(buffer(255),2))
        val content=buffer(255).apply {put(0,0);put(1,0);put(2,0)}
        assertEquals(ReadingCaptureFrames.Decision.READABLE,frames.observe(content,3))
    }
    @Test fun stableBlankWhiteIsAdmittedAfterThreeAdvancingBuffers() {
        val frames=ReadingCaptureFrames()
        assertEquals(ReadingCaptureFrames.Decision.BLANK,frames.observe(buffer(255),1))
        assertEquals(ReadingCaptureFrames.Decision.STALE,frames.observe(buffer(255),1))
        assertEquals(ReadingCaptureFrames.Decision.BLANK,frames.observe(buffer(255),2))
        assertEquals(ReadingCaptureFrames.Decision.READABLE,frames.observe(buffer(255),3))
        assertEquals(0,frames.rejectedBlack)
    }
    @Test fun aBlackBufferResetsTheBlankCandidateSequence() {
        val frames=ReadingCaptureFrames()
        assertEquals(ReadingCaptureFrames.Decision.BLANK,frames.observe(buffer(255),1))
        assertEquals(ReadingCaptureFrames.Decision.BLANK,frames.observe(buffer(255),2))
        assertEquals(ReadingCaptureFrames.Decision.BLACK,frames.observe(buffer(0),3))
        assertEquals(ReadingCaptureFrames.Decision.BLANK,frames.observe(buffer(255),4))
    }
    @Test fun coloredRowPaddingCannotAdmitAnUninitializedBlackPage() {
        val bytes=buffer(0,16).apply {
            repeat(48) { y -> repeat(16) { put(y*144+128+it,255.toByte()) } }
        }
        assertEquals(ReadingCaptureFrames.Decision.BLACK,ReadingCaptureFrames().observe(bytes,1,padding=16))
    }
    @Test fun readableSystemChromeOutsideSelectedAreaDoesNotHideProtectedContent() {
        val bytes=buffer(0).apply {repeat(32) {x ->repeat(4) {y ->val i=y*128+x*4;put(i,255.toByte());put(i+1,255.toByte());put(i+2,255.toByte())}}}
        assertEquals(ReadingCaptureFrames.Decision.BLACK,ReadingCaptureFrames().observe(bytes,1,PixelCrop(0,4,32,48)))
        assertEquals(ReadingCaptureFrames.Decision.READABLE,ReadingCaptureFrames().observe(bytes,1))
    }
    @Test fun absoluteSamplingDoesNotChangeImageBufferPositionOrReadAlphaAsContent() {
        val bytes=buffer(0).apply {position(7)}
        assertEquals(ReadingCaptureFrames.Decision.BLACK,ReadingCaptureFrames().observe(bytes,1))
        assertEquals(7,bytes.position())
    }
    @Test fun screenshotsCanReuseTheRetainedBufferWithoutConsumingItsPosition() {
        val bytes=ByteBuffer.allocate(8).apply {put(byteArrayOf(10,20,30,0,40,50,60,0));position(3)}
        val first=ReadingCaptureFrames.copyOpaqueArgb(bytes,2,1,8,4)
        assertArrayEquals(intArrayOf(0xff0a141e.toInt(),0xff28323c.toInt()),first)
        assertArrayEquals(first,ReadingCaptureFrames.copyOpaqueArgb(bytes,2,1,8,4))
        assertEquals(3,bytes.position())
    }
    @Test fun screenshotCopyHonorsPaddingAndLastRowThatHasNoTrailingPadding() {
        val bytes=ByteBuffer.allocate(12).apply {
            put(byteArrayOf(10,20,30,0,99,99,99,99,40,50,60,0))
        }
        assertArrayEquals(intArrayOf(0xff0a141e.toInt(),0xff28323c.toInt()),
            ReadingCaptureFrames.copyOpaqueArgb(bytes,1,2,8,4))
    }

}

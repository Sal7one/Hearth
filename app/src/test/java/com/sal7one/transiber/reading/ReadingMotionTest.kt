package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
import org.junit.Assert.*
import org.junit.Test

class ReadingMotionTest {
    private fun page(value: Int=255)=IntArray(64*128){grey(value)}
    private fun grey(v: Int)=0xff000000.toInt() or (v shl 16) or (v shl 8) or v
    @Test fun baselineAndUnchangedPageDoNotRetranslate() {
        val m=ReadingMotion();assertFalse(m.observe(page(),64,128,emptyList()));assertFalse(m.observe(page(),64,128,emptyList()))
    }
    @Test fun lowContrastPageMovementIsDetected() {
        val m=ReadingMotion();m.observe(page(),64,128,emptyList())
        val moved=page().apply {for(i in 4000..4100)this[i]=grey(205)}
        assertTrue(m.observe(moved,64,128,emptyList()))
    }
    @Test fun handleAndSystemChromeChangesAreExcluded() {
        val m=ReadingMotion();val mask=listOf(PixelCrop(0,0,64,30))
        m.observe(page(),64,128,mask)
        assertFalse(m.observe(page().apply {for(i in 0 until 64*30)this[i]=grey(0)},64,128,mask))
    }
    @Test fun draggingHandleExcludesBothOldAndNewPositions() {
        val m=ReadingMotion();m.observe(page().apply {for(i in 0 until 640)this[i]=grey(0)},64,128,listOf(PixelCrop(0,0,64,10)))
        assertFalse(m.observe(page().apply {for(i in 640 until 1280)this[i]=grey(0)},64,128,listOf(PixelCrop(0,10,64,20))))
    }
    @Test fun overlayPublicationResetsBaselineInsteadOfCausingFeedbackLoop() {
        val m=ReadingMotion();m.observe(page(),64,128,emptyList());m.reset()
        assertFalse(m.observe(page(80),64,128,emptyList()));assertFalse(m.observe(page(80),64,128,emptyList()))
    }
    @Test fun smallNoiseDoesNotCountAsScrolling() {
        val m=ReadingMotion();m.observe(page(240),64,128,emptyList())
        assertFalse(m.observe(page(235),64,128,emptyList()))
    }
    @Test fun newTextCoversCannotTriggerTheirOwnRetranslationLoop() {
        val m=ReadingMotion()
        val original=page().apply {for(i in 64*40 until 64*50)this[i]=grey(0)}
        m.observe(original,64,128,emptyList())
        val cover=listOf(PixelCrop(0,40,64,50))
        assertFalse(m.observe(page(),64,128,cover))
        assertFalse(m.observe(page(),64,128,cover))
        assertTrue(m.observe(page().apply {for(i in 5000..5100)this[i]=grey(0)},64,128,cover))
    }

}

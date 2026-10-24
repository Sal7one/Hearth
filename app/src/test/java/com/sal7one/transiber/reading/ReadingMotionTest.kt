package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class ReadingMotionTest {
    private fun page(value: Int=255)=IntArray(64*128){grey(value)}
    private fun grey(v: Int)=0xff000000.toInt() or (v shl 16) or (v shl 8) or v
    @Test fun motionSamplerReadsPaddedRgbaPlaneWithoutCopyingFullImage() {
        val stride=ReadingMotion.WIDTH*4+16
        val data=ByteBuffer.allocate(stride*ReadingMotion.HEIGHT)
        val index=19*stride+13*4
        data.put(index,31.toByte());data.put(index+1,62.toByte());data.put(index+2,93.toByte());data.put(index+3,127.toByte())
        val sampled=ReadingMotionSampler.sample(data,ReadingMotion.WIDTH,ReadingMotion.HEIGHT,stride,4)
        assertEquals(0xff1f3e5d.toInt(),sampled[19*ReadingMotion.WIDTH+13])
        assertEquals(0xff000000.toInt(),sampled[0])
        assertThrows(IllegalArgumentException::class.java) {
            ReadingMotionSampler.sample(data,ReadingMotion.WIDTH,ReadingMotion.HEIGHT,ReadingMotion.WIDTH*4-1,4)
        }
    }
    @Test fun baselineAndUnchangedPageDoNotRetranslate() {
        val m=ReadingMotion();assertFalse(m.observe(page(),64,128,emptyList()));assertFalse(m.observe(page(),64,128,emptyList()))
    }
    @Test fun lowContrastPageMovementIsDetected() {
        val m=ReadingMotion();m.observe(page(),64,128,emptyList())
        val moved=page().apply {for(i in 4000..4100)this[i]=grey(205)}
        assertFalse("A short changed band must settle before replacing the page",m.observe(moved,64,128,emptyList()))
        assertEquals(ReadingMotion.ChangeKind.LOCALIZED,m.lastDiagnostics.kind)
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
    @Test fun isolatedSpinnerCannotEraseAndTranslateThePageAgain() {
        val m=ReadingMotion();m.observe(page(),64,128,emptyList())
        val animated=page().apply {for(y in 24 until 32)for(x in 16 until 24)this[y*64+x]=grey(0)}
        repeat(8) {
            assertFalse(m.observe(if(it%2==0)animated else page(),64,128,emptyList()))
        }
    }
    @Test fun sensitivityControlsSmallDistributedTextChangesWithoutCountingSinglePixelNoise() {
        val smallChange=page().apply {
            for(y in 40 until 43)for(x in 8 until 24)this[y*64+x]=grey(220)
        }
        val sensitive=ReadingMotion().apply {sensitivity="sensitive"}
        sensitive.observe(page(),64,128,emptyList())
        assertFalse(sensitive.observe(smallChange,64,128,emptyList()))
        assertTrue(sensitive.observe(smallChange,64,128,emptyList()))
        for(level in listOf("balanced","stable","unknown")) {
            val m=ReadingMotion().apply {sensitivity=level}
            m.observe(page(),64,128,emptyList())
            assertFalse(m.observe(smallChange,64,128,emptyList()))
        }
        val m=ReadingMotion().apply {sensitivity="sensitive"}
        m.observe(page(),64,128,emptyList())
        assertFalse(m.observe(page().apply {this[2000]=grey(0)},64,128,emptyList()))
    }
    @Test fun stableModeStillDetectsARealPageScroll() {
        val m=ReadingMotion().apply {sensitivity="stable"}
        m.observe(page(),64,128,emptyList())
        val moved=page().apply {for(y in 30..50)for(x in 10..50)this[y*64+x]=grey(235)}
        assertTrue(m.observe(moved,64,128,emptyList()))
    }
    @Test fun newTextCoversCannotTriggerTheirOwnRetranslationLoop() {
        val m=ReadingMotion()
        val original=page().apply {for(i in 64*40 until 64*50)this[i]=grey(0)}
        m.observe(original,64,128,emptyList())
        val cover=listOf(PixelCrop(0,40,64,50))
        assertFalse(m.observe(page(),64,128,cover))
        assertFalse(m.observe(page(),64,128,cover))
        val changed=page().apply {for(i in 5000..5100)this[i]=grey(0)}
        assertFalse(m.observe(changed,64,128,cover))
        assertTrue(m.observe(changed,64,128,cover))
    }

    @Test fun horizontalTickerCannotCancelTheFirstPageOrKeepRetranslatingIt() {
        val motion=ReadingMotion()
        val trigger=ReadingTrigger().apply {mode="page";settleMs=500}
        motion.observe(page(),64,128,emptyList())
        // Animation spans many tiles/pixels, but the article outside this band
        // never changed. Its first observed frame must also preserve active work.
        repeat(12) {step ->
            val ticker=page().apply {for(y in 20 until 28)for(x in 0 until 64)
                this[y*64+x]=grey(if((x+step)%8<4)0 else 255)}
            val moved=motion.observe(ticker,64,128,emptyList())
            if(moved)trigger.movement(step*500L)
            assertFalse(moved)
            assertEquals(if(step==0)ReadingMotion.ChangeKind.LOCALIZED else ReadingMotion.ChangeKind.ANIMATION,
                motion.lastDiagnostics.kind)
            assertFalse(trigger.ready(step*500L+500))
        }
    }

    @Test fun oneOffLocalizedTextReplacementIsReportedOnceAfterItSettles() {
        val motion=ReadingMotion();motion.observe(page(),64,128,emptyList())
        val changed=page().apply {for(y in 40 until 45)for(x in 4 until 60)this[y*64+x]=grey(0)}
        assertFalse(motion.observe(changed,64,128,emptyList()))
        assertEquals(ReadingMotion.ChangeKind.LOCALIZED,motion.lastDiagnostics.kind)
        assertTrue(motion.observe(changed,64,128,emptyList()))
        assertEquals(ReadingMotion.ChangeKind.LOCALIZED,motion.lastDiagnostics.kind)
        assertTrue(motion.lastDiagnostics.primary.settled)
        assertFalse(motion.observe(changed,64,128,emptyList()))
        assertEquals(ReadingMotion.ChangeKind.STILL,motion.lastDiagnostics.kind)
    }

    @Test fun realScrollAndBroadReplacementStillInterruptAKnownAnimatedBandImmediately() {
        val motion=ReadingMotion();motion.observe(page(),64,128,emptyList())
        val ticker=page().apply {for(y in 20 until 28)for(x in 0 until 64)this[y*64+x]=grey(0)}
        assertFalse(motion.observe(ticker,64,128,emptyList()))
        assertFalse(motion.observe(page(),64,128,emptyList()))
        assertEquals(ReadingMotion.ChangeKind.ANIMATION,motion.lastDiagnostics.kind)
        val articleMoved=page().apply {for(y in 35 until 110)for(x in 4 until 60)
            if(y%6<3)this[y*64+x]=grey(0)}
        assertTrue(motion.observe(articleMoved,64,128,emptyList()))
        assertEquals(ReadingMotion.ChangeKind.WIDESPREAD,motion.lastDiagnostics.kind)
        assertTrue(motion.observe(page(80),64,128,emptyList()))
        assertEquals(ReadingMotion.ChangeKind.WIDESPREAD,motion.lastDiagnostics.kind)
    }

    @Test fun animationHistoryCannotWalkAcrossThePageOrSwallowASeparateChangedArea() {
        val motion=ReadingMotion();motion.observe(page(),64,128,emptyList())
        fun band(y: Int)=page().apply {for(row in y until y+4)for(x in 0 until 64)this[row*64+x]=grey(0)}
        assertFalse(motion.observe(band(20),64,128,emptyList()))
        assertFalse(motion.observe(band(23),64,128,emptyList()))
        assertTrue("An ignored region stays anchored; it must not grow with moving content",
            motion.observe(band(40),64,128,emptyList()))
        assertEquals(ReadingMotion.ChangeKind.WIDESPREAD,motion.lastDiagnostics.kind)
    }

    @Test fun newOverlayMaskDropsPendingLocalizedMotionInsteadOfTriggeringItsOwnCapture() {
        val motion=ReadingMotion();motion.observe(page(),64,128,emptyList())
        val changed=page().apply {for(y in 40 until 45)for(x in 4 until 60)this[y*64+x]=grey(0)}
        assertFalse(motion.observe(changed,64,128,emptyList()))
        val paint=listOf(PixelCrop(0,35,64,50))
        assertFalse(motion.observe(page(),64,128,paint))
        assertFalse(motion.observe(page(),64,128,paint))
    }

    @Test fun successiveScrollFramesKeepPostponingRecognitionUntilMovementStops() {
        val motion=ReadingMotion();val trigger=ReadingTrigger().apply {mode="page";settleMs=500}
        val base=page().apply {for(y in 30..50)for(x in 10..50)this[y*64+x]=grey(40)}
        motion.observe(base,64,128,emptyList())
        var last=base
        for(step in 1..6) {
            last=page().apply {for(y in 30-step..50-step)for(x in 10..50)this[y*64+x]=grey(40)}
            val time=step*250L
            assertTrue(motion.observe(last,64,128,emptyList()))
            trigger.movement(time)
            assertFalse(trigger.ready(time+249))
        }
        assertFalse(motion.observe(last,64,128,emptyList()))
        assertFalse(trigger.ready(1999));assertTrue(trigger.ready(2000))
    }

}

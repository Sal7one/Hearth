package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/** Composited-frame regressions: an opaque caption mask must not blind a translucent window. */
class ReadingUnderlayMotionTest {
    private val width = ReadingMotion.WIDTH
    private val height = ReadingMotion.HEIGHT
    private val fullPage = listOf(PixelCrop(0, 0, width, height))
    private fun grey(value: Int) = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value
    private fun article(offset: Int = 0) = IntArray(width * height) { index ->
        val y = index / width
        val x = index % width
        if (x in 5..58 && ((y + offset) % 15) in 2..5) 35 else 250
    }
    private fun composite(source: IntArray, revision: Int = 0, alpha: Float = .8f) = IntArray(source.size) { index ->
        // The translated glyphs stay in exactly the same place when the reader scrolls.
        val caption = if (((index / width + revision * 3) % 11) in 3..5 && index % width in 9..54) 20 else 255
        grey((caption * alpha + source[index] * (1f-alpha)).roundToInt())
    }
    private fun ReadingMotion.observePainted(frame: IntArray, revision: Long = 1,
                                            stable: Boolean = true, alpha: Float = .8f,
                                            hard: List<PixelCrop> = emptyList(),
                                            paint: List<PixelCrop> = fullPage) =
        observe(frame, width, height, hard, paint, revision, stable, alpha)

    @Test fun denseTranslatedPageStillDetectsTheArticleScrollingUnderItsStableText() {
        val m = ReadingMotion()
        val first = composite(article())
        assertFalse(m.observePainted(first))
        assertFalse(m.observePainted(first))
        assertTrue("The ordinary path has zero uncovered pixels; underlay motion must still clear stale captions",
            m.observePainted(composite(article(offset = 7))))
        assertEquals(0,m.lastDiagnostics.primary.visible)
        assertFalse(m.lastDiagnostics.primary.moved)
        assertTrue(m.lastDiagnostics.coveredEligible)
        assertTrue(m.lastDiagnostics.covered.moved)
        assertEquals(3,m.lastDiagnostics.covered.pixelDifference)
        assertNotNull(m.lastDiagnostics.covered.changedBounds)
        assertFalse(m.observePainted(composite(article(offset = 7))))
    }

    @Test fun firstCaptionPublicationAndLaterTranslationChangesCannotTriggerThemselves() {
        val m = ReadingMotion()
        val source = article()
        m.observe(source.map(::grey).toIntArray(), width, height, emptyList())
        val firstPaint = composite(source)
        assertFalse(m.observePainted(firstPaint, revision = 1, stable = false))
        assertFalse(m.observePainted(firstPaint, revision = 1, stable = true))
        assertFalse(m.observePainted(firstPaint, revision = 1, stable = true))
        val nextPaint = composite(source, revision = 1)
        assertFalse(m.observePainted(nextPaint, revision = 2, stable = false))
        assertFalse(m.observePainted(nextPaint, revision = 2, stable = true))
        assertFalse(m.observePainted(nextPaint, revision = 2, stable = true))
        assertTrue(m.observePainted(composite(article(7), revision = 1), revision = 2))
    }

    @Test fun changingPaintEpochEvenWithoutAGraceObservationStartsANewBaseline() {
        val m = ReadingMotion()
        m.observePainted(composite(article()))
        m.observePainted(composite(article()))
        val changedFontOrOpacity = composite(article(), revision = 2)
        assertFalse(m.observePainted(changedFontOrOpacity, revision = 2))
        assertFalse(m.observePainted(changedFontOrOpacity, revision = 2))
        assertTrue(m.observePainted(composite(article(7), revision = 2), revision = 2))
    }

    @Test fun temporarilyUnstablePaintDoesNotForgetAScrollCompletedBeforeTheNextStableSample() {
        val m = ReadingMotion()
        m.observePainted(composite(article()))
        assertFalse(m.observePainted(composite(article(3)), stable = false))
        assertTrue(m.observePainted(composite(article(7)), stable = true))
        assertFalse(m.observePainted(composite(article(7)), stable = true))
        assertTrue(m.observePainted(composite(article(10)), stable = true))
    }

    @Test fun openingOptionsAndRestoringIdenticalPaintRetainsThePreOptionsArticleBaseline() {
        val m = ReadingMotion()
        m.observePainted(composite(article()))
        // The reader moved while the options/clean capture temporarily hid the caption.
        val changedSource = article(7).map(::grey).toIntArray()
        assertFalse(m.observePainted(changedSource, paint = emptyList(), stable = false))
        assertFalse(m.observePainted(changedSource, paint = emptyList(), stable = false))
        assertFalse(m.observePainted(composite(article(7)), stable = false))
        assertTrue("First stable restored paint must compare against the article before options opened",
            m.observePainted(composite(article(7)), stable = true))
        assertFalse(m.observePainted(composite(article(7)), stable = true))
    }

    @Test fun changingHandlePositionsRemainExcludedInsideAnOtherwiseFullPageCaption() {
        val m = ReadingMotion()
        val first = composite(article())
        val oldHandle = PixelCrop(0, 0, width, 18)
        val newHandle = PixelCrop(0, 18, width, 36)
        m.observePainted(first, hard = listOf(oldHandle))
        val dragged = first.copyOf().apply {for(y in 0 until 36)for(x in 0 until width)this[y*width+x]=grey(0)}
        assertFalse(m.observePainted(dragged, hard = listOf(newHandle)))
        assertFalse(m.observePainted(dragged, hard = listOf(newHandle)))
        val articleMoved = composite(article(7)).apply {for(y in 18 until 36)for(x in 0 until width)this[y*width+x]=grey(0)}
        assertTrue(m.observePainted(articleMoved, hard = listOf(newHandle)))
    }

    @Test fun captionsAppearingOrDisappearingWithoutAPageChangeAreNotMovement() {
        val m = ReadingMotion()
        val source = article().map(::grey).toIntArray()
        m.observePainted(source, paint = emptyList())
        val painted = composite(article())
        assertFalse(m.observePainted(painted, revision = 1))
        assertFalse(m.observePainted(painted, revision = 1))
        assertFalse(m.observePainted(source, revision = 2, paint = emptyList()))
        assertFalse(m.observePainted(source, revision = 2, paint = emptyList()))
    }

    @Test fun compositorRoundingNoiseInsideCaptionsIsNotAReaderScroll() {
        val m = ReadingMotion()
        val first = composite(article())
        m.observePainted(first)
        val noisy = first.map { pixel -> grey(((pixel and 255)+2).coerceAtMost(255)) }.toIntArray()
        repeat(8) {assertFalse(m.observePainted(if(it%2==0)noisy else first))}
    }

    @Test fun isolatedAnimatedBadgeBehindACaptionDoesNotEraseTheWholePage() {
        val m = ReadingMotion()
        val first = composite(article())
        m.observePainted(first)
        val spinner = first.copyOf().apply {for(y in 24 until 32)for(x in 16 until 24)this[y*width+x]=grey(100)}
        repeat(8) {assertFalse(m.observePainted(if(it%2==0)spinner else first))}
    }

    @Test fun wideAnimatedTickerBehindStableCaptionsPreservesTheArticleButNotARealScroll() {
        val m=ReadingMotion()
        m.observePainted(composite(article()))
        repeat(10) {step ->
            val source=article().apply {for(y in 20 until 28)for(x in 0 until width)
                this[y*width+x]=if((x+step)%8<4)0 else 255}
            assertFalse("First and recurring localized frames must not erase translated body text",
                m.observePainted(composite(source)))
            assertEquals(if(step==0)ReadingMotion.ChangeKind.LOCALIZED else ReadingMotion.ChangeKind.ANIMATION,
                m.lastDiagnostics.kind)
            assertTrue(m.lastDiagnostics.coveredEligible)
        }
        assertTrue(m.observePainted(composite(article(7))))
        assertEquals(ReadingMotion.ChangeKind.WIDESPREAD,m.lastDiagnostics.kind)
    }

    @Test fun aNewPaintRevisionCannotReleaseAnOldLocalizedCandidateAsPageMotion() {
        val m=ReadingMotion()
        val source=article()
        m.observePainted(composite(source))
        val changed=source.copyOf().apply {for(y in 40 until 45)for(x in 4 until 60)this[y*width+x]=0}
        assertFalse(m.observePainted(composite(changed)))
        assertEquals(ReadingMotion.ChangeKind.LOCALIZED,m.lastDiagnostics.kind)
        val nextPaint=composite(changed,revision=1)
        assertFalse(m.observePainted(nextPaint,revision=2,stable=false))
        assertFalse(m.observePainted(nextPaint,revision=2))
        assertFalse(m.observePainted(nextPaint,revision=2))
        assertEquals(ReadingMotion.ChangeKind.STILL,m.lastDiagnostics.kind)
    }

    @Test fun lowContrastOriginalTextIsStillVisibleAtTwentyPercentContribution() {
        val m = ReadingMotion()
        val first = IntArray(width*height) {grey(240)}
        m.observePainted(first)
        // A 20-level source change survives alpha .8 as four composited grey levels.
        val shifted = first.copyOf().apply {for(y in 30..70)for(x in 8..54)this[y*width+x]=grey(236)}
        assertTrue(m.observePainted(shifted))
    }

    @Test fun anActuallyOpaqueLayerCannotClaimItSawTheReaderBehindIt() {
        val m = ReadingMotion()
        val first = composite(article(), alpha = 1f)
        m.observePainted(first, alpha = 1f)
        assertFalse(m.observePainted(composite(article(7), alpha = 1f), alpha = 1f))
        assertFalse(m.observePainted(composite(article(7)), alpha = .8f))
        assertFalse(m.observePainted(composite(article(7)), alpha = .8f))
        assertTrue(m.observePainted(composite(article(10)), alpha = .8f))
    }

    @Test fun resetForgetsThePreviousCaptionAndItsPaintEpoch() {
        val m = ReadingMotion()
        m.observePainted(composite(article()))
        m.reset()
        val changed = composite(article(7), revision = 2)
        assertFalse(m.observePainted(changed, revision = 3))
        assertFalse(m.observePainted(changed, revision = 3))
        assertTrue(m.observePainted(composite(article(10), revision = 2), revision = 3))
    }
}

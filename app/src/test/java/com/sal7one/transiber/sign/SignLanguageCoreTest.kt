package com.sal7one.transiber.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignSequenceBufferTest {

    private fun hand(offset: Float = 0f, confidence: Float = 0.9f) =
        HandLandmarks(FloatArray(HandLandmarks.POINT_COUNT * 3) { i -> offset + (i % 3) * 0.001f },
            isRightHand = true, confidence = confidence)

    private fun frame(ts: Long, offset: Float) =
        FrameObservation(timestampMs = ts, hands = listOf(hand(offset)))

    @Test
    fun motionClearsTheWindowAndStillFramesAccumulate() {
        val buffer = SignSequenceBuffer(capacity = 10)
        buffer.push(frame(0, 0f))
        buffer.push(frame(16, 0f))
        buffer.push(frame(33, 0f))
        assertEquals(3, buffer.window().size)
        // A big jump = transition between signs: window resets.
        buffer.push(frame(50, 0.5f))
        assertEquals(1, buffer.window().size)
        assertTrue(buffer.window().first().hands.isNotEmpty())
    }

    @Test
    fun capacityIsBounded() {
        val buffer = SignSequenceBuffer(capacity = 5)
        repeat(20) { buffer.push(frame(it * 16L, 0f)) }
        assertEquals(5, buffer.window().size)
    }

    @Test
    fun landmarksIndexRoundTrips() {
        val h = HandLandmarks(FloatArray(63) { it * 0.01f }, isRightHand = true, confidence = 1f)
        val (x, y, z) = h[5]
        assertEquals(15 * 0.01f, x, 1e-6f)
        assertEquals(16 * 0.01f, y, 1e-6f)
        assertEquals(17 * 0.01f, z, 1e-6f)
    }
}

class StableSignDetectorTest {

    private val hyp = { label: String -> SignHypothesis(label, 0.9f) }

    @Test
    fun consensusEmitsAfterAgreeingFrames() {
        val detector = StableSignDetector(agreeFrames = 3, minWindowFrames = 8)
        assertNull(detector.onHypothesis(hyp("hello"), windowSize = 10))
        assertNull(detector.onHypothesis(hyp("hello"), windowSize = 10))
        assertEquals("hello", detector.onHypothesis(hyp("hello"), windowSize = 10))
    }

    @Test
    fun noiseNeverEmits() {
        val detector = StableSignDetector(agreeFrames = 3, minWindowFrames = 8)
        // Alternating labels: no 3-run ever forms.
        val labels = listOf("hello", "help", "hello", "help", "hello", "help", "hello")
        labels.forEach { label ->
            assertNull(detector.onHypothesis(hyp(label), windowSize = 10))
        }
    }

    @Test
    fun shortWindowHoldsUntilFull() {
        val detector = StableSignDetector(agreeFrames = 3, minWindowFrames = 8)
        assertNull(detector.onHypothesis(hyp("hello"), windowSize = 3))
        assertNull(detector.onHypothesis(hyp("hello"), windowSize = 7))
        // Full window, three agreeing frames → emits.
        assertNull(detector.onHypothesis(hyp("hello"), windowSize = 8))
        assertNull(detector.onHypothesis(hyp("hello"), windowSize = 8))
        assertEquals("hello", detector.onHypothesis(hyp("hello"), windowSize = 8))
    }

    @Test
    fun repeatNeedsATransitionBetween() {
        val detector = StableSignDetector(agreeFrames = 2, minWindowFrames = 1, repeatAfterDistinct = 1)
        assertNull(detector.onHypothesis(hyp("yes"), windowSize = 5))   // run builds
        assertEquals("yes", detector.onHypothesis(hyp("yes"), windowSize = 5)) // emitted
        // Same label again right after: suppressed (no transition yet).
        assertNull(detector.onHypothesis(hyp("yes"), windowSize = 5))
        assertNull(detector.onHypothesis(hyp("yes"), windowSize = 5))
        // A different sign, then back: allowed.
        assertNull(detector.onHypothesis(hyp("no"), windowSize = 5))
        assertEquals("no", detector.onHypothesis(hyp("no"), windowSize = 5))
        assertNull(detector.onHypothesis(hyp("yes"), windowSize = 5))
        assertEquals("yes", detector.onHypothesis(hyp("yes"), windowSize = 5))
    }

    @Test
    fun nullHypothesisResetsTheRun() {
        val detector = StableSignDetector(agreeFrames = 3, minWindowFrames = 1)
        assertNull(detector.onHypothesis(hyp("go"), windowSize = 10))
        assertNull(detector.onHypothesis(null, windowSize = 10))
        // Three agreeing frames needed AFTER the reset.
        assertNull(detector.onHypothesis(hyp("go"), windowSize = 10))
        assertNull(detector.onHypothesis(hyp("go"), windowSize = 10))
        assertEquals("go", detector.onHypothesis(hyp("go"), windowSize = 10))
    }
}

package com.sal7one.transiber.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the geometric ASL fingerspelling classifier using synthetic
 * hand landmarks. Real landmark geometry (from MediaPipe) has the same
 * spatial relationships these fixtures approximate.
 */
class GeometricFingerspellingTest {
    @Test fun missingCurrentHandNeverReplaysPastPose() {
        val classifier = GeometricFingerspelling()
        val prior = FrameObservation(0, listOf(hand()))
        assertNotNull(classifier.classify(listOf(prior)))
        assertNull(classifier.classify(listOf(prior, FrameObservation(16, emptyList()))))
    }


    private fun hand(
        thumbExt: Boolean = false,
        indexExt: Boolean = false,
        middleExt: Boolean = false,
        ringExt: Boolean = false,
        pinkyExt: Boolean = false,
        thumbIndexPinch: Boolean = false,
        thumbMiddlePinch: Boolean = false,
        indexMiddleSpread: Boolean = false,
    ): HandLandmarks {
        // Wrist at (0.5, 0.9), palm ~0.3 units.
        // Extended fingertips point up (y < 0.4); folded tips stay near
        // their MCP (y ~0.6). Thumb tip reaches sideways when extended.
        val pts = FloatArray(63)
        fun set(i: Int, x: Float, y: Float) {
            pts[i * 3] = x; pts[i * 3 + 1] = y; pts[i * 3 + 2] = 0f
        }

        set(0, 0.5f, 0.9f)                     // wrist
        set(1, 0.4f, 0.8f)                     // thumb CMC
        set(2, if (thumbExt) 0.25f else 0.45f, if (thumbExt) 0.7f else 0.7f) // thumb MCP
        set(3, if (thumbExt) 0.15f else 0.45f, if (thumbExt) 0.65f else 0.7f) // thumb IP
        // Thumb tip: near index tip when pinched, far when extended.
        val thumbTipX = when {
            thumbIndexPinch -> 0.42f
            thumbExt -> 0.1f
            else -> 0.45f
        }
        val thumbTipY = when {
            thumbIndexPinch -> 0.55f
            thumbExt -> 0.6f
            else -> 0.7f
        }
        set(4, thumbTipX, thumbTipY)           // thumb TIP

        // Index (5-8): MCP at y=0.6, tip at y=0.25 when extended, curled
        // back to y=0.6 when folded (tip at or below MCP level).
        val idxMcpX = if (indexMiddleSpread) 0.38f else 0.42f
        set(5, idxMcpX, 0.6f)
        set(6, idxMcpX, if (indexExt) 0.4f else 0.62f)
        set(7, idxMcpX, if (indexExt) 0.3f else 0.62f)
        // Index tip: near thumb when pinched.
        if (thumbIndexPinch) set(8, 0.42f, 0.55f) else set(8, idxMcpX, if (indexExt) 0.25f else 0.62f)

        // Middle (9-12): MCP at y=0.6, spread from index.
        val midMcpX = if (indexMiddleSpread) 0.55f else 0.5f
        set(9, midMcpX, 0.6f)
        set(10, midMcpX, if (middleExt) 0.4f else 0.62f)
        set(11, midMcpX, if (middleExt) 0.3f else 0.62f)
        if (thumbMiddlePinch) set(12, 0.42f, 0.58f) else set(12, midMcpX, if (middleExt) 0.25f else 0.62f)

        // Ring (13-16)
        set(13, 0.6f, 0.6f)
        set(14, 0.6f, if (ringExt) 0.4f else 0.62f)
        set(15, 0.6f, if (ringExt) 0.3f else 0.62f)
        set(16, 0.6f, if (ringExt) 0.25f else 0.62f)

        // Pinky (17-20)
        set(17, 0.7f, 0.62f)
        set(18, 0.7f, if (pinkyExt) 0.45f else 0.64f)
        set(19, 0.7f, if (pinkyExt) 0.35f else 0.64f)
        set(20, 0.7f, if (pinkyExt) 0.3f else 0.64f)

        return HandLandmarks(points = pts, isRightHand = true, confidence = 0.9f)
    }

    private fun classify(
        thumbExt: Boolean = false, indexExt: Boolean = false,
        middleExt: Boolean = false, ringExt: Boolean = false, pinkyExt: Boolean = false,
        thumbIndexPinch: Boolean = false, thumbMiddlePinch: Boolean = false,
        indexMiddleSpread: Boolean = false,
    ): String? = GeometricFingerspelling.classifyStates(
        GeometricFingerspelling.fingerStates(
            hand(thumbExt, indexExt, middleExt, ringExt, pinkyExt,
                 thumbIndexPinch, thumbMiddlePinch, indexMiddleSpread).points
        )
    )

    @Test
    fun letterV() {
        assertEquals("V", classify(indexExt = true, middleExt = true, indexMiddleSpread = true))
    }

    @Test
    fun letterU() {
        assertEquals("U", classify(indexExt = true, middleExt = true, indexMiddleSpread = false))
    }

    @Test
    fun letterW() {
        assertEquals("W", classify(indexExt = true, middleExt = true, ringExt = true,
            indexMiddleSpread = true))
    }

    @Test
    fun letterL() {
        assertEquals("L", classify(thumbExt = true, indexExt = true))
    }

    @Test
    fun letterA() {
        assertEquals("A", classify()) // all folded
    }

    @Test
    fun letterB() {
        assertEquals("B", classify(indexExt = true, middleExt = true, ringExt = true, pinkyExt = true))
    }

    @Test
    fun letterY() {
        assertEquals("Y", classify(thumbExt = true, pinkyExt = true))
    }

    @Test
    fun letterF() {
        assertEquals("F", classify(thumbIndexPinch = true, middleExt = true, ringExt = true, pinkyExt = true))
    }

    @Test
    fun letterI() {
        assertEquals("I", classify(pinkyExt = true))
    }

    @Test
    fun letterE() {
        // All folded, thumb extended
        assertEquals("E", classify(thumbExt = true))
    }

    @Test
    fun unknownReturnsNull() {
        // Thumb+index+middle+ring+pinky all extended = "open hand" (5), not an ASL letter
        assertNull(classify(thumbExt = true, indexExt = true, middleExt = true, ringExt = true, pinkyExt = true))
    }

    @Test
    fun classifierThroughSignClassifierInterface() {
        val classifier = GeometricFingerspelling()
        val obs = listOf(
            FrameObservation(
                timestampMs = 1L,
                hands = listOf(hand(indexExt = true, middleExt = true, indexMiddleSpread = true)),
            ),
        )
        val result = classifier.classify(obs)
        assertNotNull(result)
        assertEquals("V", result!!.label)
        assertTrue(result.confidence > 0.5f)
    }
}

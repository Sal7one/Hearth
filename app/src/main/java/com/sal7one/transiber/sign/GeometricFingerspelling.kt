package com.sal7one.transiber.sign

import kotlin.math.hypot

/**
 * Geometric ASL fingerspelling classifier — the first real engine, ported
 * unchanged from the original Hearth ASL scaffold.
 *
 * From 21 normalized landmarks (MediaPipe order), computes finger
 * extension/fold states and relative geometry to classify a subset of
 * high-frequency ASL letters. No training data needed: these are the
 * letters whose handshapes are separable by finger geometry alone.
 * The [StableSignDetector] handles temporal consensus.
 *
 * MediaPipe landmark indices:
 *   0=wrist, 1-4=thumb (CMC→tip), 5-8=index (MCP→tip),
 *   9-12=middle (MCP→tip), 13-16=ring (MCP→tip), 17-20=pinky (MCP→tip)
 */
internal class GeometricFingerspelling : SignClassifier {

    override fun classify(window: List<FrameObservation>): SignHypothesis? {
        // Classify the current frame only; an absent hand is not the last pose.
        val latest = window.lastOrNull() ?: return null
        val hand = latest.hands.maxByOrNull { it.confidence } ?: return null

        val letter = classifyHand(hand) ?: return null
        return SignHypothesis(label = letter, confidence = 0.75f)
    }

    companion object {

        data class FingerStates(
            val thumbExtended: Boolean,
            val indexExtended: Boolean,
            val middleExtended: Boolean,
            val ringExtended: Boolean,
            val pinkyExtended: Boolean,
            val thumbIndexPinched: Boolean,
            val thumbMiddlePinched: Boolean,
            val indexMiddleSpread: Boolean,
            val middleRingSpread: Boolean,
            val ringPinkySpread: Boolean,
        )

        fun fingerStates(points: FloatArray): FingerStates {
            fun x(i: Int) = points[i * 3]
            fun y(i: Int) = points[i * 3 + 1]

            // Palm size reference: wrist to middle-MCP distance.
            val palmSize = hypot(x(9) - x(0), y(9) - y(0)).coerceAtLeast(0.01f)

            fun dist(a: Int, b: Int) = hypot(x(a) - x(b), y(a) - y(b))
            fun normalizedDist(a: Int, b: Int) = dist(a, b) / palmSize

            // A finger is extended when tip-to-wrist > mcp-to-wrist (tip
            // points away from the palm).
            val indexExt = dist(8, 0) > dist(5, 0) * 1.15f
            val middleExt = dist(12, 0) > dist(9, 0) * 1.15f
            val ringExt = dist(16, 0) > dist(13, 0) * 1.15f
            val pinkyExt = dist(20, 0) > dist(17, 0) * 1.15f

            // Thumb: extended when tip is far from index MCP.
            val thumbExt = normalizedDist(4, 5) > 0.55f

            // Pinch: thumb tip close to finger tip.
            val thumbIndex = normalizedDist(4, 8) < 0.3f
            val thumbMiddle = normalizedDist(4, 12) < 0.3f

            // Spread: adjacent MCP distance is large when fingers apart.
            val idxMidSpread = normalizedDist(5, 9) > 0.28f
            val midRingSpread = normalizedDist(9, 13) > 0.25f
            val ringPinkySpread = normalizedDist(13, 17) > 0.22f

            return FingerStates(
                thumbExtended = thumbExt,
                indexExtended = indexExt,
                middleExtended = middleExt,
                ringExtended = ringExt,
                pinkyExtended = pinkyExt,
                thumbIndexPinched = thumbIndex,
                thumbMiddlePinched = thumbMiddle,
                indexMiddleSpread = idxMidSpread,
                middleRingSpread = midRingSpread,
                ringPinkySpread = ringPinkySpread,
            )
        }

        /**
         * Classifies a single hand into an ASL letter, or null when no
         * confident match. Covers the letters whose shapes are determined
         * by finger extension patterns and pinch/spread geometry.
         */
        fun classifyHand(hand: HandLandmarks): String? {
            val f = fingerStates(hand.points)
            return classifyStates(f)
        }

        fun classifyStates(f: FingerStates): String? {
            // Ordered by specificity (most distinctive first).
            return when {
                // V: index+middle up, spread, others folded, thumb across
                f.indexExtended && f.middleExtended && !f.ringExtended && !f.pinkyExtended &&
                    f.indexMiddleSpread -> "V"

                // W: index+middle+ring up, spread, pinky folded
                f.indexExtended && f.middleExtended && f.ringExtended && !f.pinkyExtended &&
                    (f.indexMiddleSpread || f.middleRingSpread) -> "W"

                // L: thumb+index extended, 90° angle, others folded
                f.thumbExtended && f.indexExtended && !f.middleExtended &&
                    !f.ringExtended && !f.pinkyExtended -> "L"

                // Y: thumb+pinky extended, index+middle+ring folded
                f.thumbExtended && f.pinkyExtended && !f.indexExtended &&
                    !f.middleExtended && !f.ringExtended -> "Y"

                // F: thumb-index pinched, others extended
                f.thumbIndexPinched && f.middleExtended && f.ringExtended &&
                    f.pinkyExtended -> "F"

                // A: fist, thumb alongside (not across fingers)
                !f.indexExtended && !f.middleExtended && !f.ringExtended &&
                    !f.pinkyExtended && !f.thumbExtended -> "A"

                // B: four fingers up, thumb across palm
                !f.thumbExtended && f.indexExtended && f.middleExtended &&
                    f.ringExtended && f.pinkyExtended &&
                    !f.indexMiddleSpread -> "B"

                // D: index up, thumb-middle pinched, ring+pinky folded
                f.indexExtended && f.thumbMiddlePinched && !f.ringExtended &&
                    !f.pinkyExtended -> "D"

                // E: all fingers curled, thumb tucked under
                !f.indexExtended && !f.middleExtended && !f.ringExtended &&
                    !f.pinkyExtended && f.thumbExtended -> "E"

                // I: pinky up, others folded
                !f.indexExtended && !f.middleExtended && !f.ringExtended &&
                    f.pinkyExtended && !f.thumbExtended -> "I"

                // O: all fingertips near thumb tip (circle)
                f.thumbIndexPinched && !f.ringExtended && !f.pinkyExtended -> "O"

                // U: index+middle up, together (not spread)
                f.indexExtended && f.middleExtended && !f.ringExtended &&
                    !f.pinkyExtended && !f.indexMiddleSpread -> "U"

                else -> null
            }
        }
    }
}

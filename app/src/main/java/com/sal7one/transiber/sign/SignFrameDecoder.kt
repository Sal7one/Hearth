package com.sal7one.transiber.sign

import com.sal7one.common_jni.sign.SignHands

/**
 * Defensive bridge from the native hand-landmark result to the recognition
 * core's [FrameObservation]. This package owns its own parsing of the native
 * flat layout so a native layout change is a one-constant flip here instead
 * of a rewire through common-jni.
 *
 * NATIVE FLAT LAYOUT (as of 2026-09):
 * `[count, per hand: palmScore, handPresence, handednessRight, 21×3]` —
 * a 3-scalar header. `handPresence` is sigmoid(hand_flag) from the landmark
 * model; hands below [decodeFlat]'s `minHandScore` are dropped. `handedness`
 * is the sigmoid of the Right-class score (MediaPipe reports handedness from
 * the camera's viewpoint); it is informational — every classifier in this
 * package (geometric and ONNX) is handedness-agnostic.
 */
internal object SignFrameDecoder {

    /** Scalar fields per hand before its 21×3 landmark block. */
    internal const val PER_HAND_HEADER: Int = 3

    /** Flat-result index (within a hand's header) of the palm detector score. */
    internal const val HEADER_INDEX_PALM_SCORE: Int = 0

    /** Flat-result index (within a hand's header) of the hand presence score. */
    internal const val HEADER_INDEX_HAND_SCORE: Int = 1

    /** Flat-result index (within a hand's header) of the handedness score. */
    internal const val HEADER_INDEX_HANDEDNESS: Int = 2

    /** Handedness score at or above this is treated as a right hand. */
    internal const val HANDEDNESS_RIGHT_THRESHOLD: Float = 0.5f

    /** Default handedness when a decoder path has no handedness field. */
    internal const val DEFAULT_IS_RIGHT_HAND: Boolean = true

    /** Maximum hands the native layer will decode per frame. */
    internal const val NATIVE_MAX_HANDS: Int = 4

    private const val POINTS_PER_HAND: Int = HandLandmarks.POINT_COUNT * 3

    /**
     * Decodes the raw native flat result of a frame. Hands below
     * [minHandScore] are dropped (an empty-hand observation breaks a held
     * pose in [SignSequenceBuffer], by design). A truncated buffer is a
     * native contract violation and throws instead of fabricating hands.
     */
    internal fun decodeFlat(
        flat: FloatArray,
        timestampMs: Long,
        minHandScore: Float = 0.5f,
    ): FrameObservation {
        if (flat.isEmpty()) return FrameObservation(timestampMs, emptyList())
        val count = flat[0].toInt().coerceIn(0, NATIVE_MAX_HANDS)
        if (count == 0) return FrameObservation(timestampMs, emptyList())
        val perHand = PER_HAND_HEADER + POINTS_PER_HAND
        check(flat.size >= 1 + count * perHand) {
            "Native hand result is truncated: ${flat.size} floats for $count hands " +
                "(need ${1 + count * perHand} with a $PER_HAND_HEADER-scalar header)"
        }
        val hands = (0 until count).mapNotNull { i ->
            val base = 1 + i * perHand
            val handScore = flat[base + HEADER_INDEX_HAND_SCORE]
            if (handScore < minHandScore) return@mapNotNull null
            val landmarkBase = base + PER_HAND_HEADER
            val isRightHand =
                flat[base + HEADER_INDEX_HANDEDNESS] >= HANDEDNESS_RIGHT_THRESHOLD
            HandLandmarks(
                points = flat.copyOfRange(landmarkBase, landmarkBase + POINTS_PER_HAND),
                isRightHand = isRightHand,
                confidence = handScore,
            )
        }
        return FrameObservation(timestampMs, hands.sortedByDescending { it.confidence })
    }

    /**
     * Converts [SignHands.detect] results for one frame. Same gating and
     * ordering as [decodeFlat]; presence gates the hand and handedness is
     * carried through informationally.
     */
    internal fun fromDetectedHands(
        hands: List<SignHands.DetectedHand>,
        timestampMs: Long,
        minHandScore: Float = 0.5f,
    ): FrameObservation {
        val kept = hands.mapNotNull { hand ->
            if (hand.handPresence < minHandScore) return@mapNotNull null
            HandLandmarks(
                points = hand.landmarks.copyOf(),
                isRightHand = hand.handednessRight >= HANDEDNESS_RIGHT_THRESHOLD,
                confidence = hand.handPresence,
            )
        }.sortedByDescending { it.confidence }
        return FrameObservation(timestampMs, kept)
    }
}

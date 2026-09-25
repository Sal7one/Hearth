package com.sal7one.transiber.sign

/**
 * Sign-language recognition core — the proven pipeline types, ported from the
 * original Hearth ASL scaffold. Pure Kotlin, model-agnostic, unit-tested: the
 * landmark producer (native MediaPipe-style hand landmarks via common-jni, see
 * [SignFrameDecoder]) and the classifier (the geometric engine or the ONNX
 * landmark classifier) plug into these types without touching the consensus
 * logic.
 *
 * Everything here is OFFLINE-FIRST: no network code in this package.
 */

/** One hand's 21 MediaPipe-order landmarks, normalized [0..1] (x, y, z). */
internal data class HandLandmarks(
    val points: FloatArray,
    val isRightHand: Boolean,
    val confidence: Float,
) {
    init {
        require(points.size == POINT_COUNT * 3) {
            "Expected $POINT_COUNT x3 coordinates, got ${points.size}"
        }
    }

    operator fun get(i: Int): Triple<Float, Float, Float> = Triple(
        points[i * 3],
        points[i * 3 + 1],
        points[i * 3 + 2],
    )

    override fun equals(other: Any?): Boolean =
        other is HandLandmarks &&
            points.contentEquals(other.points) &&
            isRightHand == other.isRightHand

    override fun hashCode(): Int = points.contentHashCode() * 31 + isRightHash

    private val isRightHash = if (isRightHand) 1 else 0

    companion object {
        const val POINT_COUNT = 21
    }
}

/** One camera observation: any hands seen this frame + its timestamp. */
internal data class FrameObservation(
    val timestampMs: Long,
    val hands: List<HandLandmarks>,
)

/**
 * Pluggable sign classifier. Given the recent landmark window,
 * returns the best sign hypothesis with confidence, or null.
 */
internal fun interface SignClassifier {
    fun classify(window: List<FrameObservation>): SignHypothesis?
}

internal data class SignHypothesis(val label: String, val confidence: Float)

/**
 * Ring buffer of landmark frames with motion gating: only frames whose
 * hands moved less than [stillnessEpsilon] per landmark accumulate —
 * a held sign stabilizes the window while transitions fall out. Bounded
 * to [capacity] frames; empty-hand observations break a held pose.
 */
internal class SignSequenceBuffer(
    private val capacity: Int = 30,
    private val stillnessEpsilon: Float = 0.02f,
) {
    private val frames = ArrayDeque<FrameObservation>(capacity)
    private var last: FrameObservation? = null

    fun push(frame: FrameObservation) {
        last?.let { previous ->
            if (!isStill(previous, frame)) frames.clear()
        }
        frames.addLast(frame)
        while (frames.size > capacity) frames.removeFirst()
        last = frame
    }

    fun window(): List<FrameObservation> = frames.toList()

    fun isFull(): Boolean = frames.size >= capacity

    fun clear() {
        frames.clear()
        last = null
    }

    private fun isStill(a: FrameObservation, b: FrameObservation): Boolean {
        val handA = a.hands.maxByOrNull { it.confidence } ?: return b.hands.isEmpty()
        val handB = b.hands.maxByOrNull { it.confidence } ?: return false
        var drift = 0f
        for (i in 0 until HandLandmarks.POINT_COUNT) {
            val (ax, ay, _) = handA[i]
            val (bx, by, _) = handB[i]
            drift += kotlin.math.abs(ax - bx) + kotlin.math.abs(ay - by)
        }
        return drift / HandLandmarks.POINT_COUNT <= stillnessEpsilon
    }
}

/**
 * Turns noisy per-frame hypotheses into stable output text.
 *
 * A sign is EMITTED once the same label has been the modal (most common)
 * hypothesis for [agreeFrames] consecutive classifier polls while the
 * buffer holds at least [minWindowFrames] still observations. The same
 * label is not emitted twice in a row unless [repeatAfterDistinct]
 * different labels came between (ASL repeats need a small transition),
 * and [label] is always lowercased gloss text — the fingerspelling
 * classifiers emit letters that are aggregated upstream.
 */
internal class StableSignDetector(
    private val agreeFrames: Int = 3,
    private val minWindowFrames: Int = 8,
    private val repeatAfterDistinct: Int = 1,
) {
    private var lastEmitted: String? = null
    private var labelsSinceEmission = 0
    private val run = ArrayDeque<String>()

    /**
     * Feed one classifier hypothesis (null = no confident reading).
     * Returns the stable label when consensus is reached, else null.
     */
    fun onHypothesis(hypothesis: SignHypothesis?, windowSize: Int): String? {
        if (hypothesis == null || windowSize < minWindowFrames) {
            if (run.isNotEmpty()) run.clear()
            return null
        }
        val label = hypothesis.label
        run.addLast(label)
        while (run.size > agreeFrames) run.removeFirst()

        val modal = run.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val agreed = modal != null &&
            run.size == agreeFrames &&
            run.all { it == modal }

        if (!agreed || modal == lastEmitted && labelsSinceEmission < repeatAfterDistinct) {
            if (label != lastEmitted) labelsSinceEmission++
            return null
        }
        lastEmitted = modal
        labelsSinceEmission = 0
        run.clear()
        return modal
    }

    fun reset() {
        lastEmitted = null
        labelsSinceEmission = 0
        run.clear()
    }
}

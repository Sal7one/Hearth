package com.sal7one.transiber.sign

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fixtures and a fake engine for the ONNX landmark classifier: the
 * normalization math, labels parsing and hypothesis mapping run against
 * plain Kotlin — no native model is loaded in unit tests.
 */
class OnnxLandmarkClassifierTest {

    private class FakeEngine(
        override val inputSize: Int = 63,
        override val classCount: Int = 3,
        private val probs: FloatArray = floatArrayOf(0.1f, 0.7f, 0.2f),
    ) : LandmarkClassifierEngine {
        var lastFeatures: FloatArray? = null
        var closed = false

        override fun classify(features: FloatArray): FloatArray {
            lastFeatures = features.copyOf()
            return probs
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * 21 landmarks: every point near [wrist], point 9 (middle MCP) moved to
     * [middleMcp] so the wrist→middle-MCP palm size is their 3D distance.
     */
    private fun landmarks(
        wrist: Triple<Float, Float, Float> = Triple(0.5f, 0.5f, 0.1f),
        middleMcp: Triple<Float, Float, Float> = Triple(0.5f, 0.2f, 0.1f),
    ): FloatArray {
        val pts = FloatArray(HandLandmarks.POINT_COUNT * 3)
        for (i in 0 until HandLandmarks.POINT_COUNT) {
            pts[i * 3] = wrist.first + i * 0.001f
            pts[i * 3 + 1] = wrist.second + i * 0.001f
            pts[i * 3 + 2] = wrist.third
        }
        pts[9 * 3] = middleMcp.first
        pts[9 * 3 + 1] = middleMcp.second
        pts[9 * 3 + 2] = middleMcp.third
        return pts
    }

    private fun hand(points: FloatArray, confidence: Float = 0.9f) =
        HandLandmarks(points.copyOf(), isRightHand = true, confidence = confidence)

    // =====================================================================
    // Normalization math
    // =====================================================================

    @Test
    fun wristBecomesOriginAndPalmIsTheScale() {
        // palmSize = |(0, -0.3, 0)| = 0.3
        val out = OnnxLandmarkClassifier.normalize(landmarks())!!
        // Wrist (point 0) is exactly zero.
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), out.copyOfRange(0, 3), 1e-6f)
        // Middle MCP (point 9) sits one palm-length away from the wrist.
        assertArrayEquals(floatArrayOf(0f, -1f, 0f), out.copyOfRange(27, 30), 1e-6f)
        // Point 1 was (wrist + 0.001, wrist + 0.001, wrist) → (1/300, 1/300, 0).
        assertArrayEquals(
            floatArrayOf(0.001f / 0.3f, 0.001f / 0.3f, 0f),
            out.copyOfRange(3, 6),
            1e-6f,
        )
    }

    @Test
    fun normalizationIsTranslationInvariant() {
        val base = OnnxLandmarkClassifier.normalize(
            landmarks(Triple(0.1f, 0.1f, 0.0f), Triple(0.1f, 0.4f, 0.0f)),
        )
        val shifted = OnnxLandmarkClassifier.normalize(
            landmarks(Triple(10.1f, -4.9f, 0.25f), Triple(10.1f, -4.6f, 0.25f)),
        )
        assertArrayEquals(base, shifted, 1e-5f)
    }

    @Test
    fun normalizationIsScaleInvariant() {
        // Scale EVERY landmark by 2 around the origin: wrist-relative
        // offsets and the palm size both double, so the normalized vector
        // is unchanged (scaling only wrist/mcp would not be a scaled hand).
        val unit = OnnxLandmarkClassifier.normalize(
            landmarks(Triple(0.2f, 0.8f, 0.0f), Triple(0.2f, 0.4f, 0.0f)),
        )
        val scaled = landmarks(Triple(0.2f, 0.8f, 0.0f), Triple(0.2f, 0.4f, 0.0f))
            .map { it * 2f }
            .toFloatArray()
        val doubled = OnnxLandmarkClassifier.normalize(scaled)
        assertArrayEquals(unit, doubled, 1e-5f)
    }

    @Test
    fun degeneratePalmIsRejectedNotCrashed() {
        val wrist = Triple(0.5f, 0.5f, 0.1f)
        assertNull(OnnxLandmarkClassifier.normalize(landmarks(wrist, wrist)))
        val classifier = OnnxLandmarkClassifier.fromEngine(FakeEngine(), listOf("A", "B", "C"))
        val window = listOf(FrameObservation(1, listOf(hand(landmarks(wrist, wrist)))))
        assertNull(classifier.classify(window))
    }

    // =====================================================================
    // Labels parsing
    // =====================================================================

    @Test
    fun labelsJsonArrayParsesInOrder() {
        assertEquals(
            listOf("A", "B", "ئ"),
            OnnxLandmarkClassifier.parseLabels("""["A", "B", "ئ"]"""),
        )
    }

    @Test
    fun labelsMustBeAJsonArray() {
        assertThrows(IllegalStateException::class.java) {
            OnnxLandmarkClassifier.parseLabels("""{"labels": ["A"]}""")
        }
    }

    @Test
    fun labelsArrayMustNotBeEmpty() {
        assertThrows(IllegalStateException::class.java) {
            OnnxLandmarkClassifier.parseLabels("[]")
        }
    }

    @Test
    fun labelsEntriesMustBeStrings() {
        assertThrows(IllegalStateException::class.java) {
            OnnxLandmarkClassifier.parseLabels("""["A", 3]""")
        }
    }

    // =====================================================================
    // Engine wiring
    // =====================================================================

    @Test
    fun hypothesisTakesArgmaxLabelAndConfidence() {
        val engine = FakeEngine(probs = floatArrayOf(0.1f, 0.7f, 0.2f))
        val classifier = OnnxLandmarkClassifier.fromEngine(engine, listOf("A", "B", "C"))
        val result = classifier.classify(listOf(FrameObservation(1, listOf(hand(landmarks())))))
        assertEquals("B", result!!.label)
        assertEquals(0.7f, result.confidence, 1e-6f)
        // The engine saw exactly the normalized features.
        assertArrayEquals(
            OnnxLandmarkClassifier.normalize(landmarks())!!,
            engine.lastFeatures!!,
            1e-6f,
        )
    }

    @Test
    fun classifyUsesTheLatestFrameMostConfidentHand() {
        val engine = FakeEngine()
        val classifier = OnnxLandmarkClassifier.fromEngine(engine, listOf("A", "B", "C"))
        val older = landmarks(Triple(0.2f, 0.8f, 0f), Triple(0.2f, 0.5f, 0f))
        val weak = landmarks(Triple(0.4f, 0.9f, 0f), Triple(0.4f, 0.6f, 0f))
        val strong = landmarks(Triple(0.6f, 0.9f, 0.1f), Triple(0.6f, 0.6f, 0.1f))
        classifier.classify(
            listOf(
                FrameObservation(1, listOf(hand(older))),
                FrameObservation(2, listOf(hand(weak, confidence = 0.6f), hand(strong, confidence = 0.95f))),
            ),
        )
        assertArrayEquals(
            OnnxLandmarkClassifier.normalize(strong)!!,
            engine.lastFeatures!!,
            1e-6f,
        )
    }

    @Test
    fun emptyWindowOrEmptyHandsReturnNull() {
        val classifier = OnnxLandmarkClassifier.fromEngine(FakeEngine(), listOf("A", "B", "C"))
        assertNull(classifier.classify(emptyList()))
        assertNull(classifier.classify(listOf(FrameObservation(1, emptyList()))))
    }

    @Test
    fun emptyDistributionSurfacesAnError() {
        val classifier =
            OnnxLandmarkClassifier.fromEngine(FakeEngine(classCount = 2, probs = FloatArray(0)), listOf("A", "B"))
        assertThrows(IllegalStateException::class.java) {
            classifier.classify(listOf(FrameObservation(1, listOf(hand(landmarks())))))
        }
    }

    @Test
    fun featureShapeMismatchIsRejectedAtConstruction() {
        assertThrows(IllegalStateException::class.java) {
            OnnxLandmarkClassifier.fromEngine(FakeEngine(inputSize = 60), listOf("A", "B", "C"))
        }
    }

    @Test
    fun classCountMustMatchLabelsCount() {
        assertThrows(IllegalStateException::class.java) {
            OnnxLandmarkClassifier.fromEngine(FakeEngine(classCount = 3), listOf("A", "B"))
        }
        assertThrows(IllegalStateException::class.java) {
            OnnxLandmarkClassifier.fromEngine(FakeEngine(classCount = 2), emptyList())
        }
    }
}

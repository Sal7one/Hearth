package com.sal7one.transiber.sign

import com.sal7one.common_jni.sign.SignClassifier as NativeSignClassifier
import org.json.JSONArray
import java.io.File
import kotlin.math.sqrt

/**
 * Minimal view of a landmark classifier engine. The production
 * implementation wraps common-jni's native classifier; tests supply fakes.
 */
internal interface LandmarkClassifierEngine : AutoCloseable {
    /** Feature count the model expects (must equal 21×3 for hand landmarks). */
    val inputSize: Int

    /** Number of output classes; must equal the labels file's entry count. */
    val classCount: Int

    /** Runs the model; returns the full softmax distribution. */
    fun classify(features: FloatArray): FloatArray
}

/** common-jni native classifier adapted to [LandmarkClassifierEngine]. */
private class NativeLandmarkEngine(model: File, threads: Int) : LandmarkClassifierEngine {
    private val native = NativeSignClassifier(model, threads)

    override val inputSize: Int get() = native.inputSize
    override val classCount: Int get() = native.classCount
    override fun classify(features: FloatArray): FloatArray = native.classify(features)
    override fun close() = native.close()
}

/**
 * ONNX landmark-letters classifier over the camera pipeline's hand
 * landmarks. Implements the core [SignClassifier] interface, so it drops in
 * next to [GeometricFingerspelling]: it classifies the newest frame's most
 * confident hand and leaves temporal consensus to [StableSignDetector].
 *
 * Features are wrist-relative and palm-scale-normalized before inference
 * (see [normalize]). Load errors — unreadable labels JSON, feature/class
 * mismatches, native load failures — propagate as [IllegalStateException]
 * with the real message; a loaded classifier never fabricates output.
 * [classify] returns null only for honest "no reading" cases: no hand in
 * the window, or a degenerate (zero-size) palm.
 *
 * Strings are hardcoded English for now (res/ is owned by another change).
 */
internal class OnnxLandmarkClassifier private constructor(
    private val engine: LandmarkClassifierEngine,
    private val labels: List<String>,
) : SignClassifier, AutoCloseable {

    /**
     * Classifies the latest frame's most confident hand. Returns null when
     * the window has no hand or the palm is degenerate.
     */
    override fun classify(window: List<FrameObservation>): SignHypothesis? {
        val hand = window.lastOrNull()?.hands?.maxByOrNull { it.confidence } ?: return null
        val features = normalize(hand.points) ?: return null
        require(features.size == engine.inputSize) {
            "Landmark classifier expects ${engine.inputSize} features, got ${features.size}"
        }
        val probabilities = engine.classify(features)
        val best = probabilities.indices.maxByOrNull { probabilities[it] }
            ?: throw IllegalStateException("Landmark classifier returned an empty distribution")
        return SignHypothesis(label = labels[best], confidence = probabilities[best])
    }

    override fun close() = engine.close()

    companion object {
        /** MediaPipe landmark index of the wrist (subtracted from all points). */
        const val WRIST_INDEX = 0

        /** MediaPipe landmark index of the middle-finger MCP (palm scale). */
        const val MIDDLE_MCP_INDEX = 9

        /** Palm sizes below this cannot be normalized; the frame is rejected. */
        const val MIN_PALM_SIZE = 1e-6f

        /**
         * Loads a classifier model with its sister labels file
         * (`<model>_labels.json`, UTF-8 JSON array of strings). Any labels
         * or shape problem throws [IllegalStateException] with the real
         * cause; native load failures propagate unchanged.
         */
        fun load(model: File, labelsFile: File, threads: Int = 2): OnnxLandmarkClassifier =
            fromEngine(NativeLandmarkEngine(model, threads), parseLabels(labelsFile.readText(Charsets.UTF_8)))

        /** Validates engine/labels agreement and builds the classifier. */
        internal fun fromEngine(engine: LandmarkClassifierEngine, labels: List<String>): OnnxLandmarkClassifier {
            check(engine.inputSize == HandLandmarks.POINT_COUNT * 3) {
                "Landmark classifier expects ${engine.inputSize} input features, " +
                    "but hand landmarks are ${HandLandmarks.POINT_COUNT * 3}"
            }
            check(labels.isNotEmpty()) { "Landmark classifier labels are empty" }
            check(engine.classCount == labels.size) {
                "Landmark classifier has ${engine.classCount} classes, " +
                    "but the labels file lists ${labels.size}"
            }
            return OnnxLandmarkClassifier(engine, labels)
        }

        /**
         * Wrist-relative, palm-scale normalization. Subtracts landmark 0
         * (wrist) x/y/z from all 21 points, then divides every coordinate
         * by the wrist→middle-MCP distance in 3D. Returns null when the
         * palm size is below [MIN_PALM_SIZE] (degenerate hand).
         */
        internal fun normalize(landmarks: FloatArray): FloatArray? {
            require(landmarks.size == HandLandmarks.POINT_COUNT * 3) {
                "Expected ${HandLandmarks.POINT_COUNT * 3} landmark floats, got ${landmarks.size}"
            }
            fun x(i: Int) = landmarks[i * 3]
            fun y(i: Int) = landmarks[i * 3 + 1]
            fun z(i: Int) = landmarks[i * 3 + 2]
            val dx = x(MIDDLE_MCP_INDEX) - x(WRIST_INDEX)
            val dy = y(MIDDLE_MCP_INDEX) - y(WRIST_INDEX)
            val dz = z(MIDDLE_MCP_INDEX) - z(WRIST_INDEX)
            val palmSize = sqrt(dx * dx + dy * dy + dz * dz)
            if (palmSize < MIN_PALM_SIZE) return null
            val wristX = x(WRIST_INDEX)
            val wristY = y(WRIST_INDEX)
            val wristZ = z(WRIST_INDEX)
            return FloatArray(landmarks.size) { i ->
                when (i % 3) {
                    0 -> (landmarks[i] - wristX) / palmSize
                    1 -> (landmarks[i] - wristY) / palmSize
                    else -> (landmarks[i] - wristZ) / palmSize
                }
            }
        }

        /**
         * Parses a labels file: a UTF-8 JSON array of strings, in output
         * order. Throws [IllegalStateException] with the real cause when
         * the text is not a JSON array, is empty, or holds non-strings.
         */
        internal fun parseLabels(json: String): List<String> {
            val array = try {
                JSONArray(json)
            } catch (e: Exception) {
                throw IllegalStateException("Classifier labels file is not valid JSON: ${e.message}", e)
            }
            check(array.length() > 0) { "Classifier labels array is empty" }
            return (0 until array.length()).map { i ->
                try {
                    array.getString(i)
                } catch (e: Exception) {
                    throw IllegalStateException("Classifier labels entry $i is not a string: ${e.message}", e)
                }
            }
        }
    }
}

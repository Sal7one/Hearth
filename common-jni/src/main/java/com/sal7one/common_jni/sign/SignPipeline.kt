package com.sal7one.common_jni.sign

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * One loaded hand-landmark pipeline (MediaPipe-semantics palm detection +
 * 21-point landmarks as ONNX on the shared ONNX Runtime — no Google
 * runtimes). [detect] is serialized internally by the JVM and externally by
 * the shared inference lease; a closed pipeline throws instead of returning
 * fabricated hands.
 */
class SignHands(directory: File, threads: Int = 2) : AutoCloseable {
    private val handle = AtomicLong(
        SignNative.nativeCreateHandPipeline(directory.absolutePath.toByteArray(Charsets.UTF_8), threads)
    )

    /** Maximum hands the pipeline will decode per frame. */
    var maxHands: Int = 2

    /** Palm detection threshold; 0.5 matches the MediaPipe default. */
    var palmThreshold: Float = 0.5f

    /**
     * Detects hands in a tightly packed RGBA frame ([width] x [height],
     * row-major, direct buffer, at least width*height*4 bytes remaining).
     * Returns hands best-first; empty when no palm passes the threshold or
     * hand presence is below 0.5. Errors throw with the native message.
     */
    @Synchronized
    fun detect(frame: ByteBuffer, width: Int, height: Int): List<DetectedHand> {
        val id = handle.get()
        check(id != 0L) { "Hand pipeline is closed" }
        require(frame.isDirect) { "Frame buffer must be direct" }
        require(frame.remaining() >= width * height * 4) { "Frame buffer too small" }
        val flat = SignNative.nativeDetectHands(id, frame, width, height, palmThreshold, maxHands)
        return Companion.decode(flat)
    }

    override fun close() {
        handle.getAndSet(0).takeIf { it != 0L }?.let(SignNative::nativeDestroyHandPipeline)
    }

    /**
     * One detected hand: 21 MediaPipe-order landmarks (normalized image
     * space, z with MediaPipe's /224/0.4 crop-relative semantics) plus the
     * palm detection score, hand-presence probability and handedness score
     * (probability of the Right class; MediaPipe reports handedness from the
     * camera's viewpoint).
     */
    data class DetectedHand(
        val palmScore: Float,
        val handPresence: Float,
        val handednessRight: Float,
        /** [x0, y0, z0, ..., x20, y20, z20] — MediaPipe landmark order. */
        val landmarks: FloatArray,
    ) {
        init {
            require(landmarks.size == POINT_COUNT * 3) { "Landmark vector must be 63 floats" }
        }

        companion object {
            const val POINT_COUNT = 21
        }
    }

    companion object {
        /**
         * Decodes the native flat result:
         * [count, per hand: palmScore, handPresence, handednessRight,
         *  21 * (x, y, z)].
         */
        fun decode(flat: FloatArray): List<DetectedHand> {
            if (flat.isEmpty()) return emptyList()
            val count = flat[0].toInt().coerceIn(0, 4)
            val perHand = 3 + DetectedHand.POINT_COUNT * 3
            if (flat.size < 1 + count * perHand) {
                throw IllegalStateException("Truncated hand result: ${flat.size} floats")
            }
            return (0 until count).map { i ->
                val base = 1 + i * perHand
                DetectedHand(
                    palmScore = flat[base],
                    handPresence = flat[base + 1],
                    handednessRight = flat[base + 2],
                    landmarks = flat.copyOfRange(base + 3, base + 3 + DetectedHand.POINT_COUNT * 3),
                )
            }
        }

        /** Allocates a reusable direct RGBA frame buffer. */
        fun frameBuffer(width: Int, height: Int): ByteBuffer =
            ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
    }
}

/**
 * One loaded landmark classifier. Input: 63 normalized floats; output: the
 * full softmax distribution (the native side applies softmax to raw logits).
 */
class SignClassifier(path: File, threads: Int = 2) : AutoCloseable {
    private val handle = AtomicLong(
        SignNative.nativeCreateClassifier(path.absolutePath.toByteArray(Charsets.UTF_8), threads)
    )

    val inputSize: Int
    val classCount: Int

    init {
        val shape = SignNative.nativeClassifierShape(handle.get())
        inputSize = shape[0]
        classCount = shape[1]
    }

    /** Requires exactly [inputSize] features; returns softmax probabilities. */
    @Synchronized
    fun classify(features: FloatArray): FloatArray {
        val id = handle.get()
        check(id != 0L) { "Classifier is closed" }
        require(features.size == inputSize) { "Expected $inputSize features" }
        return SignNative.nativeClassify(id, features)
    }

    override fun close() {
        handle.getAndSet(0).takeIf { it != 0L }?.let(SignNative::nativeDestroyClassifier)
    }
}

package com.sal7one.common_jni.sign

import java.nio.ByteBuffer

/**
 * JNI surface for the sign-language native pipeline.
 *
 * - [nativeCreateHandPipeline] loads the two MediaPipe .tflite hand models
 *   (hand_detector + hand_landmarks_detector) over LiteRT.
 * - [nativeDetectHands] runs palm detection + landmark extraction on one
 *   tightly packed RGBA frame (row-major direct ByteBuffer).
 * - [nativeCreateClassifier] loads a landmark classifier ONNX
 *   (input "landmarks" [1,63], output "logits" [1,N], raw logits).
 *
 * Errors throw [IllegalStateException] with the native message — callers
 * surface them, never swallow them into empty results.
 */
internal object SignNative {
    init { System.loadLibrary("common_jni") }

    external fun nativeCreateHandPipeline(dir: ByteArray, threads: Int): Long
    external fun nativeDetectHands(
        handle: Long,
        frame: ByteBuffer,
        width: Int,
        height: Int,
        palmThreshold: Float,
        maxHands: Int,
    ): FloatArray

    external fun nativeDestroyHandPipeline(handle: Long)

    external fun nativeCreateClassifier(path: ByteArray, threads: Int): Long
    external fun nativeClassifierShape(handle: Long): IntArray
    external fun nativeClassify(handle: Long, landmarks: FloatArray): FloatArray
    external fun nativeDestroyClassifier(handle: Long)
}

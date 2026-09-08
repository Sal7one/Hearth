package com.sal7one.common_jni

/**
 * Progress/cancellation boundary for blocking FFmpeg extraction.
 *
 * [onProgress] receives a monotonic value in `0f..1f`. Return `false` to ask
 * the native loop to stop at its next safe packet boundary. Keeping this as a
 * one-method interface makes the native contract reusable from Kotlin, Java,
 * and desktop-facing JNI adapters without coupling it to coroutines.
 */
fun interface AudioExtractionProgressCallback {
    fun onProgress(progress: Float): Boolean
}

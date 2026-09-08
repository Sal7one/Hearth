package com.sal7one.common_jni.marian

/**
 * JNI bindings for the native Marian translation engine.
 *
 * Maps to functions in marian_jni.cpp. Errors are thread-local and surfaced
 * through [nativeGetLastError].
 */
internal object MarianNative {

    /** True when the ONNX Runtime + Marian engine are compiled into this build. */
    external fun nativeRuntimeAvailable(): Boolean

    /** Creates an engine from a model directory; returns 0 on failure. */
    external fun nativeCreate(modelDir: String): Long

    /** Translates one utterance; returns null on failure. */
    external fun nativeTranslate(handle: Long, text: String): String?

    /** Latency of the most recent successful translation, in milliseconds. */
    external fun nativeLastLatencyMs(handle: Long): Long

    external fun nativeRelease(handle: Long)

    external fun nativeGetLastError(): String?

    init {
        System.loadLibrary("common_jni")
    }
}

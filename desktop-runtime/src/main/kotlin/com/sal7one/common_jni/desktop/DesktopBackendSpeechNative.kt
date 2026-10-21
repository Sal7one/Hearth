package com.sal7one.common_jni.desktop

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

/**
 * Generic bridge over a pinned Hearth speech backend dylib.
 *
 * The backend library is loaded and hash-verified by DesktopNativeLibraries first; this
 * JNI surface dlopens the same absolute path and calls the backend's C ABI.
 */
internal object DesktopBackendSpeechNative {
    init { DesktopNativeLibraries.load("hearth_desktop_backend_speech") }
    external fun protocolVersion(): Int
    external fun create(
        library: ByteArray, backendId: ByteArray, entrySymbol: ByteArray, model: ByteArray,
        encoder: ByteArray, decoder: ByteArray, tokenizer: ByteArray, convFrontend: ByteArray, language: ByteArray,
        threads: Int, rightContext: Int, maximumMs: Int, silenceMs: Int, thresholdDb: Float,
    ): Long
    external fun transcribe(handle: Long, pcm: FloatArray): ByteArray
    external fun language(handle: Long): ByteArray
    external fun executionInfo(handle: Long): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

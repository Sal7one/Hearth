package com.sal7one.common_jni.translation

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

/** Desktop-only ABI: protocol 4 adds explicit thread and GPU placement arguments. */
internal object LocalTranslationNative {
    init { DesktopNativeLibraries.load("transiber_translation") }
    external fun promptProtocolVersion(): Int
    external fun create(path: ByteArray, promptMode: Int, threads: Int, gpuLayers: Int): Long
    external fun metalAvailable(): Boolean
    external fun executionInfo(handle: Long): ByteArray
    external fun translate(handle: Long, prompt: ByteArray): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

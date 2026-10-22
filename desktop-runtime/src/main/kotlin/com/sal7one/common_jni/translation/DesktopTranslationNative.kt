package com.sal7one.common_jni.translation

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

/** Desktop-only ABI: protocol 5 adds an explicit CPU/Metal/CUDA backend and GPU device index. */
internal object LocalTranslationNative {
    const val PROTOCOL_VERSION = 5
    init { DesktopNativeLibraries.load("transiber_translation") }
    external fun promptProtocolVersion(): Int
    external fun create(path: ByteArray, promptMode: Int, threads: Int, gpuLayers: Int): Long
    external fun createWithBackend(path: ByteArray, promptMode: Int, threads: Int, backend: Int, deviceIndex: Int): Long
    external fun metalAvailable(): Boolean
    external fun gpuAvailable(backend: Int, deviceIndex: Int): Boolean
    external fun gpuDevices(backend: Int): ByteArray
    external fun executionInfo(handle: Long): ByteArray
    external fun translate(handle: Long, prompt: ByteArray): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

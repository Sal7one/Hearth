package com.sal7one.common_jni.desktop

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

internal object DesktopQwenNative {
    init { DesktopNativeLibraries.load("hearth_desktop_qwen3_asr") }
    external fun protocolVersion(): Int
    external fun create(directory: ByteArray, language: ByteArray, threads: Int): Long
    external fun transcribe(handle: Long, pcm: FloatArray): ByteArray
    external fun language(handle: Long): ByteArray
    external fun executionInfo(handle: Long): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

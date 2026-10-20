package com.sal7one.common_jni.desktop

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

internal object DesktopWhisperNative {
    init { DesktopNativeLibraries.load("hearth_desktop_whisper") }
    external fun protocolVersion(): Int
    external fun create(path: ByteArray, language: ByteArray, threads: Int, metal: Boolean): Long
    external fun metalAvailable(): Boolean
    external fun executionInfo(handle: Long): ByteArray
    external fun transcribe(handle: Long, pcm: FloatArray): ByteArray
    external fun language(handle: Long): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

package com.sal7one.common_jni.translation

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

internal object Ct2Native {
    init { DesktopNativeLibraries.load("hearth_desktop_ct2") }
    external fun protocolVersion(): Int
    external fun create(path: ByteArray, mode: Int): Long
    external fun translate(handle: Long, text: ByteArray, target: ByteArray): ByteArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

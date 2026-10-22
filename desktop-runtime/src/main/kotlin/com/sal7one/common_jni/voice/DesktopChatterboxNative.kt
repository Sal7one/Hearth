package com.sal7one.common_jni.voice

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

internal fun interface ChatterboxPcmCallback { fun onPcm(samples: FloatArray, sampleRate: Int): Boolean }
internal object ChatterboxNative {
    init { DesktopNativeLibraries.load("hearth_desktop_chatterbox") }
    external fun available(): Boolean
    external fun create(directory: ByteArray, threads: Int): Long
    external fun voices(handle: Long): Array<String>
    external fun synthesize(handle: Long, text: ByteArray, language: ByteArray, voice: ByteArray, callback: ChatterboxPcmCallback)
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

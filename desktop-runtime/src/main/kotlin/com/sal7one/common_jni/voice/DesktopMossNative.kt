package com.sal7one.common_jni.voice

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

internal fun interface MossPcmCallback { fun onPcm(samples: FloatArray, sampleRate: Int): Boolean }
internal object MossNative {
    init { DesktopNativeLibraries.load("hearth_desktop_moss") }
    external fun available(): Boolean
    external fun create(directory: ByteArray, threads: Int): Long
    external fun voices(handle: Long): Array<String>
    external fun synthesize(handle: Long, text: ByteArray, voice: ByteArray, callback: MossPcmCallback)
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

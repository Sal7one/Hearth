package com.sal7one.common_jni.voice

internal fun interface ChatterboxPcmCallback { fun onPcm(samples: FloatArray, sampleRate: Int): Boolean }
internal object ChatterboxNative {
    init { System.loadLibrary("hearth_chatterbox_jni") }
    external fun available(): Boolean
    external fun create(directory: ByteArray, threads: Int): Long
    external fun voices(handle: Long): Array<String>
    external fun synthesize(handle: Long, text: ByteArray, language: ByteArray, voice: ByteArray, callback: ChatterboxPcmCallback)
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

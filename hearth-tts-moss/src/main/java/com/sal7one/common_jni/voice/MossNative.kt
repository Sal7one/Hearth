package com.sal7one.common_jni.voice

internal fun interface MossPcmCallback { fun onPcm(samples: FloatArray, sampleRate: Int): Boolean }
internal object MossNative {
    init { System.loadLibrary("hearth_moss_jni") }
    external fun available(): Boolean
    external fun create(directory: ByteArray, threads: Int): Long
    external fun voices(handle: Long): Array<String>
    external fun synthesize(handle: Long, text: ByteArray, voice: ByteArray, callback: MossPcmCallback)
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

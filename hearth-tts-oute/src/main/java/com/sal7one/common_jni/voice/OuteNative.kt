package com.sal7one.common_jni.voice

internal fun interface OutePcmCallback { fun onPcm(samples: FloatArray, sampleRate: Int): Boolean }
internal object OuteNative {
    init { System.loadLibrary("hearth_oute_jni") }
    external fun available(): Boolean
    external fun create(model: ByteArray, reference: ByteArray): Long
    external fun synthesize(handle: Long, text: ByteArray, callback: OutePcmCallback)
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

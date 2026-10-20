package com.sal7one.common_jni.voice

internal fun interface OmniVoicePcmCallback { fun onPcm(samples: FloatArray, sampleRate: Int): Boolean }
internal object OmniVoiceNative {
    init { System.loadLibrary("hearth_omnivoice_jni") }
    external fun available(): Boolean
    external fun create(generator: ByteArray, codec: ByteArray): Long
    external fun languages(): Array<String>
    external fun synthesize(handle: Long, text: ByteArray, language: ByteArray, design: Int, callback: OmniVoicePcmCallback)
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

package com.sal7one.common_jni.voice

internal fun interface MagpiePcmCallback { fun onPcm(samples: ShortArray, sampleRate: Int): Boolean }
internal object MagpieNative {
    init { System.loadLibrary("hearth_magpie_jni") }
    external fun available(): Boolean
    external fun create(generator: ByteArray, codec: ByteArray, tokenizer: ByteArray, threads: Int): Long
    external fun voices(handle: Long): Array<String>
    external fun synthesize(handle: Long, text: ByteArray, language: ByteArray, voice: ByteArray, callback: MagpiePcmCallback)
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

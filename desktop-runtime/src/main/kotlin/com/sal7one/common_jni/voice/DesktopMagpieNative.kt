package com.sal7one.common_jni.voice

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

/** Desktop adapter for the pinned NeMo MagpieTTS C ABI. PCM stays Int16 like the Android binding. */
internal fun interface MagpiePcmCallback { fun onPcm(samples: ShortArray, sampleRate: Int): Boolean }
internal object MagpieNative {
    init { DesktopNativeLibraries.load("hearth_desktop_magpie") }
    external fun available(): Boolean
    external fun create(generator: ByteArray, codec: ByteArray, tokenizer: ByteArray, threads: Int): Long
    external fun voices(handle: Long): Array<String>
    external fun synthesize(handle: Long, text: ByteArray, language: ByteArray, voice: ByteArray, callback: MagpiePcmCallback)
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

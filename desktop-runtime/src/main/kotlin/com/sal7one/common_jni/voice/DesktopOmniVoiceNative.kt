package com.sal7one.common_jni.voice

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

internal fun interface OmniVoicePcmCallback { fun onPcm(samples: FloatArray, sampleRate: Int): Boolean }
internal object OmniVoiceNative {
    init { DesktopNativeLibraries.load("hearth_desktop_omnivoice") }
    external fun available(): Boolean
    external fun create(generator: ByteArray, codec: ByteArray): Long
    external fun languages(): Array<String>
    external fun synthesize(handle: Long, text: ByteArray, language: ByteArray, design: Int, callback: OmniVoicePcmCallback)
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

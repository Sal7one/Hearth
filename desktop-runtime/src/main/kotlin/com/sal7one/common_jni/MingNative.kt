package com.sal7one.common_jni

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries

/** Experimental JNI over the pinned fusor-ai Ming runtime; one-shot WAV synthesis. */
internal object MingNative {
    init { DesktopNativeLibraries.load("ming_jni") }
    external fun abiVersion(): Int
    external fun modelName(): String
    external fun synthesizeWav(
        bundleDir: String, text: String, voice: String, outWav: String,
        preset: Int, seed: Long, threads: Int,
    ): String
}

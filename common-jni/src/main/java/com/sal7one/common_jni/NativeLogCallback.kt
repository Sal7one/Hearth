package com.sal7one.common_jni

/**
 * Stable, unboxed callback boundary used by the native logger.
 *
 * A named SAM method keeps the JNI descriptor independent of Kotlin's
 * Function3 implementation and remains safe when an application enables R8.
 */
internal fun interface NativeLogCallback {
    fun onLog(level: Int, tag: String, message: String)
}

package com.sal7one.common_jni.voice

import com.sal7one.hearth.desktop.runtime.DesktopNativeLibraries
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Desktop loader for the exact shared Supertonic JNI/model contract. */
class SupertonicVoice(directory: File) : AutoCloseable {
    private val handle = AtomicLong(SupertonicNative.create(directory.absolutePath.toByteArray(Charsets.UTF_8)))
    @Synchronized fun synthesize(ids: LongArray, style: VoiceStyle, steps: Int = 5, speed: Float = 1f): FloatArray {
        val id = handle.get(); check(id != 0L) { "Voice model is closed" }
        return SupertonicNative.synthesize(id, ids, style.ttl, style.dp, steps, speed)
    }
    fun cancel() { handle.get().takeIf { it != 0L }?.let(SupertonicNative::cancel) }
    override fun close() { handle.getAndSet(0).takeIf { it != 0L }?.let(SupertonicNative::destroy) }
    companion object {
        const val SAMPLE_RATE = 44100
        fun available(): Boolean = DesktopNativeLibraries.readiness("hearth_supertonic_jni") == null
        fun availabilityReason(): String = DesktopNativeLibraries.readiness("hearth_supertonic_jni")
            ?: "Supertonic JNI loaded"
    }
}
data class VoiceStyle(val ttl: FloatArray, val dp: FloatArray)
internal object SupertonicNative {
    init { DesktopNativeLibraries.load("hearth_supertonic_jni") }
    external fun create(path: ByteArray): Long
    external fun synthesize(handle: Long, ids: LongArray, ttl: FloatArray, dp: FloatArray, steps: Int, speed: Float): FloatArray
    external fun cancel(handle: Long)
    external fun destroy(handle: Long)
}

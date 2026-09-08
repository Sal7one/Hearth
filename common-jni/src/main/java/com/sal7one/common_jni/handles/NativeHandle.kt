package com.sal7one.common_jni.handles

import android.util.Log
import com.sal7one.common_jni.error.NativeError
import com.sal7one.common_jni.error.NativeException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Base class for all native handle wrappers.
 * Provides thread-safe lifecycle management and prevents double-release.
 * 
 * Usage:
 * ```kotlin
 * class WhisperHandle private constructor(handle: Long) : NativeHandle(handle) {
 *     override fun releaseNative(handle: Long) {
 *         nativeRelease(handle)
 *     }
 *     
 *     fun transcribe(audio: ShortArray): String {
 *         ensureValid()
 *         return nativeTranscribe(handle, audio)
 *     }
 *     
 *     companion object {
 *         fun create(modelPath: String): WhisperHandle {
 *             val handle = nativeCreate(modelPath)
 *             if (handle == 0L) throw NativeException.fromNative(nativeGetLastError())
 *             return WhisperHandle(handle)
 *         }
 *         
 *         private external fun nativeCreate(modelPath: String): Long
 *         private external fun nativeRelease(handle: Long)
 *         private external fun nativeTranscribe(handle: Long, audio: ShortArray): String
 *         private external fun nativeGetLastError(): String?
 *     }
 * }
 * ```
 */
abstract class NativeHandle(initialHandle: Long) : Closeable, AutoCloseable {
    
    private val handle = AtomicLong(initialHandle)
    private val released = AtomicBoolean(false)
    
    /**
     * Get the raw handle value.
     * Returns 0 if already released.
     */
    protected val nativeHandle: Long
        get() = handle.get()
    
    /**
     * Check if handle is still valid.
     */
    val isValid: Boolean
        get() = !released.get() && handle.get() != 0L
    
    /**
     * Ensure handle is valid, throw if not.
     */
    protected fun ensureValid() {
        if (released.get()) {
            throw NativeException(NativeError.HandleAlreadyReleased())
        }
        if (handle.get() == 0L) {
            throw NativeException(NativeError.InvalidHandle())
        }
    }
    
    /**
     * Override to implement native release.
     * Will only be called once.
     */
    protected abstract fun releaseNative(handle: Long)
    
    /**
     * Release the native resource.
     * Safe to call multiple times.
     */
    override fun close() {
        if (released.compareAndSet(false, true)) {
            val h = handle.getAndSet(0L)
            if (h != 0L) {
                try {
                    releaseNative(h)
                    Log.d(TAG, "Released handle: $h")
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing handle $h: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Use block pattern for automatic cleanup.
     */
    inline fun <R> use(block: (NativeHandle) -> R): R {
        try {
            return block(this)
        } finally {
            close()
        }
    }
    
    protected fun finalize() {
        if (!released.get() && handle.get() != 0L) {
            Log.w(TAG, "Handle was not properly released! Releasing in finalizer.")
            close()
        }
    }
    
    companion object {
        private const val TAG = "NativeHandle"
    }
}

/**
 * Extension to safely execute a block with a handle.
 */
fun <T : NativeHandle, R> T.withHandle(block: T.() -> R): R {
    if (!isValid) {
        throw NativeException(NativeError.InvalidHandle())
    }
    return block()
}


package com.sal7one.common_jni.engine

import com.sal7one.common_jni.model.AudioChunk
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ordered PCM input for a single engine. Selects its optional direct-buffer input
 * without assuming a model family; other engines receive the original samples.
 * The caller serializes pushes and keeps samples unchanged until each call ends.
 */
class SttPcmInput(private val engine: SttEngine, private val sampleRate: Int) {
    private val direct = engine as? DirectPcmSttEngine
    private var buffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    init { require(sampleRate > 0) { "PCM sample rate must be positive" } }

    suspend fun push(samples: ShortArray, timestampMs: Long = 0L): Result<Unit> {
        if (direct == null) {
            return engine.pushAudioChunk(AudioChunk(samples, sampleRate, timestampMs,
                samples.size * 1000L / sampleRate))
        }
        val bytes = Math.multiplyExact(samples.size, Short.SIZE_BYTES)
        if (buffer.capacity() < bytes) buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        buffer.clear()
        buffer.asShortBuffer().put(samples)
        buffer.limit(bytes)
        return direct.pushAudioDirect(buffer, 0, bytes, sampleRate)
    }
}

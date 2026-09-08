package com.sal7one.common_jni.audio

import com.sal7one.common_jni.perf.RollingStats
import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance microphone recorder for real-time STT.
 *
 * Two capture modes:
 *
 *  * **ShortArray mode (default, legacy)** — emits [AudioChunkData] through
 *    [audioFlow]. Simple, compatible with any consumer; requires a JVM→native
 *    array copy at the JNI boundary.
 *
 *  * **Direct mode** (`useDirectBuffers = true`) — emits [PooledAudioChunk]
 *    through [directAudioFlow]. Each chunk references a pre-allocated
 *    [java.nio.ByteBuffer] from an internal ring of [DIRECT_POOL_SIZE] buffers.
 *    The consumer **must call [PooledAudioChunk.release]** after processing so
 *    the buffer can be reused. Combined with the `pushAudioDirect` JNI path on
 *    Whisper/Vosk engines this delivers a true zero-copy mic→inference pipeline.
 */
class MicRecorder(
    private val sampleRate: Int = SAMPLE_RATE_16K,
    private val chunkDurationMs: Int = CHUNK_DURATION_MS,
    private val useDirectBuffers: Boolean = false
) {
    companion object {
        private const val TAG = "MicRecorder"
        const val SAMPLE_RATE_16K = 16000
        const val SAMPLE_RATE_44K = 44100
        const val CHUNK_DURATION_MS = 100  // 100ms chunks for low latency

        /** Number of ByteBuffers in the direct-mode pool. */
        const val DIRECT_POOL_SIZE = 8

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    // Recording state
    private val _isRecording = AtomicBoolean(false)
    private val captureGeneration = AtomicLong(0L)
    val isRecording: Boolean get() = _isRecording.get()

    // Legacy ShortArray flow
    private val _audioFlow = MutableSharedFlow<AudioChunkData>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioFlow: SharedFlow<AudioChunkData> = _audioFlow.asSharedFlow()

    // A pooled buffer has exactly one owner, so this must remain a single-consumer
    // queue rather than a multicast SharedFlow. When inference falls behind, drop
    // the newest chunk and immediately return its buffer to the pool.
    private val directAudioChannel = ReleasingPooledChannel<PooledAudioChunk>(
        capacity = DIRECT_POOL_SIZE,
        release = PooledAudioChunk::release,
    )
    private val directDeliveryLock = Any()
    val directAudioFlow: Flow<PooledAudioChunk> = directAudioChannel.flow

    // Recording state flow
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    // Internal
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Calculated values
    private val chunkSamples = (sampleRate * chunkDurationMs) / 1000
    private val chunkBytes = chunkSamples * 2 // int16 = 2 bytes
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
    private val bufferSize = maxOf(minBufferSize, chunkSamples * 2 * 4) // At least 4 chunks

    // Direct-buffer pool (only allocated when useDirectBuffers = true)
    private val freeBuffers: ArrayBlockingQueue<ByteBuffer>? =
        if (useDirectBuffers) ArrayBlockingQueue<ByteBuffer>(DIRECT_POOL_SIZE).apply {
            repeat(DIRECT_POOL_SIZE) {
                add(ByteBuffer.allocateDirect(chunkBytes).order(ByteOrder.nativeOrder()))
            }
        } else null

    /**
     * Start recording. Requires RECORD_AUDIO permission.
     *
     * @throws SecurityException if permission not granted
     * @throws IllegalStateException if already recording
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (_isRecording.getAndSet(true)) {
            Log.w(TAG, "Already recording")
            return
        }

        try {
            _state.value = RecordingState.STARTING

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            ).also {
                if (it.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord failed to initialize")
                }
            }

            audioRecord?.startRecording()
            _state.value = RecordingState.RECORDING

            // Start capture loop on dedicated thread
            val generation = captureGeneration.incrementAndGet()
            recordingJob = scope.launch(Dispatchers.IO) {
                if (useDirectBuffers) directCaptureLoop(generation) else captureLoop(generation)
            }

            Log.i(
                TAG,
                "Recording started: ${sampleRate}Hz, ${chunkDurationMs}ms chunks, " +
                    "direct=${useDirectBuffers}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _isRecording.set(false)
            _state.value = RecordingState.ERROR
            releaseAudioRecord()
            throw e
        }
    }

    /**
     * Stop recording and release resources.
     */
    fun stop() {
        if (!_isRecording.getAndSet(false)) {
            return
        }

        _state.value = RecordingState.STOPPING
        captureGeneration.incrementAndGet()

        recordingJob?.cancel()
        recordingJob = null

        releaseAudioRecord()
        // Serialize the final drain with delivery. A blocking AudioRecord.read()
        // may return as stop() tears the recorder down; it must not enqueue a
        // pooled buffer after this drain has completed.
        synchronized(directDeliveryLock) {
            directAudioChannel.discardPending()
        }

        _state.value = RecordingState.IDLE
        Log.i(TAG, "Recording stopped")
    }

    /**
     * Release all resources. Call when done with the recorder.
     */
    fun release() {
        stop()
        directAudioChannel.cancel()
        scope.cancel()
    }

    private suspend fun captureLoop(generation: Long) {
        val buffer = ShortArray(chunkSamples)
        var timestampMs = 0L

        while (_isRecording.get() && generation == captureGeneration.get() &&
            currentCoroutineContext().isActive) {
            val record = audioRecord ?: break
            if (generation != captureGeneration.get()) break

            val samplesRead = record.read(buffer, 0, chunkSamples)

            when {
                samplesRead > 0 -> {
                    if (generation != captureGeneration.get()) break
                    recordChunkPacing()
                    val chunk = AudioChunkData(
                        samples = buffer.copyOf(samplesRead),
                        sampleRate = sampleRate,
                        timestampMs = timestampMs,
                        durationMs = (samplesRead * 1000L) / sampleRate
                    )

                    _audioFlow.emit(chunk)
                    timestampMs += chunk.durationMs
                }
                samplesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                    Log.e(TAG, "Invalid operation during read")
                    break
                }
                samplesRead == AudioRecord.ERROR_BAD_VALUE -> {
                    Log.e(TAG, "Bad value during read")
                    break
                }
            }
        }
    }

    /**
     * Direct-buffer capture loop: reads PCM bytes straight into a pooled
     * [ByteBuffer] via `AudioRecord.read(ByteBuffer, …)` (API 23+, our min is 28),
     * then emits a [PooledAudioChunk]. No intermediate ShortArray, no GC.
     *
     * If the pool is exhausted (slow consumer) we drop the sample and keep
     * reading. If the channel itself is full, the newest chunk is dropped and
     * its buffer is immediately returned to the pool.
     */
    private suspend fun directCaptureLoop(generation: Long) {
        var timestampMs = 0L
        val pool = freeBuffers ?: return
        while (_isRecording.get() && generation == captureGeneration.get() &&
            currentCoroutineContext().isActive) {
            val record = audioRecord ?: break
            if (generation != captureGeneration.get()) break

            // Acquire a free buffer (non-blocking; if empty, drop this cycle)
            val buf = pool.poll()
            if (buf == null) {
                // Consumer is backed up — yield briefly so we don't busy-spin.
                // At 100ms chunks this is at most one frame skipped.
                delay(chunkDurationMs.toLong())
                continue
            }

            buf.clear() // position=0, limit=capacity
            val bytesRead = record.read(buf, chunkBytes)

            when {
                bytesRead > 0 -> {
                    buf.position(0).limit(bytesRead)
                    val sampleCount = bytesRead / 2
                    val durationMs = (sampleCount * 1000L) / sampleRate
                    val chunk = PooledAudioChunk(
                        buffer = buf,
                        byteOffset = 0,
                        byteCount = bytesRead,
                        sampleRate = sampleRate,
                        timestampMs = timestampMs,
                        durationMs = durationMs,
                        releaseFn = { pool.offer(buf) }
                    )
                    timestampMs += durationMs
                    recordChunkPacing()

                    // The bounded queue explicitly releases a rejected newest
                    // chunk; cancellation releases queued undelivered chunks.
                    synchronized(directDeliveryLock) {
                        if (_isRecording.get() && generation == captureGeneration.get()) {
                            directAudioChannel.offer(chunk)
                        } else {
                            chunk.release()
                        }
                    }
                }
                bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                    pool.offer(buf)
                    Log.e(TAG, "Invalid operation during direct read")
                    break
                }
                bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                    pool.offer(buf)
                    Log.e(TAG, "Bad value during direct read")
                    break
                }
                else -> {
                    // 0 bytes or transient error: return buffer, keep going
                    pool.offer(buf)
                }
            }
        }
    }

    // Measurement-only chunk-pacing instrumentation (additive; never alters
    // capture or delivery behavior). Aggregates delivery jitter — the
    // wall-clock interval between delivered chunks minus chunkDurationMs — into
    // a RollingStats and debug-logs a summary every 50 chunks.
    private val chunkJitterStats = RollingStats(capacity = 512)
    private var lastChunkDeliveryNanos = 0L
    private var deliveredChunkCount = 0L

    private fun recordChunkPacing() {
        val now = System.nanoTime()
        if (lastChunkDeliveryNanos != 0L) {
            val intervalMs = (now - lastChunkDeliveryNanos) / 1_000_000.0
            chunkJitterStats.record(intervalMs - chunkDurationMs)
        }
        lastChunkDeliveryNanos = now
        deliveredChunkCount++
        if (deliveredChunkCount % 50L == 0L) {
            Log.d(TAG, "chunk pacing (${deliveredChunkCount} chunks): ${chunkJitterStats.format()}")
        }
    }

    private fun releaseAudioRecord() {
        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioRecord", e)
            }
        }
        audioRecord = null
    }
}

/**
 * Audio chunk data from microphone.
 */
data class AudioChunkData(
    val samples: ShortArray,
    val sampleRate: Int,
    val timestampMs: Long,
    val durationMs: Long
) {
    val sampleCount: Int get() = samples.size
    val isEmpty: Boolean get() = samples.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioChunkData
        return samples.contentEquals(other.samples) &&
                sampleRate == other.sampleRate &&
                timestampMs == other.timestampMs
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}

/**
 * Zero-copy audio chunk backed by a pooled Direct [ByteBuffer].
 *
 * IMPORTANT: The consumer **must** call [release] exactly once after the chunk
 * is processed, so the underlying buffer returns to the pool. Forgetting to
 * release will eventually starve the recorder (it drops frames once the pool
 * is empty).
 *
 * Layout: `buffer[byteOffset .. byteOffset + byteCount)` holds native-endian
 * int16 PCM samples at [sampleRate].
 */
class PooledAudioChunk internal constructor(
    val buffer: ByteBuffer,
    val byteOffset: Int,
    val byteCount: Int,
    val sampleRate: Int,
    val timestampMs: Long,
    val durationMs: Long,
    private val releaseFn: () -> Unit
) {
    private val released = AtomicBoolean(false)
    val sampleCount: Int get() = byteCount / 2

    fun release() {
        if (released.compareAndSet(false, true)) releaseFn()
    }
}

/**
 * Recording state.
 */
enum class RecordingState {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
    ERROR
}

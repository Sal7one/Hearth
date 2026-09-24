package com.sal7one.common_jni.core

import android.util.Log
import com.sal7one.common_jni.CommonJni
import com.sal7one.common_jni.audio.MicRecorder
import com.sal7one.common_jni.audio.OneShotPcm16Probe
import com.sal7one.common_jni.audio.PooledAudioChunk
import com.sal7one.common_jni.engine.SttEngine
import com.sal7one.common_jni.engine.vosk.VoskEngine
import com.sal7one.common_jni.engine.whisper.WhisperEngine
import com.sal7one.common_jni.model.AudioChunk
import com.sal7one.common_jni.model.PartialTranscript
import com.sal7one.common_jni.model.SttConfig
import com.sal7one.common_jni.model.TranscriptResult
import com.sal7one.common_jni.perf.PerfMetrics
import com.sal7one.common_jni.perf.RollingStats
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time STT session that combines microphone recording with streaming transcription.
 * 
 * Features:
 * - Low-latency audio capture and processing
 * - Streaming partial results
 * - Proper thread management (audio on IO, inference on Default)
 * - Cancellation support
 * 
 * Thread Safety:
 * - initialize() is protected by mutex to prevent double-init races
 * - start() uses atomic flag to prevent double-start
 * - All whisper_* calls are serialized by the engine's internal ProcessingGuard
 * 
 * Usage:
 * ```kotlin
 * val session = RealtimeSttSession(engine, config)
 * 
 * // Observe transcripts
 * session.partialTranscripts.collect { partial ->
 *     updateUI(partial.text)
 * }
 * 
 * // Initialize first
 * session.initialize(modelPath)
 * 
 * // Start session
 * session.start()
 * 
 * // Stop and get final result
 * val result = session.stop()
 * ```
 */
class RealtimeSttSession(
    private val engine: SttEngine,
    private val config: SttConfig = SttConfig.forStreaming()
) {
    companion object {
        private const val TAG = "RealtimeSttSession"
        // Minimum interval between partial emissions; acts as a coalescer when
        // inference is faster than the UI can consume. Actual fetch is driven
        // by audio-push completion events, not a fixed clock.
        private const val PARTIAL_MIN_INTERVAL_MS = 80L
    }
    
    // State
    private val _state = MutableStateFlow(RealtimeSessionState.IDLE)
    val state: StateFlow<RealtimeSessionState> = _state.asStateFlow()
    
    private val _partialTranscripts = MutableSharedFlow<PartialTranscript>(
        replay = 1,
        extraBufferCapacity = 8
    )
    val partialTranscripts: SharedFlow<PartialTranscript> = _partialTranscripts.asSharedFlow()
    
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
    
    // Use tryEmit + DROP_OLDEST to never block audio processing on error emission
    private val _error = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val errors: SharedFlow<String> = _error.asSharedFlow()
    
    private val audioProbe = OneShotPcm16Probe()
    
    // Internal
    // Enable zero-copy direct-buffer mic path when the engine supports it (Whisper / Vosk).
    // Other engines fall back to the legacy ShortArray capture path.
    private val useDirectPath: Boolean = engine is WhisperEngine || engine is VoskEngine

    private val micRecorder = MicRecorder(
        sampleRate = config.sampleRate,
        chunkDurationMs = config.streamingChunkDurationMs,
        useDirectBuffers = useDirectPath
    )

    // Fused pipeline dispatcher: single-threaded for STT inference *and* mic
    // consumption, so audio chunks flow straight into the engine without an
    // extra context switch per 100 ms frame.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pipelineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val pipelineScope = CoroutineScope(SupervisorJob() + pipelineDispatcher)

    // Kept as aliases so existing references in the file still compile; both
    // now point at the single fused pipeline scope.
    private val audioScope get() = pipelineScope
    private val sttScope get() = pipelineScope
    
    private var processingJob: Job? = null
    private var partialJob: Job? = null
    private var captureErrorJob: Job? = null
    private val isActive = AtomicBoolean(false)
    
    // Mutex for initialize() to prevent double-init races
    private val initMutex = Mutex()
    private var initialized = false

    // Measurement-only latency instrumentation (additive; never alters the
    // inference path). Aggregated into RollingStats and debug-logged every 50
    // pushes.
    private val pushDirectStats = RollingStats(capacity = 512)
    private val getPartialStats = RollingStats(capacity = 512)
    private var pushCount = 0L

    /**
     * Capture exactly one bounded PCM16 prefix for an auxiliary task such as
     * language detection. After [sampleCount] samples the probe detaches and the
     * realtime path returns to zero-copy operation.
     */
    fun requestAudioProbe(sampleCount: Int): Deferred<ShortArray?> = audioProbe.request(sampleCount)
    
    /**
     * Initialize the session with model.
     * Thread-safe: protected by mutex.
     */
    suspend fun initialize(modelPath: String): Result<Unit> = initMutex.withLock {
        if (initialized) return Result.success(Unit)
        
        _state.value = RealtimeSessionState.INITIALIZING
        
        return try {
            engine.initialize(modelPath, config).also {
                if (it.isSuccess) {
                    initialized = true
                    _state.value = RealtimeSessionState.READY
                } else {
                    _state.value = RealtimeSessionState.ERROR
                }
            }
        } catch (e: Exception) {
            _state.value = RealtimeSessionState.ERROR
            Result.failure(e)
        }
    }
    
    /**
     * Start real-time transcription.
     * Requires RECORD_AUDIO permission.
     * 
     * IMPORTANT: reset() is called BEFORE starting mic/processing to avoid race.
     */
    @Throws(SecurityException::class, IllegalStateException::class)
    suspend fun start() {
        if (!initialized) {
            throw IllegalStateException("Session not initialized. Call initialize() first.")
        }
        
        if (isActive.getAndSet(true)) {
            Log.w(TAG, "Session already active")
            return
        }
        
        _state.value = RealtimeSessionState.STARTING
        
        // Reset engine state SYNCHRONOUSLY before starting anything else
        // This avoids race between reset() and pushAudioChunk()
        withContext(sttScope.coroutineContext) {
            engine.reset()
        }
        
        // Start microphone
        try {
            micRecorder.start()
        } catch (e: Exception) {
            isActive.set(false)
            _state.value = RealtimeSessionState.ERROR
            _error.tryEmit(e.message ?: "Failed to start microphone")
            throw e
        }
        
        // Start processing pipeline on IO (audio collection)
        processingJob = audioScope.launch {
            processAudioStream()
        }

        // Partials are now emitted event-driven from the audio loop
        // (after each push) instead of a fixed 300ms poll. Keep partialJob
        // null so the lifecycle code below still works unchanged.
        partialJob = null
        
        _state.value = RealtimeSessionState.LISTENING
        // Observe capture failure outside the single inference lane: a slow
        // native push must not delay the microphone error reaching callers.
        captureErrorJob = pipelineScope.launch(Dispatchers.Default) {
            val code = micRecorder.readErrorCode.filterNotNull().first()
            if (this@RealtimeSttSession.isActive.getAndSet(false)) {
                _state.value = RealtimeSessionState.ERROR
                _error.tryEmit("Microphone capture failed: AudioRecord.read returned $code")
                audioProbe.cancel()
                processingJob?.cancel()
            }
        }
        Log.i(TAG, "Real-time session started")
    }
    
    /**
     * Stop recording and get final transcript.
     */
    suspend fun stop(): Result<TranscriptResult> {
        if (!isActive.getAndSet(false)) {
            val readCode = micRecorder.readErrorCode.value
            val message = if (readCode != null) {
                "Microphone capture failed: AudioRecord.read returned $readCode"
            } else {
                "Session not active"
            }
            return Result.failure(IllegalStateException(message))
        }
        
        _state.value = RealtimeSessionState.PROCESSING
        
        // Stop microphone first
        micRecorder.stop()
        audioProbe.cancel()
        
        // Cancel processing jobs and wait for them
        processingJob?.cancelAndJoin()
        partialJob?.cancelAndJoin()
        captureErrorJob?.cancelAndJoin()
        processingJob = null
        partialJob = null
        captureErrorJob = null
        
        // Get final result
        return try {
            val result = withContext(sttScope.coroutineContext) {
                engine.finalize()
            }
            _state.value = RealtimeSessionState.READY
            result
        } catch (e: Exception) {
            _state.value = RealtimeSessionState.ERROR
            Result.failure(e)
        }
    }
    
    /**
     * Cancel the session without getting result.
     */
    suspend fun cancel() {
        if (!isActive.getAndSet(false)) return
        
        micRecorder.stop()
        audioProbe.cancel()
        processingJob?.cancelAndJoin()
        partialJob?.cancelAndJoin()
        captureErrorJob?.cancelAndJoin()
        processingJob = null
        partialJob = null
        captureErrorJob = null
        
        withContext(sttScope.coroutineContext) {
            engine.reset()
        }
        
        _state.value = RealtimeSessionState.READY
        Log.i(TAG, "Session cancelled")
    }
    
    /**
     * Release all resources.
     * BLOCKING: Waits for engine.release() to complete before cancelling scopes.
     */
    suspend fun release() {
        // First cancel any active session
        if (isActive.get()) {
            cancel()
        }
        
        // Release engine BEFORE cancelling scopes (so it actually runs)
        withContext(sttScope.coroutineContext) {
            engine.release()
        }
        
        micRecorder.release()
        audioProbe.cancel()
        
        // Now safe to cancel scopes
        audioScope.cancel()
        sttScope.cancel()
        
        initialized = false
        _state.value = RealtimeSessionState.IDLE
        Log.i(TAG, "Session released")
    }
    
    private var lastPartialEmitMs: Long = 0L

    /**
     * Event-driven partial emit: called immediately after a push completes.
     * Coalesces bursts via [PARTIAL_MIN_INTERVAL_MS] and suppresses duplicate
     * text to keep UI animation cheap.
     */
    private var lastPartialText: String = ""
    private suspend fun maybeEmitPartial() {
        val now = System.currentTimeMillis()
        if (now - lastPartialEmitMs < PARTIAL_MIN_INTERVAL_MS) return
        val partial = try {
            val timed = PerfMetrics.measureValue { engine.getPartialTranscript() }
            getPartialStats.record(timed.elapsedMs)
            timed.value
        } catch (e: Exception) {
            Log.e(TAG, "Error getting partial transcript", e); null
        } ?: return
        if (partial.text.isBlank() || partial.text == lastPartialText) return
        lastPartialEmitMs = now
        lastPartialText = partial.text
        _partialTranscripts.emit(partial)
    }

    private suspend fun processAudioStream() {
        if (useDirectPath) {
            processDirectAudioStream()
        } else {
            processLegacyAudioStream()
        }
    }

    /**
     * Zero-copy direct-buffer audio pipeline for Whisper / Vosk.
     * Each [PooledAudioChunk] is handed straight to the engine's
     * `pushAudioDirect` JNI call — no ShortArray copy, no JVM→native
     * critical-array handshake. The native side computes the RMS level.
     * When done we MUST release the chunk so its buffer returns to the pool.
     */
    private suspend fun processDirectAudioStream() {
        micRecorder.directAudioFlow.collect { chunk ->
            if (!isActive.get()) {
                chunk.release(); return@collect
            }
            val currentState = _state.value
            if (currentState == RealtimeSessionState.ERROR ||
                currentState == RealtimeSessionState.PROCESSING) {
                chunk.release(); return@collect
            }

            // This copies only while a bounded one-shot probe is pending.
            audioProbe.append(chunk.buffer, chunk.byteOffset, chunk.byteCount)

            // Native NEON RMS — no JVM loop, no allocation.
            _audioLevel.value = CommonJni.audioLevel(chunk.buffer, chunk.byteOffset, chunk.byteCount)

            try {
                val (result, pushElapsedMs) = when (engine) {
                    is WhisperEngine -> {
                        val timed = PerfMetrics.measureValue {
                            engine.pushAudioDirect(
                                chunk.buffer, chunk.byteOffset, chunk.byteCount, chunk.sampleRate
                            )
                        }
                        timed.value to timed.elapsedMs
                    }
                    is VoskEngine -> {
                        val timed = PerfMetrics.measureValue {
                            engine.pushAudioDirect(
                                chunk.buffer, chunk.byteOffset, chunk.byteCount, chunk.sampleRate
                            )
                        }
                        timed.value to timed.elapsedMs
                    }
                    else -> Result.success(Unit) to 0.0
                }
                pushDirectStats.record(pushElapsedMs)
                pushCount++
                if (pushCount % 50L == 0L) {
                    Log.d(TAG, "push stats after ${pushCount} pushes: push=${pushDirectStats.format()}; partial=${getPartialStats.format()}")
                }
                result.onFailure { e ->
                    Log.e(TAG, "Error pushing direct audio chunk", e)
                    _error.tryEmit("Processing error: ${e.message}")
                }
                maybeEmitPartial()
            } catch (e: Exception) {
                Log.e(TAG, "Error pushing direct audio chunk", e)
                _error.tryEmit("Processing error: ${e.message}")
            } finally {
                chunk.release()
            }
        }
    }

    /** Legacy short-array capture path (used for engines without a direct-buffer entry). */
    private suspend fun processLegacyAudioStream() {
        micRecorder.audioFlow.collect { chunk ->
            if (!isActive.get()) return@collect

            val currentState = _state.value
            if (currentState == RealtimeSessionState.ERROR ||
                currentState == RealtimeSessionState.PROCESSING) {
                return@collect
            }

            // Calculate audio level for UI visualization
            val level = calculateAudioLevel(chunk.samples)
            _audioLevel.value = level

            audioProbe.append(chunk.samples)

            val audioChunk = AudioChunk(
                samples = chunk.samples,
                sampleRate = chunk.sampleRate,
                timestampMs = chunk.timestampMs,
                durationMs = chunk.durationMs
            )

            try {
                // Same dispatcher — no context switch, just a direct call
                engine.pushAudioChunk(audioChunk)
                maybeEmitPartial()
            } catch (e: Exception) {
                Log.e(TAG, "Error pushing audio chunk", e)
                _error.tryEmit("Processing error: ${e.message}")
            }
        }
    }
    
    
    private fun calculateAudioLevel(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        
        var sum = 0L
        for (sample in samples) {
            sum += sample.toLong() * sample
        }
        
        val rms = kotlin.math.sqrt(sum.toDouble() / samples.size)
        // Normalize to 0-1 range (max int16 = 32768)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * Real-time session state.
 */
enum class RealtimeSessionState {
    IDLE,
    INITIALIZING,
    READY,
    STARTING,
    LISTENING,
    PROCESSING,
    ERROR
}

package com.sal7one.common_jni.engine.whisper

import com.sal7one.common_jni.engine.SttEngine
import com.sal7one.common_jni.engine.SttError
import com.sal7one.common_jni.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Whisper.cpp STT engine implementation.
 *
 * Performance Notes:
 * - Model loading: 1-5 seconds depending on model size
 * - Batch mode: ~0.3-0.5x realtime on modern devices
 * - Streaming: ~0.5-0.8x realtime
 * - Thread count capped at 4 for Android big.LITTLE architecture
 *
 * CHANGES FROM ORIGINAL:
 * - Uses Dispatchers.Default for CPU-bound work (not IO)
 * - Removed Kotlin mutex (native code is already thread-safe)
 * - Removed expensive debug allocations
 * - Added capability reporting
 */
class WhisperEngine : SttEngine {

    private var nativeHandle: Long = 0L
    private var _isInitialized = false
    private var _currentModel: ModelInfo? = null
    private var _currentConfig: SttConfig? = null
    private var accumulatedAudioMs = 0L
    
    // Use Default dispatcher for CPU-bound inference work
    // Limited to 1 to prevent concurrent inference (Whisper is not thread-safe per context)
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)

    override val engineType: SttEngineType = SttEngineType.WHISPER
    override val isInitialized: Boolean get() = _isInitialized
    override val currentModel: ModelInfo? get() = _currentModel
    
    override val capabilities: EngineCapabilities
        get() = EngineCapabilities.WHISPER

    companion object {
        private const val TAG = "WhisperEngine"

        @Volatile
        private var libraryLoaded = false

        @Volatile
        private var libraryError: String? = null

        init {
            try {
                System.loadLibrary("common_jni")
                libraryLoaded = nativeIsWhisperAvailable()
            } catch (e: UnsatisfiedLinkError) {
                libraryLoaded = false
                libraryError = e.message
            }
        }

        fun isAvailable(): Boolean = libraryLoaded
        fun getLibraryError(): String? = libraryError

        @JvmStatic
        private external fun nativeIsWhisperAvailable(): Boolean

        @JvmStatic
        private external fun nativeGetWhisperVersion(): String
    }

    // Native methods
    private external fun nativeCreateWhisper(): Long
    private external fun nativeDestroyWhisper(handle: Long)
    private external fun nativeInitWhisper(handle: Long, modelPath: String, configJson: String): Boolean
    private external fun nativePushAudioWhisper(handle: Long, samples: ShortArray, count: Int, sampleRate: Int): Int
    private external fun nativePushAudioFloatWhisper(handle: Long, samples: FloatArray, count: Int, sampleRate: Int): Int
    // Zero-copy: feeds a direct int16 PCM ByteBuffer straight to the engine.
    // byteOffset/byteCount are in BYTES (sampleCount = byteCount / 2). Caller must
    // keep the ByteBuffer alive for the duration of the call.
    private external fun nativePushAudioDirectWhisper(
        handle: Long, buffer: java.nio.ByteBuffer, byteOffset: Int, byteCount: Int, sampleRate: Int
    ): Int
    private external fun nativeGetPartialWhisper(handle: Long): String?
    private external fun nativeFinalizeWhisper(handle: Long): String?
    private external fun nativeResetWhisper(handle: Long)
    private external fun nativeTranscribeBatchWhisper(handle: Long, samples: ShortArray, count: Int, sampleRate: Int): String?
    private external fun nativeGetLastErrorWhisper(handle: Long): String?
    private external fun nativeIsProcessingWhisper(handle: Long): Boolean
    private external fun nativeGetModelTypeWhisper(handle: Long): Int
    private external fun nativeCancelWhisper(handle: Long)
    private external fun nativeIsCancelledWhisper(handle: Long): Boolean
    private external fun nativeDetectLanguage(handle: Long, samples: ShortArray, count: Int, sampleRate: Int): String?
    
    /**
     * Detect language from audio samples using Whisper's dedicated LID-only function.
     * This does NOT run full transcription - just language identification.
     * 
     * This is useful for selecting the correct Vosk model when using Vosk for STT.
     * 
     * @param samples Audio samples (16-bit PCM, mono)
     * @param sampleRate Sample rate (typically 16000, will be resampled if different)
     * @return Detected language code (e.g., "en", "ar") or null on failure/error
     */
    suspend fun detectLanguage(samples: ShortArray, sampleRate: Int = 16000): String? =
        withContext(inferenceDispatcher) {  // Use dedicated dispatcher, not shared Default pool
            if (!_isInitialized || nativeHandle == 0L) return@withContext null
            if (samples.isEmpty()) return@withContext null
            
            // Native returns null on error, empty string should not happen with new impl
            nativeDetectLanguage(nativeHandle, samples, samples.size, sampleRate)
        }

    override suspend fun initialize(modelPath: String, config: SttConfig): Result<Unit> =
        withContext(inferenceDispatcher) {  // serialize handle lifetime with pushes/polls
            if (!libraryLoaded) {
                return@withContext Result.failure(
                    SttError.LibraryNotLoaded(libraryError ?: "Whisper library not loaded")
                )
            }

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                return@withContext Result.failure(SttError.ModelNotFound(modelPath))
            }

            releaseInternal()

            try {
                val verifiedConfig = if (config.modelSha256 == null) {
                    config.copy(modelSha256 = ModelIntegrity.inspect(modelFile).digest.hex)
                } else {
                    config
                }
                nativeHandle = nativeCreateWhisper()
                if (nativeHandle == 0L) {
                    return@withContext Result.failure(
                        SttError.InitializationFailed("Failed to create Whisper engine")
                    )
                }

                val success = nativeInitWhisper(nativeHandle, modelPath, verifiedConfig.toJson())
                if (!success) {
                    val error = nativeGetLastErrorWhisper(nativeHandle) ?: "Unknown error"
                    releaseInternal()
                    return@withContext Result.failure(SttError.InitializationFailed(error))
                }

                _isInitialized = true
                _currentConfig = verifiedConfig
                _currentModel = ModelInfo(
                    path = modelPath,
                    name = modelFile.name,
                    type = ModelInfo.ModelSize.fromNativeType(nativeGetModelTypeWhisper(nativeHandle)),
                    engineType = SttEngineType.WHISPER,
                    sizeBytes = modelFile.length()
                )
                accumulatedAudioMs = 0L

                Result.success(Unit)
            } catch (e: Exception) {
                releaseInternal()
                Result.failure(SttError.InitializationFailed(e.message ?: "Unknown error"))
            }
        }

    override suspend fun pushAudioChunk(chunk: AudioChunk): Result<Unit> =
        withContext(inferenceDispatcher) {
            if (!_isInitialized || nativeHandle == 0L) {
                return@withContext Result.failure(SttError.NotInitialized())
            }

            if (chunk.isEmpty) return@withContext Result.success(Unit)

            try {
                val result = nativePushAudioWhisper(nativeHandle, chunk.samples, chunk.sampleCount, chunk.sampleRate)
                if (result < 0) {
                    val error = nativeGetLastErrorWhisper(nativeHandle) ?: "Push failed"
                    return@withContext Result.failure(SttError.ProcessingFailed(error))
                }
                accumulatedAudioMs += chunk.durationMs
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(SttError.ProcessingFailed(e.message ?: "Unknown error"))
            }
        }

    /**
     * Zero-copy push from a Direct ByteBuffer containing native-endian int16 PCM.
     *
     * This is the preferred realtime path: it avoids the JVM→native array copy
     * entirely by handing the engine a pointer to the DirectByteBuffer's backing
     * memory via `GetDirectBufferAddress`. Use with a buffer produced by
     * [java.nio.ByteBuffer.allocateDirect] (the mic recorder's direct-buffer mode
     * writes into exactly such a buffer).
     *
     * @param buffer A direct ByteBuffer. Must remain live and unmodified for the
     *               duration of this call.
     * @param byteOffset Starting byte offset inside the buffer.
     * @param byteCount  Number of bytes to read (must be even; sampleCount = byteCount / 2).
     * @param sampleRate Input sample rate. If != 16000 the native side resamples
     *                   using thread-local scratch buffers (no heap allocation).
     */
    suspend fun pushAudioDirect(
        buffer: java.nio.ByteBuffer,
        byteOffset: Int,
        byteCount: Int,
        sampleRate: Int
    ): Result<Unit> = withContext(inferenceDispatcher) {
        if (!_isInitialized || nativeHandle == 0L) {
            return@withContext Result.failure(SttError.NotInitialized())
        }
        if (byteCount <= 0) return@withContext Result.success(Unit)
        if (!buffer.isDirect) {
            return@withContext Result.failure(
                SttError.ProcessingFailed("pushAudioDirect requires a direct ByteBuffer")
            )
        }
        try {
            val rc = nativePushAudioDirectWhisper(nativeHandle, buffer, byteOffset, byteCount, sampleRate)
            if (rc < 0) {
                val err = nativeGetLastErrorWhisper(nativeHandle) ?: "Push failed"
                return@withContext Result.failure(SttError.ProcessingFailed(err))
            }
            // durationMs = sampleCount * 1000 / sampleRate
            accumulatedAudioMs += (byteCount / 2) * 1000L / sampleRate
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(SttError.ProcessingFailed(e.message ?: "Unknown error"))
        }
    }

    override suspend fun getPartialTranscript(): PartialTranscript? =
        withContext(inferenceDispatcher) {
            if (!_isInitialized || nativeHandle == 0L) return@withContext null

            try {
                nativeGetPartialWhisper(nativeHandle)?.takeIf { it.isNotBlank() }?.let {
                    PartialTranscript(it.trim(), System.currentTimeMillis(), false)
                }
            } catch (e: Exception) {
                throw SttError.ProcessingFailed(e.message ?: "Whisper partial failed")
            }
        }

    override suspend fun finalize(): Result<TranscriptResult> =
        withContext(inferenceDispatcher) {
            if (!_isInitialized || nativeHandle == 0L) {
                return@withContext Result.failure(SttError.NotInitialized())
            }

            try {
                val json = nativeFinalizeWhisper(nativeHandle)
                if (json == null) {
                    val error = nativeGetLastErrorWhisper(nativeHandle) ?: "Finalization failed"
                    return@withContext Result.failure(SttError.ProcessingFailed(error))
                }
                Result.success(TranscriptResult.fromJson(json, SttEngineType.WHISPER))
            } catch (e: Exception) {
                Result.failure(SttError.ProcessingFailed(e.message ?: "Unknown error"))
            }
        }

    override suspend fun reset(): Result<Unit> = withContext(inferenceDispatcher) {
        if (nativeHandle == 0L) return@withContext Result.failure(SttError.NotInitialized())
        try {
            nativeResetWhisper(nativeHandle)
            accumulatedAudioMs = 0L
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    override suspend fun release() = withContext(inferenceDispatcher) {
        releaseInternal()
    }

    private fun releaseInternal() {
        if (nativeHandle != 0L) {
            try {
                nativeDestroyWhisper(nativeHandle)
            } catch (e: Exception) { }
            nativeHandle = 0L
        }
        _isInitialized = false
        _currentModel = null
        _currentConfig = null
        accumulatedAudioMs = 0L
    }

    override suspend fun transcribeBatch(
        samples: ShortArray, 
        sampleRate: Int,
        onProgress: ((Float) -> Unit)?
    ): Result<TranscriptResult> = withContext(inferenceDispatcher) {
        if (!_isInitialized || nativeHandle == 0L) {
            return@withContext Result.failure(SttError.NotInitialized())
        }

        if (samples.isEmpty()) {
            return@withContext Result.success(TranscriptResult.EMPTY)
        }

        try {
            // Lightweight validation (only in debug builds)
            if (BuildConfig.DEBUG) {
                val nonZeroCount = samples.asSequence().take(100).count { it != 0.toShort() }
                if (nonZeroCount == 0) {
                    android.util.Log.w(TAG, "Audio appears to be silent (first 100 samples are zero)")
                }
            }
            
            // Report initial progress
            onProgress?.invoke(0.1f)
            
            val json = nativeTranscribeBatchWhisper(nativeHandle, samples, samples.size, sampleRate)
            
            // Report completion
            onProgress?.invoke(1.0f)
            
            if (json == null) {
                val error = nativeGetLastErrorWhisper(nativeHandle) ?: "Batch transcription failed"
                return@withContext Result.failure(SttError.ProcessingFailed(error))
            }
            
            Result.success(TranscriptResult.fromJson(json, SttEngineType.WHISPER))
        } catch (e: Exception) {
            Result.failure(SttError.ProcessingFailed(e.message ?: "Unknown error"))
        }
    }

    fun isProcessing(): Boolean = nativeHandle != 0L && nativeIsProcessingWhisper(nativeHandle)
    
    /**
     * Cancel the current transcription operation.
     * This is thread-safe and can be called from any thread.
     * The engine will stop at the next safe point and return partial results.
     */
    fun cancel() {
        if (nativeHandle != 0L) {
            nativeCancelWhisper(nativeHandle)
        }
    }
    
    /**
     * Check if cancellation has been requested.
     */
    fun isCancelled(): Boolean = nativeHandle != 0L && nativeIsCancelledWhisper(nativeHandle)
}

// Placeholder for BuildConfig - in real project this would be generated
private object BuildConfig {
    const val DEBUG = false  // Set to true for debug builds
}

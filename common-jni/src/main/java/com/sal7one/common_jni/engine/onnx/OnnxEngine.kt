package com.sal7one.common_jni.engine.onnx

import com.sal7one.common_jni.engine.SttEngine
import com.sal7one.common_jni.engine.SttError
import com.sal7one.common_jni.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.jvm.Throws

/**
 * ONNX Runtime STT engine implementation.
 *
 * Uses ONNX Runtime for running Whisper or other STT models in ONNX format.
 * Benefits:
 * - Cross-platform model format
 * - Hardware acceleration support
 * - Quantized model support
 *
 * CHANGES:
 * - Removed Kotlin mutex (native code is already thread-safe)
 * - Uses Dispatchers.Default for CPU-bound inference
 * - Added @Throws annotations
 * - Fixed duplicate transcribeBatch method
 */
class OnnxEngine : SttEngine {

    private var nativeHandle: Long = 0L
    private var _isInitialized = false
    private var _currentModel: ModelInfo? = null
    private var _currentConfig: SttConfig? = null
    private var accumulatedAudioMs = 0L
    
    // Use Default dispatcher for CPU-bound inference work
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)

    override val engineType: SttEngineType = SttEngineType.ONNX
    override val isInitialized: Boolean get() = _isInitialized
    override val currentModel: ModelInfo? get() = _currentModel
    
    override val capabilities: EngineCapabilities
        get() = EngineCapabilities.ONNX

    companion object {
        private const val TAG = "OnnxEngine"

        @Volatile
        private var libraryLoaded = false

        @Volatile
        private var libraryError: String? = null

        init {
            try {
                System.loadLibrary("common_jni")
                libraryLoaded = nativeIsOnnxAvailable()
            } catch (e: UnsatisfiedLinkError) {
                libraryLoaded = false
                libraryError = e.message
            }
        }

        fun isAvailable(): Boolean = libraryLoaded
        fun getLibraryError(): String? = libraryError

        @JvmStatic
        private external fun nativeIsOnnxAvailable(): Boolean
    }

    // Native methods
    private external fun nativeCreateOnnx(): Long
    private external fun nativeDestroyOnnx(handle: Long)
    private external fun nativeInitOnnx(handle: Long, modelPath: String, configJson: String): Boolean
    private external fun nativePushAudioOnnx(handle: Long, samples: ShortArray, count: Int, sampleRate: Int): Int
    private external fun nativeGetPartialOnnx(handle: Long): String?
    private external fun nativeFinalizeOnnx(handle: Long): String?
    private external fun nativeResetOnnx(handle: Long)
    private external fun nativeTranscribeBatchOnnx(handle: Long, samples: ShortArray, count: Int, sampleRate: Int): String?
    private external fun nativeGetLastErrorOnnx(handle: Long): String?
    
    // Cancellation support (P0 from FFmpeg-Kit analysis)
    private external fun nativeCancelOnnx(handle: Long)
    private external fun nativeIsCancelledOnnx(handle: Long): Boolean
    
    /**
     * Request cancellation of current operation.
     * Thread-safe - can be called from any thread.
     */
    fun cancel() {
        if (nativeHandle != 0L) {
            nativeCancelOnnx(nativeHandle)
        }
    }
    
    /**
     * Check if cancellation has been requested.
     */
    fun isCancelled(): Boolean {
        return nativeHandle != 0L && nativeIsCancelledOnnx(nativeHandle)
    }

    @Throws(SttError::class)
    override suspend fun initialize(modelPath: String, config: SttConfig): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (!libraryLoaded) {
                return@withContext Result.failure(
                    SttError.LibraryNotLoaded(libraryError ?: "ONNX library not loaded")
                )
            }

            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                return@withContext Result.failure(SttError.ModelNotFound(modelPath))
            }

            releaseInternal()

            try {
                nativeHandle = nativeCreateOnnx()
                if (nativeHandle == 0L) {
                    return@withContext Result.failure(
                        SttError.InitializationFailed("Failed to create ONNX engine")
                    )
                }

                val success = nativeInitOnnx(nativeHandle, modelPath, config.toJson())
                if (!success) {
                    val error = nativeGetLastErrorOnnx(nativeHandle) ?: "Unknown error"
                    releaseInternal()
                    return@withContext Result.failure(SttError.InitializationFailed(error))
                }

                _isInitialized = true
                _currentConfig = config
                _currentModel = ModelInfo(
                    path = modelPath,
                    name = modelFile.name,
                    type = ModelInfo.ModelSize.UNKNOWN,
                    engineType = SttEngineType.ONNX,
                    sizeBytes = modelFile.length()
                )
                accumulatedAudioMs = 0L

                Result.success(Unit)
            } catch (e: Exception) {
                releaseInternal()
                Result.failure(SttError.InitializationFailed(e.message ?: "Unknown error"))
            }
        }

    @Throws(SttError::class)
    override suspend fun pushAudioChunk(chunk: AudioChunk): Result<Unit> =
        withContext(inferenceDispatcher) {
            if (!_isInitialized || nativeHandle == 0L) {
                return@withContext Result.failure(SttError.NotInitialized())
            }

            if (chunk.isEmpty) return@withContext Result.success(Unit)

            try {
                val result = nativePushAudioOnnx(nativeHandle, chunk.samples, chunk.sampleCount, chunk.sampleRate)
                if (result < 0) {
                    val error = nativeGetLastErrorOnnx(nativeHandle) ?: "Push failed"
                    return@withContext Result.failure(SttError.ProcessingFailed(error))
                }
                accumulatedAudioMs += chunk.durationMs
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(SttError.ProcessingFailed(e.message ?: "Unknown error"))
            }
        }

    override suspend fun getPartialTranscript(): PartialTranscript? =
        withContext(inferenceDispatcher) {
            if (!_isInitialized || nativeHandle == 0L) return@withContext null

            try {
                nativeGetPartialOnnx(nativeHandle)?.takeIf { it.isNotBlank() }?.let {
                    PartialTranscript(it.trim(), System.currentTimeMillis(), false)
                }
            } catch (e: Exception) {
                null
            }
        }

    @Throws(SttError::class)
    override suspend fun finalize(): Result<TranscriptResult> =
        withContext(inferenceDispatcher) {
            if (!_isInitialized || nativeHandle == 0L) {
                return@withContext Result.failure(SttError.NotInitialized())
            }

            try {
                val json = nativeFinalizeOnnx(nativeHandle)
                if (json == null) {
                    val error = nativeGetLastErrorOnnx(nativeHandle) ?: "Finalization failed"
                    return@withContext Result.failure(SttError.ProcessingFailed(error))
                }
                Result.success(TranscriptResult.fromJson(json, SttEngineType.ONNX))
            } catch (e: Exception) {
                Result.failure(SttError.ProcessingFailed(e.message ?: "Unknown error"))
            }
        }

    override suspend fun reset(): Result<Unit> =
        withContext(inferenceDispatcher) {
            if (nativeHandle != 0L) {
                try { nativeResetOnnx(nativeHandle) } catch (e: Exception) { }
            }
            accumulatedAudioMs = 0L
            Result.success(Unit)
        }

    override suspend fun release() = withContext(Dispatchers.IO) {
        releaseInternal()
    }
    
    private fun releaseInternal() {
        if (nativeHandle != 0L) {
            try {
                nativeDestroyOnnx(nativeHandle)
            } catch (e: Exception) { }
            nativeHandle = 0L
        }
        _isInitialized = false
        _currentModel = null
        _currentConfig = null
        accumulatedAudioMs = 0L
    }

    @Throws(SttError::class)
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
            onProgress?.invoke(0.1f)

            val json = nativeTranscribeBatchOnnx(nativeHandle, samples, samples.size, sampleRate)

            onProgress?.invoke(1.0f)

            if (json == null) {
                val error = nativeGetLastErrorOnnx(nativeHandle) ?: "Batch failed"
                return@withContext Result.failure(SttError.ProcessingFailed(error))
            }

            // Update accumulated time for segment timing
            accumulatedAudioMs = (samples.size.toLong() * 1000) / sampleRate
            val result = TranscriptResult.fromJson(json, SttEngineType.ONNX)

            Result.success(result)
        } catch (e: Exception) {
            Result.failure(SttError.ProcessingFailed(e.message ?: "Unknown error"))
        }
    }
}

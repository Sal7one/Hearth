package com.sal7one.common_jni.engine

import com.sal7one.common_jni.model.*
import kotlinx.coroutines.flow.Flow

/**
 * Core STT engine interface.
 * All engine implementations (Whisper, Vosk, ONNX) implement this interface.
 *
 * Thread Safety: All methods are suspend functions called from coroutine context.
 * Implementations handle their own synchronization.
 *
 * Lifecycle:
 * 1. Create via SttEngineFactory
 * 2. initialize() with model path
 * 3. Streaming: pushAudioChunk() -> getPartialTranscript() -> finalize()
 * 4. Batch: transcribeBatch()
 * 5. reset() for new session
 * 6. release() when done
 */
interface SttEngine {

    /** Engine type identifier */
    val engineType: SttEngineType

    /** True if engine is ready to process */
    val isInitialized: Boolean

    /** Current model info or null */
    val currentModel: ModelInfo?
    
    /** 
     * Engine capabilities - query what features this engine supports.
     * This allows UI to show/hide features based on engine selection.
     */
    val capabilities: EngineCapabilities
        get() = EngineCapabilities.BASIC  // Default implementation

    /**
     * Initialize with model file.
     */
    suspend fun initialize(modelPath: String, config: SttConfig): Result<Unit>

    /**
     * Push audio chunk for streaming.
     */
    suspend fun pushAudioChunk(chunk: AudioChunk): Result<Unit>

    /**
     * Get current partial transcript.
     */
    suspend fun getPartialTranscript(): PartialTranscript?

    /**
     * Finalize and get complete result.
     */
    suspend fun finalize(): Result<TranscriptResult>

    /**
     * Reset state for new session (keeps model).
     */
    suspend fun reset(): Result<Unit>

    /**
     * Release all resources.
     */
    suspend fun release()

    /**
     * Batch transcription (faster for short audio).
     * 
     * @param samples 16-bit PCM audio samples
     * @param sampleRate Sample rate in Hz (typically 16000)
     * @param onProgress Optional progress callback (0.0 to 1.0)
     */
    suspend fun transcribeBatch(
        samples: ShortArray, 
        sampleRate: Int,
        onProgress: ((Float) -> Unit)? = null
    ): Result<TranscriptResult>
    
    // Backward compatible overload without progress
    suspend fun transcribeBatch(samples: ShortArray, sampleRate: Int): Result<TranscriptResult> =
        transcribeBatch(samples, sampleRate, null)
}

/**
 * Extended interface for engines with streaming flow support.
 */
interface StreamingSttEngine : SttEngine {
    /** Flow of partial transcripts */
    val partialTranscripts: Flow<PartialTranscript>

    /** Flow of completed segments */
    val completedSegments: Flow<TranscriptSegment>
}

/**
 * Factory for creating engine instances.
 */
interface SttEngineFactory {
    /**
     * Create engine of specified type.
     */
    fun create(type: SttEngineType): Result<SttEngine>

    /**
     * Check if engine type is available.
     */
    fun isAvailable(type: SttEngineType): Boolean

    /**
     * Get all available engines.
     */
    fun availableEngines(): List<SttEngineType>

    /**
     * Get system capabilities.
     */
    fun getCapabilities(): SttCapabilities
}

/**
 * STT error types.
 */
sealed class SttError : Exception() {
    data class InitializationFailed(override val message: String) : SttError()
    data class ModelNotFound(val path: String) : SttError() {
        override val message: String = "Model not found: $path"
    }
    data class NotInitialized(override val message: String = "Engine not initialized") : SttError()
    data class ProcessingFailed(override val message: String) : SttError()
    data class InvalidAudio(override val message: String) : SttError()
    data class EngineNotAvailable(val type: SttEngineType) : SttError() {
        override val message: String = "${type.displayName} engine not available"
    }
    data class LibraryNotLoaded(override val message: String) : SttError()
    data class Cancelled(override val message: String = "Operation cancelled") : SttError()
}

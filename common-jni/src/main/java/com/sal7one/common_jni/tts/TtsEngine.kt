package com.sal7one.common_jni.tts

import com.sal7one.common_jni.json.JsonInterop
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * TTS Engine types matching native enum.
 * Values must stay in sync with `TtsEngineType` in `tts_engine_interface.h`.
 * Value 0 is intentionally skipped (legacy VibeVoice slot, removed).
 */
enum class TtsEngineType(val value: Int, val displayName: String) {
    SUPERTONIC(1, "Supertonic"),
    KOKORO(2, "Kokoro"),
    SHERPA_ONNX(3, "sherpa-onnx");

    companion object {
        fun fromInt(value: Int) = values().firstOrNull { it.value == value }
    }
}

/**
 * TTS quality modes.
 */
enum class TtsQualityMode(val value: Int) {
    LOW_LATENCY(0),
    BALANCED(1),
    HIGH_QUALITY(2);
}

/**
 * TTS Engine capabilities (mirrors STT EngineCapabilities pattern).
 */
data class TtsCapabilities(
    val batchSynthesis: Boolean = true,
    val streamingPush: Boolean = false,
    val multiSpeaker: Boolean = false,
    val voiceCloning: Boolean = false,
    val customVoice: Boolean = false,
    val multiLanguage: Boolean = false,
    val wordTimestamps: Boolean = false,
    val ssmlSupport: Boolean = false,
    val prosodyControl: Boolean = false,
) {
    companion object {
        val BASIC = TtsCapabilities()
        val STREAMING = TtsCapabilities(streamingPush = true)
        val FULL = TtsCapabilities(
            batchSynthesis = true,
            streamingPush = true,
            multiSpeaker = true,
            voiceCloning = true,
            customVoice = true,
            multiLanguage = true,
            wordTimestamps = true,
            ssmlSupport = true,
            prosodyControl = true
        )
    }
}

/**
 * Voice information.
 */
data class TtsVoiceInfo(
    val id: String,
    val displayName: String,
    val language: String,
    val gender: String,
    val style: String = "neutral",
    val isDefault: Boolean = false,
    val isCustom: Boolean = false
)

/**
 * TTS configuration.
 */
data class TtsConfig(
    val modelPath: String,
    val vocabPath: String = "",
    val voicePromptPath: String = "",
    val qualityMode: TtsQualityMode = TtsQualityMode.BALANCED,
    val sampleRate: Int = 24000,
    val numThreads: Int = 4,
    val useGpu: Boolean = false,
    val maxCacheLength: Int = 4096,
    val tokensPerStep: Int = 4,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
) {
    fun toJson(): String {
        require(sampleRate in 8_000..192_000) { "sampleRate must be in [8000,192000]" }
        require(numThreads in 1..8) { "numThreads must be in [1,8]" }
        require(maxCacheLength in 1..1_048_576) { "maxCacheLength is out of range" }
        require(tokensPerStep in 1..1_024) { "tokensPerStep is out of range" }
        require(speed.isFinite() && speed in 0.25f..4f) { "speed must be finite and in [0.25,4]" }
        require(pitch.isFinite() && pitch in 0.25f..4f) { "pitch must be finite and in [0.25,4]" }
        require(volume.isFinite() && volume in 0f..1f) { "volume must be finite and in [0,1]" }
        require(vocabPath.toByteArray(Charsets.UTF_8).size <= 4096) { "vocabPath is too long" }
        require('\u0000' !in vocabPath) { "vocabPath cannot contain NUL" }
        require(voicePromptPath.toByteArray(Charsets.UTF_8).size <= 4096) {
            "voicePromptPath is too long"
        }
        require('\u0000' !in voicePromptPath) { "voicePromptPath cannot contain NUL" }

        val json = JSONObject()
            .put("qualityMode", qualityMode.value)
            .put("sampleRate", sampleRate)
            .put("numThreads", numThreads)
            .put("useGpu", useGpu)
            .put("maxCacheLength", maxCacheLength)
            .put("tokensPerStep", tokensPerStep)
            .put("speed", speed)
            .put("pitch", pitch)
            .put("volume", volume)
            .put("vocabPath", vocabPath)
            .put("voicePromptPath", voicePromptPath)
        return JsonInterop.asciiString(json)
    }
}

/**
 * Audio chunk from streaming synthesis.
 */
data class TtsAudioChunk(
    val samples: FloatArray,
    val sampleRate: Int,
    val timestampMs: Long,
    val isLast: Boolean,
    val wordTimings: List<WordTiming> = emptyList()
) {
    data class WordTiming(
        val word: String,
        val startMs: Long,
        val endMs: Long
    )
    
    fun toPcm16(): ShortArray {
        return ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TtsAudioChunk
        return samples.contentEquals(other.samples) &&
               sampleRate == other.sampleRate &&
               timestampMs == other.timestampMs &&
               isLast == other.isLast
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + isLast.hashCode()
        return result
    }
}

/**
 * TTS synthesis result.
 */
data class TtsResult(
    val audio: FloatArray,
    val sampleRate: Int,
    val durationMs: Long
) {
    fun toPcm16(): ShortArray {
        return ShortArray(audio.size) { i ->
            (audio[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TtsResult
        return audio.contentEquals(other.audio) &&
               sampleRate == other.sampleRate &&
               durationMs == other.durationMs
    }

    override fun hashCode(): Int {
        var result = audio.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + durationMs.hashCode()
        return result
    }
}

/**
 * Core TTS engine interface.
 * All engine implementations implement this interface.
 * 
 * Thread Safety: All methods are suspend functions called from coroutine context.
 * 
 * Lifecycle:
 * 1. Create via TtsEngineFactory
 * 2. initialize() with config
 * 3a. Batch: synthesize(text)
 * 3b. Streaming: synthesizeStreaming(text) -> Flow<TtsAudioChunk>
 * 4. reset() for new session
 * 5. release() when done
 */
interface TtsEngine {
    
    /** Engine type identifier */
    val engineType: TtsEngineType
    
    /** True if engine is ready to process */
    val isInitialized: Boolean
    
    /** Output sample rate */
    val sampleRate: Int
    
    /** Engine capabilities */
    val capabilities: TtsCapabilities
        get() = TtsCapabilities.BASIC
    
    /**
     * Initialize with configuration.
     */
    suspend fun initialize(config: TtsConfig): Result<Unit>
    
    /**
     * Synthesize text to audio (batch mode).
     */
    suspend fun synthesize(text: String): Result<TtsResult>
    
    /**
     * Synthesize with streaming output.
     * Only available if capabilities.streamingPush is true.
     */
    fun synthesizeStreaming(text: String): Flow<TtsAudioChunk>
    
    /**
     * Get available voices.
     */
    suspend fun getVoices(): List<TtsVoiceInfo>
    
    /**
     * Set active voice by ID.
     */
    suspend fun setVoice(voiceId: String): Result<Unit>
    
    /**
     * Get current voice ID.
     */
    suspend fun getCurrentVoice(): String?
    
    /**
     * Load custom voice from file (for voice cloning).
     * Only available if capabilities.voiceCloning or capabilities.customVoice is true.
     */
    suspend fun loadCustomVoice(voicePath: String): Result<Unit>
    
    /**
     * Reset state for new session (keeps model).
     */
    suspend fun reset(): Result<Unit>
    
    /**
     * Cancel current operation.
     */
    fun cancel()
    
    /**
     * Release all resources.
     */
    suspend fun release()
}

/**
 * Extended interface for engines with native streaming support.
 */
interface StreamingTtsEngine : TtsEngine {
    
    /** Flow of audio chunks during streaming */
    val audioChunks: Flow<TtsAudioChunk>
    
    /** Current sequence length (tokens processed) */
    val sequenceLength: Int
    
    /** Remaining cache capacity (tokens) */
    val remainingCapacity: Int
}

/**
 * Factory for creating TTS engine instances.
 */
interface TtsEngineFactory {
    
    /**
     * Create engine of specified type.
     */
    fun create(type: TtsEngineType): Result<TtsEngine>
    
    /**
     * Check if engine type is available.
     */
    fun isAvailable(type: TtsEngineType): Boolean
    
    /**
     * Get all available engines.
     */
    fun availableEngines(): List<TtsEngineType>
}

/**
 * TTS error types (mirrors STT SttError pattern).
 */
sealed class TtsError : Exception() {
    data class InitializationFailed(override val message: String) : TtsError()
    data class ModelNotFound(val path: String) : TtsError() {
        override val message: String = "Model not found: $path"
    }
    data class NotInitialized(override val message: String = "Engine not initialized") : TtsError()
    data class SynthesisFailed(override val message: String) : TtsError()
    data class VoiceNotFound(val voiceId: String) : TtsError() {
        override val message: String = "Voice not found: $voiceId"
    }
    data class EngineNotAvailable(val type: TtsEngineType) : TtsError() {
        override val message: String = "${type.displayName} engine not available"
    }
    data class LibraryNotLoaded(override val message: String) : TtsError()
    data class Cancelled(override val message: String = "Operation cancelled") : TtsError()
    data class StreamingNotSupported(override val message: String = "Streaming not supported by this engine") : TtsError()
}

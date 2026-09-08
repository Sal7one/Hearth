package com.sal7one.common_jni.model

import com.sal7one.common_jni.json.JsonInterop
import org.json.JSONObject

/**
 * Available STT engine types.
 * Each engine is optional and may or may not be compiled in.
 */
enum class SttEngineType(val displayName: String, val bitFlag: Int) {
    WHISPER("Whisper.cpp", 1),
    VOSK("Vosk", 2),
    ONNX("ONNX Runtime", 4);

    companion object {
        fun fromBitFlag(flag: Int): SttEngineType? = entries.find { it.bitFlag == flag }
        
        fun fromMask(mask: Int): List<SttEngineType> = entries.filter { (mask and it.bitFlag) != 0 }
    }
}

/**
 * Audio decoder types for the audio pipeline.
 */
enum class AudioDecoderType(val displayName: String, val bitFlag: Int) {
    FFMPEG("FFmpeg", 8),
    ANDROID_MEDIA("Android MediaCodec", 16);

    companion object {
        fun fromMask(mask: Int): List<AudioDecoderType> = entries.filter { (mask and it.bitFlag) != 0 }
    }
}

/**
 * Audio processing mode - batch (full memory) or streaming (chunked).
 */
enum class AudioProcessingMode(val displayName: String) {
    BATCH("Batch (Full Memory)"),      // Load entire file into memory - fast for small files
    STREAMING("Streaming (Chunked)"),   // Process in chunks - safe for large files
    AUTO("Auto (Hybrid)")               // Auto-select based on file size
}

/**
 * Language configuration for STT engines.
 */
sealed class LanguageConfig {
    data object Auto : LanguageConfig()
    data class Specific(val code: String) : LanguageConfig()

    fun toCode(): String = when (this) {
        is Auto -> "auto"
        is Specific -> code
    }

    companion object {
        fun fromCode(code: String?): LanguageConfig = when {
            code.isNullOrBlank() || code == "auto" -> Auto
            else -> Specific(code)
        }
        
        // Common language codes
        const val ENGLISH = "en"
        const val ARABIC = "ar"
        const val SPANISH = "es"
        const val FRENCH = "fr"
        const val GERMAN = "de"
        const val CHINESE = "zh"
        const val JAPANESE = "ja"
        const val KOREAN = "ko"
        const val RUSSIAN = "ru"
    }
}

/**
 * STT engine configuration.
 */
data class SttConfig(
    val sampleRate: Int = SAMPLE_RATE_16K,
    val language: LanguageConfig = LanguageConfig.Auto,
    val enableTimestamps: Boolean = true,
    val enableVad: Boolean = true,
    val maxSegmentLengthMs: Int = 30000,
    val silenceThresholdDb: Float = -40.0f,
    val numThreads: Int = 4,
    val translateToEnglish: Boolean = false,
    // Whisper-specific configuration (PERMISSIVE defaults for quiet/musical audio)
    val noSpeechThreshold: Float = 0.8f,  // More permissive than default (0.6) for quiet audio
    val suppressBlank: Boolean = false,  // Allow blank segments (permissive default)
    val suppressNonSpeechTokens: Boolean = true,  // Suppress [MUSIC], [NOISE] tags
    val debugForceEnglish: Boolean = false,  // Debug mode: aggressive settings
    val debugLogging: Boolean = false,  // Verbose Whisper logging
    // Audio processing mode configuration
    val audioProcessingMode: AudioProcessingMode = AudioProcessingMode.AUTO,  // Auto-select batch vs streaming
    val streamingChunkDurationMs: Int = 200,  // Chunk duration for streaming mode (milliseconds) - reduced for faster updates
    val streamingContextDurationMs: Int = 0,  // Context retained between native inference chunks
    /** Pinned or TOFU SHA-256 for the app-private model snapshot. */
    val modelSha256: String? = null
) {
    companion object {
        const val SAMPLE_RATE_16K = 16000
        const val SAMPLE_RATE_48K = 48000
        
        val DEFAULT = SttConfig()
        
        fun forStreaming() = SttConfig(
            enableVad = true,
            maxSegmentLengthMs = 5000,
            audioProcessingMode = AudioProcessingMode.STREAMING
        )
        
        fun forBatch() = SttConfig(
            enableVad = false,
            maxSegmentLengthMs = 30000,
            audioProcessingMode = AudioProcessingMode.BATCH
        )
    }

    fun toJson(): String {
        val languageCode = language.toCode()
        require(sampleRate in 8_000..192_000) { "sampleRate must be in [8000,192000]" }
        require(languageCode.toByteArray(Charsets.UTF_8).size <= 64) { "language code is too long" }
        require('\u0000' !in languageCode) { "language code cannot contain NUL" }
        require(modelSha256 == null || modelSha256.matches(Regex("[0-9a-f]{64}"))) {
            "modelSha256 must contain 64 lowercase hexadecimal characters"
        }
        require(numThreads in 1..8) { "numThreads must be in [1,8]" }
        require(maxSegmentLengthMs in 100..3_600_000) {
            "maxSegmentLengthMs must be in [100,3600000]"
        }
        require(silenceThresholdDb.isFinite() && silenceThresholdDb in -160f..0f) {
            "silenceThresholdDb must be finite and in [-160,0]"
        }
        require(noSpeechThreshold.isFinite() && noSpeechThreshold in 0f..1f) {
            "noSpeechThreshold must be finite and in [0,1]"
        }
        require(streamingChunkDurationMs in 10..600_000) {
            "streamingChunkDurationMs must be in [10,600000]"
        }
        require(streamingContextDurationMs in 0..streamingChunkDurationMs) {
            "streamingContextDurationMs must be in [0,streamingChunkDurationMs]"
        }

        val json = JSONObject()
            .put("sampleRate", sampleRate)
            .put("language", languageCode)
            .put("enableTimestamps", enableTimestamps)
            .put("enableVad", enableVad)
            .put("maxSegmentLengthMs", maxSegmentLengthMs)
            .put("silenceThresholdDb", silenceThresholdDb)
            .put("numThreads", numThreads)
            .put("translate", translateToEnglish)
            .put("noSpeechThreshold", noSpeechThreshold)
            .put("suppressBlank", suppressBlank)
            .put("suppressNonSpeechTokens", suppressNonSpeechTokens)
            .put("debugForceEnglish", debugForceEnglish)
            .put("debugLogging", debugLogging)
            .put("audioProcessingMode", audioProcessingMode.name.lowercase())
            .put("streamingChunkDurationMs", streamingChunkDurationMs)
            .put("streamingContextDurationMs", streamingContextDurationMs)
        modelSha256?.let { json.put("modelSha256", it) }
        return JsonInterop.asciiString(json)
    }
}

/**
 * Model information for loaded STT models.
 */
data class ModelInfo(
    val path: String,
    val name: String,
    val type: ModelSize = ModelSize.UNKNOWN,
    val engineType: SttEngineType,
    val sizeBytes: Long = 0L
) {
    enum class ModelSize(val displayName: String) {
        TINY("Tiny"),
        BASE("Base"),
        SMALL("Small"),
        MEDIUM("Medium"),
        LARGE("Large"),
        UNKNOWN("Unknown");

        companion object {
            fun fromNativeType(type: Int): ModelSize = when (type) {
                0 -> TINY
                1 -> BASE
                2 -> SMALL
                3 -> MEDIUM
                4 -> LARGE
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Runtime capabilities of the STT system.
 */
data class SttCapabilities(
    val availableEngines: List<SttEngineType>,
    val availableDecoders: List<AudioDecoderType>,
    val capabilityMask: Int
) {
    fun hasEngine(type: SttEngineType): Boolean = type in availableEngines
    fun hasDecoder(type: AudioDecoderType): Boolean = type in availableDecoders
    
    val hasWhisper: Boolean get() = hasEngine(SttEngineType.WHISPER)
    val hasVosk: Boolean get() = hasEngine(SttEngineType.VOSK)
    val hasOnnx: Boolean get() = hasEngine(SttEngineType.ONNX)
    val hasFfmpeg: Boolean get() = hasDecoder(AudioDecoderType.FFMPEG)
    
    companion object {
        val EMPTY = SttCapabilities(emptyList(), emptyList(), 0)
        
        fun fromMask(mask: Int) = SttCapabilities(
            availableEngines = SttEngineType.fromMask(mask),
            availableDecoders = AudioDecoderType.fromMask(mask),
            capabilityMask = mask
        )
    }
}

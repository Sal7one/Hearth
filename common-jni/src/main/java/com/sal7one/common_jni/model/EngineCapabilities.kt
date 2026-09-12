package com.sal7one.common_jni.model

/**
 * Engine feature flags that match native EngineCapability enum.
 * 
 * Use these to query what features an engine supports before attempting
 * to use them. This allows the UI to show/hide options based on
 * engine capabilities.
 */
enum class EngineFeature(val bitFlag: Int) {
    /** Engine supports transcribeBatch() */
    BATCH_TRANSCRIPTION(1 shl 0),
    
    /** Engine supports pushAudioChunk() for streaming */
    STREAMING_PUSH(1 shl 1),
    
    /** Engine supports getPartialTranscript() */
    PARTIAL_RESULTS(1 shl 2),
    
    /** Segments include startMs/endMs timing */
    SEGMENT_TIMESTAMPS(1 shl 3),
    
    /** Word-level timestamps available (token timestamps in Whisper) */
    WORD_TIMESTAMPS(1 shl 4),
    
    /** Can auto-detect input language */
    LANGUAGE_DETECTION(1 shl 5),
    
    /** Can translate to English */
    TRANSLATION(1 shl 6),
    
    /** Supports multiple languages in same audio */
    MULTI_LANGUAGE(1 shl 7),
    
    /** Per-segment confidence scores */
    SEGMENT_CONFIDENCE(1 shl 8),
    
    /** Per-word confidence scores */
    WORD_CONFIDENCE(1 shl 9);

    companion object {
        /**
         * Convert native capability mask to set of features.
         */
        fun fromMask(mask: Int): Set<EngineFeature> =
            entries.filter { (mask and it.bitFlag) != 0 }.toSet()
        
        /**
         * Convert set of features to native capability mask.
         */
        fun toMask(features: Set<EngineFeature>): Int =
            features.fold(0) { acc, feature -> acc or feature.bitFlag }
    }
}

/**
 * Engine capabilities including features and supported languages.
 */
data class EngineCapabilities(
    val features: Set<EngineFeature>,
    val supportedLanguages: List<String> = emptyList()
) {
    /**
     * Check if engine supports a specific feature.
     */
    fun supports(feature: EngineFeature): Boolean = feature in features
    
    /**
     * Check if engine supports all specified features.
     */
    fun supportsAll(vararg requiredFeatures: EngineFeature): Boolean =
        requiredFeatures.all { it in features }
    
    /**
     * Check if engine supports any of the specified features.
     */
    fun supportsAny(vararg requiredFeatures: EngineFeature): Boolean =
        requiredFeatures.any { it in features }
    
    /** Convenience: Can do batch transcription */
    val canBatch: Boolean get() = supports(EngineFeature.BATCH_TRANSCRIPTION)
    
    /** Convenience: Can do streaming transcription */
    val canStream: Boolean get() = supports(EngineFeature.STREAMING_PUSH)
    
    /** Convenience: Can provide partial results during streaming */
    val hasPartials: Boolean get() = supports(EngineFeature.PARTIAL_RESULTS)
    
    /** Convenience: Can auto-detect language */
    val canDetectLanguage: Boolean get() = supports(EngineFeature.LANGUAGE_DETECTION)
    
    /** Convenience: Can translate to English */
    val canTranslate: Boolean get() = supports(EngineFeature.TRANSLATION)
    
    /** Convenience: Provides segment timing */
    val hasTimestamps: Boolean get() = supports(EngineFeature.SEGMENT_TIMESTAMPS)
    
    /**
     * Convert to native capability mask.
     */
    fun toMask(): Int = EngineFeature.toMask(features)
    
    companion object {
        /** No capabilities */
        val NONE = EngineCapabilities(emptySet())
        
        /** Basic STT: batch only */
        val BASIC = EngineCapabilities(
            features = setOf(
                EngineFeature.BATCH_TRANSCRIPTION,
                EngineFeature.SEGMENT_TIMESTAMPS
            )
        )
        
        /** Streaming STT: batch + streaming + partials */
        val STREAMING = EngineCapabilities(
            features = setOf(
                EngineFeature.BATCH_TRANSCRIPTION,
                EngineFeature.STREAMING_PUSH,
                EngineFeature.PARTIAL_RESULTS,
                EngineFeature.SEGMENT_TIMESTAMPS
            )
        )
        
        /** Full Whisper capabilities */
        val WHISPER = EngineCapabilities(
            features = setOf(
                EngineFeature.BATCH_TRANSCRIPTION,
                EngineFeature.STREAMING_PUSH,
                EngineFeature.PARTIAL_RESULTS,
                EngineFeature.SEGMENT_TIMESTAMPS,
                EngineFeature.WORD_TIMESTAMPS,
                EngineFeature.LANGUAGE_DETECTION,
                EngineFeature.TRANSLATION,
                EngineFeature.SEGMENT_CONFIDENCE
            ),
            supportedLanguages = listOf("auto") + com.sal7one.common_jni.language.LanguageCatalog.whisperCodes
        )
        
        /** Vosk capabilities */
        val VOSK = EngineCapabilities(
            features = setOf(
                EngineFeature.BATCH_TRANSCRIPTION,
                EngineFeature.STREAMING_PUSH,
                EngineFeature.PARTIAL_RESULTS,
                EngineFeature.SEGMENT_TIMESTAMPS
            )
            // Languages depend on loaded model
        )
        
        /** ONNX capabilities (framework - actual capabilities depend on model) */
        val ONNX = EngineCapabilities(
            features = setOf(
                EngineFeature.BATCH_TRANSCRIPTION,
                EngineFeature.STREAMING_PUSH,
                EngineFeature.SEGMENT_TIMESTAMPS
            )
        )
        
        /**
         * Create capabilities from native mask.
         */
        fun fromMask(mask: Int, languages: List<String> = emptyList()) = EngineCapabilities(
            features = EngineFeature.fromMask(mask),
            supportedLanguages = languages
        )
        
        /**
         * Get default capabilities for engine type.
         */
        fun forEngine(type: SttEngineType): EngineCapabilities = when (type) {
            SttEngineType.WHISPER -> WHISPER
            SttEngineType.VOSK -> VOSK
            SttEngineType.ONNX -> ONNX
        }
    }
}


package com.sal7one.common_jni.model

/**
 * STT processing mode - controls quality/speed tradeoff.
 *
 * Usage:
 * ```kotlin
 * val config = SttConfig(mode = SttMode.FAST)   // Quick results, lower accuracy
 * ```
 */
enum class SttMode(val nativeValue: Int) {
    /**
     * Fast mode - use lightweight engine (Vosk/small ONNX).
     * Best for: Real-time, low-end devices, quick previews.
     */
    FAST(0),
    
    /**
     * Balanced mode - good tradeoff between speed and accuracy.
     * Best for: Most use cases, general transcription.
     */
    BALANCED(1),
    
    /**
     * Accurate mode - use Whisper/large model.
     * Best for: Final transcripts, offline processing, high-quality output.
     */
    ACCURATE(2);
    
    companion object {
        fun fromNative(value: Int): SttMode = entries.find { it.nativeValue == value } ?: BALANCED
    }
}

/**
 * Quality level for generic processing (reusable across ML tasks).
 */
enum class Quality(val nativeValue: Int) {
    LOW(0),
    MEDIUM(1),
    HIGH(2);
    
    companion object {
        fun fromNative(value: Int): Quality = entries.find { it.nativeValue == value } ?: MEDIUM
    }
}


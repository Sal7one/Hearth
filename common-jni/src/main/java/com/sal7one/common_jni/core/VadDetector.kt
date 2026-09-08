package com.sal7one.common_jni.core

import com.sal7one.common_jni.json.JsonInterop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

data class VadSegment(
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
    val isSpeech: Boolean
)

data class VadConfig(
    val speechThresholdDb: Float = -30f,
    val silenceThresholdDb: Float = -45f,
    val minSpeechMs: Int = 250,
    val minSilenceMs: Int = 300,
    val windowMs: Int = 30,
    val sampleRate: Int = 16000
) {
    init {
        require(speechThresholdDb.isFinite() && speechThresholdDb in -160f..0f) {
            "speechThresholdDb must be finite and in [-160,0]"
        }
        require(silenceThresholdDb.isFinite() && silenceThresholdDb in -160f..0f) {
            "silenceThresholdDb must be finite and in [-160,0]"
        }
        require(speechThresholdDb > silenceThresholdDb) {
            "speechThresholdDb must be greater than silenceThresholdDb"
        }
        require(minSpeechMs in 0..3_600_000) { "minSpeechMs must be in [0,3600000]" }
        require(minSilenceMs in 0..3_600_000) { "minSilenceMs must be in [0,3600000]" }
        require(windowMs in 1..10_000) { "windowMs must be in [1,10000]" }
        require(sampleRate in 1..384_000) { "sampleRate must be in [1,384000]" }
        require(windowMs.toLong() * sampleRate / 1000L >= 1L) {
            "windowMs and sampleRate must produce a positive window size"
        }
    }
}

internal fun parseVadSegments(json: String?): List<VadSegment> {
    if (json.isNullOrBlank()) return emptyList()
    val values = JsonInterop.arrayOrNull(json) ?: return emptyList()
    val segments = mutableListOf<VadSegment>()
    for (index in 0 until values.length()) {
        val segment = JsonInterop.objectOrNull(values, index) ?: continue
        val startMs = JsonInterop.longOrNull(segment, "startMs") ?: continue
        val endMs = JsonInterop.longOrNull(segment, "endMs") ?: continue
        segments.add(VadSegment(
            startMs = startMs,
            endMs = endMs,
            confidence = JsonInterop.floatOrDefault(segment, "confidence", 0.9f),
            isSpeech = JsonInterop.boolOrDefault(segment, "isSpeech", true)
        ))
    }
    return segments
}

/**
 * Real-time Voice Activity Detection.
 * 
 * Usage:
 * ```
 * val vad = VadDetector()
 * vad.segments.collect { segment ->
 *     if (segment.isSpeech) {
 *         // Speech detected from segment.startMs to segment.endMs
 *     }
 * }
 * 
 * // Push audio chunks
 * vad.pushAudio(samples, sampleRate)
 * 
 * // Finalize
 * vad.finalize()
 * ```
 */
class VadDetector(private val config: VadConfig = VadConfig()) {
    
    private var nativeHandle: Long = 0L
    private val _segments = MutableSharedFlow<VadSegment>(extraBufferCapacity = 64)
    
    val segments: Flow<VadSegment> = _segments.asSharedFlow()
    
    val isSpeaking: Boolean
        get() = if (nativeHandle != 0L) nativeIsSpeaking(nativeHandle) else false
    
    val currentEnergyDb: Float
        get() = if (nativeHandle != 0L) nativeGetCurrentEnergyDb(nativeHandle) else -100f
    
    init {
        nativeHandle = nativeCreate(
            config.speechThresholdDb,
            config.silenceThresholdDb,
            config.minSpeechMs,
            config.minSilenceMs,
            config.windowMs,
            config.sampleRate
        )
    }
    
    /**
     * Process audio and detect speech segments.
     */
    suspend fun process(samples: ShortArray): List<VadSegment> = withContext(Dispatchers.Default) {
        if (nativeHandle == 0L) return@withContext emptyList()
        
        val json = nativeProcess(nativeHandle, samples, samples.size)
        parseVadSegments(json).also { segments ->
            segments.forEach { _segments.tryEmit(it) }
        }
    }
    
    /**
     * Push audio for streaming VAD.
     */
    suspend fun pushAudio(samples: ShortArray) = withContext(Dispatchers.Default) {
        if (nativeHandle == 0L) return@withContext
        
        nativePushAudio(nativeHandle, samples, samples.size)
        
        // Check for new segments
        val json = nativeGetSegments(nativeHandle)
        parseVadSegments(json).forEach { _segments.tryEmit(it) }
    }
    
    /**
     * Finalize VAD processing and get any remaining segment.
     */
    suspend fun finalizeVad(): VadSegment? = withContext(Dispatchers.Default) {
        if (nativeHandle == 0L) return@withContext null
        
        val json = nativeFinalize(nativeHandle)
        parseVadSegments(json).firstOrNull()?.also { _segments.tryEmit(it) }
    }
    
    /**
     * Reset state for new audio.
     */
    fun reset() {
        if (nativeHandle != 0L) {
            nativeReset(nativeHandle)
        }
    }
    
    /**
     * Release resources.
     */
    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }
    
    protected fun finalize() {
        release()
    }
    
    // Native methods
    private external fun nativeCreate(
        speechThresholdDb: Float,
        silenceThresholdDb: Float,
        minSpeechMs: Int,
        minSilenceMs: Int,
        windowMs: Int,
        sampleRate: Int
    ): Long
    
    private external fun nativeDestroy(handle: Long)
    private external fun nativeProcess(handle: Long, samples: ShortArray, count: Int): String?
    private external fun nativePushAudio(handle: Long, samples: ShortArray, count: Int)
    private external fun nativeGetSegments(handle: Long): String?
    private external fun nativeFinalize(handle: Long): String?
    private external fun nativeReset(handle: Long)
    private external fun nativeIsSpeaking(handle: Long): Boolean
    private external fun nativeGetCurrentEnergyDb(handle: Long): Float
    
    companion object {
        init {
            System.loadLibrary("common_jni")
        }
    }
}

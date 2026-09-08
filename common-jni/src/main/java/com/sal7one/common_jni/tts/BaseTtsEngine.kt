package com.sal7one.common_jni.tts

import com.sal7one.common_jni.json.JsonInterop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Generic TTS engine backed by the native TtsNative router.
 *
 * One concrete class handles every engine type — the engine-specific
 * behaviour lives on the native side. Subclass only if you need to
 * override capabilities or streaming behaviour.
 */
open class BaseTtsEngine(
    override val engineType: TtsEngineType
) : TtsEngine {
    
    protected var nativeHandle: Long = 0
    protected var config: TtsConfig? = null
    
    override val isInitialized: Boolean
        get() = nativeHandle != 0L && TtsNative.nativeIsInitialized(nativeHandle)
    
    override val sampleRate: Int
        get() = if (nativeHandle != 0L) {
            TtsNative.nativeGetSampleRate(nativeHandle)
        } else {
            24000
        }
    
    override val capabilities: TtsCapabilities
        get() = TtsCapabilities.BASIC
    
    override suspend fun initialize(config: TtsConfig): Result<Unit> {
        return withContext(Dispatchers.Default) {
            try {
                // Release existing handle if any
                if (nativeHandle != 0L) {
                    TtsNative.nativeRelease(nativeHandle)
                    nativeHandle = 0
                }
                
                // Create engine
                nativeHandle = TtsNative.nativeCreateEngine(engineType.value)
                if (nativeHandle <= 0) {
                    return@withContext Result.failure(
                        TtsError.InitializationFailed("Failed to create ${engineType.displayName} engine")
                    )
                }
                
                // Initialize with config
                val success = TtsNative.nativeInitialize(
                    nativeHandle,
                    config.modelPath,
                    config.toJson()
                )
                
                if (!success) {
                    val error = TtsNative.nativeGetLastError()
                    TtsNative.nativeRelease(nativeHandle)
                    nativeHandle = 0
                    return@withContext Result.failure(
                        TtsError.InitializationFailed("Initialization failed: $error")
                    )
                }
                
                this@BaseTtsEngine.config = config
                Result.success(Unit)
                
            } catch (e: Exception) {
                Result.failure(TtsError.InitializationFailed("Init error: ${e.message}"))
            }
        }
    }
    
    override suspend fun synthesize(text: String): Result<TtsResult> {
        return withContext(Dispatchers.Default) {
            try {
                if (!isInitialized) {
                    return@withContext Result.failure(TtsError.NotInitialized())
                }
                
                val audio = TtsNative.nativeSynthesize(nativeHandle, text, 0)
                
                if (audio == null || audio.isEmpty()) {
                    val error = TtsNative.nativeGetLastError()
                    return@withContext Result.failure(
                        TtsError.SynthesisFailed("Synthesis failed: $error")
                    )
                }
                
                val actualSampleRate = TtsNative.nativeGetSampleRate(nativeHandle)
                val durationMs = (audio.size * 1000L) / actualSampleRate
                
                Result.success(TtsResult(audio, actualSampleRate, durationMs))
                
            } catch (e: Exception) {
                Result.failure(TtsError.SynthesisFailed("Synthesis error: ${e.message}"))
            }
        }
    }
    
    override fun synthesizeStreaming(text: String): Flow<TtsAudioChunk> = flow {
        if (!capabilities.streamingPush) {
            // Fallback: split text and synthesize chunks
            val sentences = splitSentences(text)
            var timestampMs = 0L
            
            for ((index, sentence) in sentences.withIndex()) {
                if (sentence.isBlank()) continue
                
                val result = synthesize(sentence).getOrNull() ?: continue
                
                emit(TtsAudioChunk(
                    samples = result.audio,
                    sampleRate = result.sampleRate,
                    timestampMs = timestampMs,
                    isLast = index == sentences.lastIndex
                ))
                
                timestampMs += result.durationMs
            }
        } else {
            // Native streaming (implemented in subclasses)
            throw TtsError.StreamingNotSupported()
        }
    }
    
    override suspend fun getVoices(): List<TtsVoiceInfo> {
        return withContext(Dispatchers.Default) {
            if (!isInitialized) return@withContext emptyList()
            
            val json = TtsNative.nativeGetVoices(nativeHandle)
            parseVoicesJson(json)
        }
    }
    
    override suspend fun setVoice(voiceId: String): Result<Unit> {
        return withContext(Dispatchers.Default) {
            if (!isInitialized) {
                return@withContext Result.failure(TtsError.NotInitialized())
            }
            
            val success = TtsNative.nativeSetVoice(nativeHandle, voiceId)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(TtsError.VoiceNotFound(voiceId))
            }
        }
    }
    
    override suspend fun getCurrentVoice(): String? {
        return withContext(Dispatchers.Default) {
            if (!isInitialized) null
            else TtsNative.nativeGetCurrentVoice(nativeHandle)
        }
    }
    
    override suspend fun loadCustomVoice(voicePath: String): Result<Unit> {
        return withContext(Dispatchers.Default) {
            if (!isInitialized) {
                return@withContext Result.failure(TtsError.NotInitialized())
            }
            
            if (!capabilities.voiceCloning && !capabilities.customVoice) {
                return@withContext Result.failure(
                    TtsError.SynthesisFailed("Custom voices not supported by this engine")
                )
            }
            
            val success = TtsNative.nativeLoadCustomVoice(nativeHandle, voicePath)
            if (success) {
                Result.success(Unit)
            } else {
                val error = TtsNative.nativeGetLastError()
                Result.failure(TtsError.SynthesisFailed("Failed to load voice: $error"))
            }
        }
    }
    
    override suspend fun reset(): Result<Unit> {
        return withContext(Dispatchers.Default) {
            try {
                if (nativeHandle != 0L) {
                    TtsNative.nativeReset(nativeHandle)
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(TtsError.SynthesisFailed("Reset failed: ${e.message}"))
            }
        }
    }
    
    override fun cancel() {
        if (nativeHandle != 0L) {
            TtsNative.nativeCancel(nativeHandle)
        }
    }
    
    override suspend fun release() {
        withContext(Dispatchers.Default) {
            if (nativeHandle != 0L) {
                TtsNative.nativeRelease(nativeHandle)
                nativeHandle = 0
            }
        }
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    /**
     * Split text into sentences for pseudo-streaming.
     */
    protected fun splitSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val pattern = """[.!?]+\s*|\n+""".toRegex()
        
        var lastEnd = 0
        pattern.findAll(text).forEach { match ->
            val sentence = text.substring(lastEnd, match.range.last + 1).trim()
            if (sentence.isNotEmpty()) {
                sentences.add(sentence)
            }
            lastEnd = match.range.last + 1
        }
        
        // Add remaining text
        if (lastEnd < text.length) {
            val remaining = text.substring(lastEnd).trim()
            if (remaining.isNotEmpty()) {
                sentences.add(remaining)
            }
        }
        
        return sentences
    }
    
}

/** Decode the native voice schema without constructing or loading an engine. */
internal fun parseVoicesJson(json: String): List<TtsVoiceInfo> {
        if (json.isEmpty() || json == "[]") return emptyList()
        val values = JsonInterop.arrayOrNull(json) ?: return emptyList()
        val voices = mutableListOf<TtsVoiceInfo>()

        for (index in 0 until values.length()) {
            val voice = JsonInterop.objectOrNull(values, index) ?: continue
            val name = JsonInterop.stringOrNull(voice, "name").orEmpty()
            voices.add(TtsVoiceInfo(
                id = JsonInterop.stringOrNull(voice, "id").orEmpty(),
                displayName = name.ifEmpty {
                    JsonInterop.stringOrNull(voice, "displayName").orEmpty()
                },
                language = JsonInterop.stringOrNull(voice, "language").orEmpty(),
                gender = JsonInterop.stringOrNull(voice, "gender").orEmpty(),
                style = JsonInterop.stringOrNull(voice, "style").orEmpty().ifEmpty { "neutral" },
                isDefault = JsonInterop.boolOrDefault(voice, "isDefault", false),
                isCustom = JsonInterop.boolOrDefault(voice, "isCustom", false)
            ))
        }
        return voices
}

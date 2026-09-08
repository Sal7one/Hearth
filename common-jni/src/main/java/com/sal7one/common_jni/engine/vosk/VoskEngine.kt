package com.sal7one.common_jni.engine.vosk

import com.sal7one.common_jni.CommonJni
import com.sal7one.common_jni.engine.SttEngine
import com.sal7one.common_jni.engine.SttError
import com.sal7one.common_jni.json.JsonInterop
import com.sal7one.common_jni.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.jvm.Throws

/**
 * Vosk STT engine implementation.
 *
 * Vosk is optimized for:
 * - Offline speech recognition
 * - Low latency streaming
 * - Multiple languages with small model sizes
 *
 * CHANGES FROM ORIGINAL:
 * - Proper word timestamp parsing from Vosk's "result" array
 * - Uses Default dispatcher for inference
 * - Removed redundant mutex
 * - Added capability reporting
 */
class VoskEngine : SttEngine {

    private var nativeHandle: Long = 0L
    private var _isInitialized = false
    private var _currentModel: ModelInfo? = null
    private var _currentConfig: SttConfig? = null
    private var accumulatedAudioMs = 0L
    private var stagedModelDir: File? = null
    
    // Use Default dispatcher for CPU-bound inference
    private val inferenceDispatcher = Dispatchers.Default.limitedParallelism(1)

    override val engineType: SttEngineType = SttEngineType.VOSK
    override val isInitialized: Boolean get() = _isInitialized
    override val currentModel: ModelInfo? get() = _currentModel
    
    override val capabilities: EngineCapabilities
        get() = EngineCapabilities.VOSK

    companion object {
        private const val TAG = "VoskEngine"

        @Volatile
        private var libraryLoaded = false

        @Volatile
        private var libraryError: String? = null

        init {
            try {
                System.loadLibrary("common_jni")
                libraryLoaded = nativeIsVoskAvailable()
            } catch (e: UnsatisfiedLinkError) {
                libraryLoaded = false
                libraryError = e.message
            }
        }

        fun isAvailable(): Boolean = libraryLoaded
        fun getLibraryError(): String? = libraryError

        @JvmStatic
        private external fun nativeIsVoskAvailable(): Boolean
    }

    // Native methods
    private external fun nativeCreateVosk(): Long
    private external fun nativeDestroyVosk(handle: Long)
    private external fun nativeInitVosk(handle: Long, modelPath: String, configJson: String): Boolean
    private external fun nativePushAudioVosk(handle: Long, samples: ShortArray, count: Int, sampleRate: Int): Int
    // Zero-copy: feeds a direct int16 PCM ByteBuffer straight to the engine.
    // byteOffset/byteCount are in BYTES (sampleCount = byteCount / 2).
    private external fun nativePushAudioDirectVosk(
        handle: Long, buffer: java.nio.ByteBuffer, byteOffset: Int, byteCount: Int, sampleRate: Int
    ): Int
    private external fun nativeGetPartialVosk(handle: Long): String?
    private external fun nativeFinalizeVosk(handle: Long): String?
    private external fun nativeResetVosk(handle: Long)
    private external fun nativeTranscribeBatchVosk(handle: Long, samples: ShortArray, count: Int, sampleRate: Int): String?
    private external fun nativeGetLastErrorVosk(handle: Long): String?
    
    // Cancellation support (P0 from FFmpeg-Kit analysis)
    private external fun nativeCancelVosk(handle: Long)
    private external fun nativeIsCancelledVosk(handle: Long): Boolean
    
    /**
     * Request cancellation of current operation.
     * Thread-safe - can be called from any thread.
     */
    fun cancel() {
        if (nativeHandle != 0L) {
            nativeCancelVosk(nativeHandle)
        }
    }
    
    /**
     * Check if cancellation has been requested.
     */
    fun isCancelled(): Boolean {
        return nativeHandle != 0L && nativeIsCancelledVosk(nativeHandle)
    }

    @Throws(SttError::class)
    override suspend fun initialize(modelPath: String, config: SttConfig): Result<Unit> =
        withContext(inferenceDispatcher) {
            if (!libraryLoaded) {
                return@withContext Result.failure(
                    SttError.LibraryNotLoaded(libraryError ?: "Vosk library not loaded")
                )
            }

            val modelDir = File(modelPath)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                return@withContext Result.failure(SttError.ModelNotFound(modelPath))
            }

            releaseInternal()

            try {
                val privateRoots = CommonJni.getPrivateModelRoots()
                val stagingRoot = CommonJni.getModelStagingRoot()
                    ?: throw IllegalStateException(
                        "CommonJni.init(context) is required before Vosk initialization"
                    )
                val privateModel = privateRoots.any { root ->
                    ModelIntegrity.isContained(root, modelDir)
                }
                val staged = if (privateModel) {
                    null
                } else {
                    ModelIntegrity.stageDirectory(
                        modelDir,
                        stagingRoot,
                        modelDir.name,
                        config.modelSha256
                    )
                }
                if (staged != null) stagedModelDir = staged.file
                val effectiveModelDir = staged?.file ?: modelDir.canonicalFile
                val inspected = when {
                    staged != null -> staged.inspection
                    config.modelSha256 == null -> ModelIntegrity.inspect(effectiveModelDir)
                    else -> null
                }
                val verifiedConfig = config.copy(
                    modelSha256 = inspected?.digest?.hex ?: requireNotNull(config.modelSha256)
                )
                nativeHandle = nativeCreateVosk()
                if (nativeHandle == 0L) {
                    releaseInternal()
                    return@withContext Result.failure(
                        SttError.InitializationFailed("Failed to create Vosk engine")
                    )
                }

                val success = nativeInitVosk(
                    nativeHandle,
                    effectiveModelDir.absolutePath,
                    verifiedConfig.toJson()
                )
                if (!success) {
                    val error = nativeGetLastErrorVosk(nativeHandle) ?: "Unknown error"
                    releaseInternal()
                    return@withContext Result.failure(SttError.InitializationFailed(error))
                }

                _isInitialized = true
                _currentConfig = verifiedConfig
                _currentModel = ModelInfo(
                    path = effectiveModelDir.absolutePath,
                    name = effectiveModelDir.name,
                    type = ModelInfo.ModelSize.UNKNOWN,
                    engineType = SttEngineType.VOSK,
                    sizeBytes = inspected?.sizeBytes ?: -1
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
                val result = nativePushAudioVosk(nativeHandle, chunk.samples, chunk.sampleCount, chunk.sampleRate)
                if (result < 0) {
                    val error = nativeGetLastErrorVosk(nativeHandle) ?: "Push failed"
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
     * See [com.sal7one.common_jni.engine.whisper.WhisperEngine.pushAudioDirect] for rationale.
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
            val rc = nativePushAudioDirectVosk(nativeHandle, buffer, byteOffset, byteCount, sampleRate)
            if (rc < 0) {
                val err = nativeGetLastErrorVosk(nativeHandle) ?: "Push failed"
                return@withContext Result.failure(SttError.ProcessingFailed(err))
            }
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
                nativeGetPartialVosk(nativeHandle)?.takeIf { it.isNotBlank() }?.let { json ->
                    val text = parseVoskPartialJson(json)
                    PartialTranscript(text.trim(), System.currentTimeMillis(), false)
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
                val json = nativeFinalizeVosk(nativeHandle)
                if (json == null) {
                    val error = nativeGetLastErrorVosk(nativeHandle) ?: "Finalization failed"
                    return@withContext Result.failure(SttError.ProcessingFailed(error))
                }
                Result.success(parseVoskResult(json))
            } catch (e: Exception) {
                Result.failure(SttError.ProcessingFailed(e.message ?: "Unknown error"))
            }
        }

    private fun parseVoskResult(json: String): TranscriptResult = parseVoskResultJson(
        json = json,
        configuredLanguage = _currentConfig?.language?.toCode(),
        accumulatedAudioMs = accumulatedAudioMs
    )

    override suspend fun reset(): Result<Unit> =
        withContext(inferenceDispatcher) {
            if (nativeHandle != 0L) {
                try { nativeResetVosk(nativeHandle) } catch (e: Exception) { }
            }
            accumulatedAudioMs = 0L
            Result.success(Unit)
        }

    override suspend fun release() = withContext(inferenceDispatcher) {
        releaseInternal()
    }

    private fun releaseInternal() {
        if (nativeHandle != 0L) {
            try { nativeDestroyVosk(nativeHandle) } catch (e: Exception) { }
            nativeHandle = 0L
        }
        _isInitialized = false
        _currentModel = null
        _currentConfig = null
        accumulatedAudioMs = 0L
        stagedModelDir?.deleteRecursively()
        stagedModelDir = null
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
            
            val json = nativeTranscribeBatchVosk(nativeHandle, samples, samples.size, sampleRate)
            
            onProgress?.invoke(1.0f)
            
            if (json == null) {
                val error = nativeGetLastErrorVosk(nativeHandle) ?: "Batch failed"
                return@withContext Result.failure(SttError.ProcessingFailed(error))
            }
            
            // Update accumulated time for segment timing
            accumulatedAudioMs = (samples.size.toLong() * 1000) / sampleRate
            
            Result.success(parseVoskResult(json))
        } catch (e: Exception) {
            Result.failure(SttError.ProcessingFailed(e.message ?: "Unknown error"))
        }
    }
}

private data class VoskWord(
    val word: String,
    val start: Double,
    val end: Double,
    val confidence: Float
)

internal fun parseVoskPartialJson(json: String): String {
    val root = JsonInterop.objectOrNull(json)
    return root?.let { JsonInterop.stringOrNull(it, "partial") } ?: json
}

/** Supports both native normalized transcripts and raw Vosk word-result JSON. */
internal fun parseVoskResultJson(
    json: String,
    configuredLanguage: String?,
    accumulatedAudioMs: Long
): TranscriptResult {
    val root = JsonInterop.objectOrNull(json)
        ?: return TranscriptResult.EMPTY.copy(engineUsed = SttEngineType.VOSK)
    if (JsonInterop.arrayOrNull(root, "segments") != null) {
        val normalized = TranscriptResult.fromJson(json, SttEngineType.VOSK)
        return if (normalized.language == null) {
            normalized.copy(language = configuredLanguage)
        } else {
            normalized
        }
    }

    val text = JsonInterop.stringOrNull(root, "text") ?: ""
    val segments = mutableListOf<TranscriptSegment>()
    val wordResults = parseVoskWordResults(root)
    if (wordResults.isNotEmpty()) {
        var currentWords = mutableListOf<VoskWord>()
        var lastEndTime = 0.0
        for (word in wordResults) {
            if (currentWords.isNotEmpty() && word.start - lastEndTime > 1.0) {
                segments.add(createSegmentFromWords(currentWords))
                currentWords = mutableListOf()
            }
            currentWords.add(word)
            lastEndTime = word.end
        }
        if (currentWords.isNotEmpty()) segments.add(createSegmentFromWords(currentWords))
    } else if (text.isNotBlank()) {
        segments.add(TranscriptSegment(
            text = text.trim(),
            startMs = 0,
            endMs = accumulatedAudioMs,
            confidence = 0.9f,
            isFinal = true
        ))
    }

    return TranscriptResult(
        segments = segments,
        fullText = text.trim(),
        language = configuredLanguage,
        processingTimeMs = 0,
        engineUsed = SttEngineType.VOSK
    )
}

private fun parseVoskWordResults(root: org.json.JSONObject): List<VoskWord> {
    val words = mutableListOf<VoskWord>()
    val results = JsonInterop.arrayOrNull(root, "result") ?: return words
    for (index in 0 until results.length()) {
        val value = JsonInterop.objectOrNull(results, index) ?: continue
        val word = JsonInterop.stringOrNull(value, "word")
        val start = JsonInterop.doubleOrNull(value, "start")
        val end = JsonInterop.doubleOrNull(value, "end")
        val confidence = JsonInterop.floatOrDefault(value, "conf", 0.9f)
        if (word != null && start != null && end != null) {
            words.add(VoskWord(word, start, end, confidence))
        }
    }
    return words
}

private fun createSegmentFromWords(words: List<VoskWord>): TranscriptSegment {
    return TranscriptSegment(
        text = words.joinToString(" ") { it.word },
        startMs = (words.first().start * 1000).toLong(),
        endMs = (words.last().end * 1000).toLong(),
        confidence = words.map { it.confidence }.average().toFloat(),
        isFinal = true
    )
}

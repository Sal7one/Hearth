package com.sal7one.common_jni.core

import android.content.Context
import android.util.Log
import com.sal7one.common_jni.CommonJni
import com.sal7one.common_jni.engine.whisper.WhisperEngine
import com.sal7one.common_jni.model.AssetModelProvider
import com.sal7one.common_jni.model.LanguageConfig
import com.sal7one.common_jni.model.SttConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Language detection outcome. */
sealed class LanguageDetectionResult {
    data class Detected(val languageCode: String, val confidence: Float = 1.0f) : LanguageDetectionResult()
    data object Unknown : LanguageDetectionResult()
    data class Error(val message: String) : LanguageDetectionResult()
    data class Unsupported(val languageCode: String) : LanguageDetectionResult()
}

/**
 * Whisper-backed language identifier. Does NOT run transcription — just LID.
 * Useful for pre-selecting a Vosk model based on spoken language.
 */
class LanguageDetector(private val context: Context) {

    private data class PreparedModel(val path: String, val sha256: String)

    sealed class InitResult {
        data object Success : InitResult()
        data class Failure(val reason: String) : InitResult()
    }

    private val mutex = Mutex()
    private var whisperEngine: WhisperEngine? = null
    private var isInitialized = false

    fun isAvailable(): Boolean = CommonJni.hasWhisper()

    /**
     * Initialize with a Whisper model path. Asset paths starting with
     * `models/` are copied out to internal storage first.
     */
    suspend fun initialize(modelPath: String): InitResult = mutex.withLock {
        if (isInitialized) return InitResult.Success
        if (!isAvailable()) return InitResult.Failure("Whisper library not compiled in")

        withContext(Dispatchers.IO) {
            val preparedAsset = when {
                modelPath.startsWith("models/") ->
                    copyModelFromAssets(modelPath)
                        ?: return@withContext InitResult.Failure("Model not found in assets: $modelPath")
                else -> null
            }
            val localPath = preparedAsset?.path ?: modelPath

            if (!File(localPath).exists()) {
                return@withContext InitResult.Failure("Model file not found: $localPath")
            }

            val engine = WhisperEngine()
            val config = SttConfig(
                language = LanguageConfig.Auto,
                numThreads = 2,
                enableTimestamps = false,
                suppressBlank = true,
                modelSha256 = preparedAsset?.sha256
            )

            val result = engine.initialize(localPath, config)
            if (result.isSuccess) {
                whisperEngine = engine
                isInitialized = true
                InitResult.Success
            } else {
                val err = result.exceptionOrNull()?.message ?: "Unknown error"
                InitResult.Failure("Engine initialization failed: $err")
            }
        }
    }

    /** Detect language from 16-bit PCM samples. */
    suspend fun detectLanguage(
        samples: ShortArray,
        sampleRate: Int = 16_000
    ): LanguageDetectionResult = mutex.withLock {
        if (!isInitialized) return LanguageDetectionResult.Error("Detector not initialized")
        val engine = whisperEngine
            ?: return LanguageDetectionResult.Error("Engine was released")
        if (samples.isEmpty()) return LanguageDetectionResult.Error("Empty audio samples")

        try {
            val langCode = engine.detectLanguage(samples, sampleRate)
                ?: return LanguageDetectionResult.Unknown

            if (isLanguageSupported(langCode)) {
                LanguageDetectionResult.Detected(langCode)
            } else {
                LanguageDetectionResult.Unsupported(langCode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during language detection", e)
            LanguageDetectionResult.Error("Detection failed: ${e.message}")
        }
    }

    suspend fun release() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { whisperEngine?.release() }
            whisperEngine = null
            isInitialized = false
        }
    }

    private fun isLanguageSupported(langCode: String): Boolean {
        val code = langCode.lowercase()
        return code in SUPPORTED_LANGUAGES || code == "cn"
    }

    private suspend fun copyModelFromAssets(assetPath: String): PreparedModel? = withContext(Dispatchers.IO) {
        runCatching {
            val provider = AssetModelProvider(
                context = context,
                assetPath = assetPath,
                cacheSubdir = "language-detection-models"
            )
            val path = provider.getModelPath()
            val digest = requireNotNull(provider.getModelDigest()) {
                "Verified asset provider did not publish a digest"
            }
            PreparedModel(path, digest.hex)
        }.onFailure { Log.e(TAG, "Failed to stage verified model asset: $assetPath", it) }
            .getOrNull()
    }

    private companion object {
        private const val TAG = "LanguageDetector"
        private val SUPPORTED_LANGUAGES = setOf(
            "en", "ar", "zh", "de", "es", "fr", "ru", "ja", "ko", "pt",
            "it", "nl", "tr", "pl", "uk", "hi"
        )
    }
}

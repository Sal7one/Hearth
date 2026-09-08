package com.sal7one.transiber.byok

import com.sal7one.common_jni.engine.SttEngine
import com.sal7one.common_jni.model.AudioChunk
import com.sal7one.common_jni.model.ModelInfo
import com.sal7one.common_jni.model.PartialTranscript
import com.sal7one.common_jni.model.SttConfig
import com.sal7one.common_jni.model.SttEngineType
import com.sal7one.common_jni.model.TranscriptResult
import com.sal7one.common_jni.model.LanguageConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

/**
 * Cloud Whisper behind the standard [SttEngine] interface (BYOK).
 *
 * NETWORK CODE — play distribution only; see [ByokPolicy]. The capture
 * pipeline is unchanged: audio chunks stream in, this engine slices them
 * into utterances ([UtteranceBuffer] + an RMS gate) and uploads each as
 * WAV to the user's OpenAI-compatible endpoint with the user's key. Each
 * completed upload REPLACES the published partial — the caption
 * controller's existing promotion logic sees a fresh non-prefix utterance
 * and finalizes the previous line automatically, so history/translation
 * behave exactly like the on-path engines.
 *
 * Compared to on-device whisper this trades latency-per-utterance
 * (~1-2 s round trip) for model quality — e.g. hearing Chinese and
 * translating to English in one /audio/translations call, using
 * [SttConfig.translateToEnglish] exactly like the local whisper task.
 *
 * In the foss build [ByokPolicy.FEATURE_BYOK] is false; the controller
 * never constructs this class there and falls back to local whisper.
 */
class RemoteWhisperEngine(
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    model: String = DEFAULT_MODEL,
) : SttEngine {

    private val client = OpenAiAudioClient(apiKey, baseUrl, model)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var config: SttConfig? = null
    private val buffer = UtteranceBuffer(sampleRate = TARGET_SAMPLE_RATE, maxMs = 4_000)
    private val requests = Channel<ShortArray>(capacity = 2)
    private val finals = ConcurrentLinkedQueue<String>()

    @Volatile private var published: String = ""
    @Volatile private var lastError: String? = null
    @Volatile private var lastNotice: String? = null
    @Volatile private var initialized = false

    override val engineType: SttEngineType = SttEngineType.WHISPER
    override val isInitialized: Boolean get() = initialized
    override val currentModel: ModelInfo? = null

    override suspend fun initialize(modelPath: String, config: SttConfig): Result<Unit> {
        // The cloud engine needs no local model file; modelPath is ignored.
        if (!ByokPolicy.FEATURE_BYOK) {
            return Result.failure(IllegalStateException(NETWORK_DISABLED_MESSAGE))
        }
        this.config = config
        initialized = true
        scope.launch {
            for (pcm in requests) {
                if (!initialized) break
                val cfg = this@RemoteWhisperEngine.config ?: break
                try {
                    val wav = WavEncoder.encodePcm16(pcm, TARGET_SAMPLE_RATE)
                    val text = if (cfg.translateToEnglish) client.translateToEnglish(wav)
                        else client.transcribe(wav, languageCode(cfg.language))
                    if (initialized && text.isNotBlank()) finals.add(text)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (initialized) lastError = "Cloud STT: ${e.message}" }
            }
        }
        return Result.success(Unit)
    }

    override suspend fun pushAudioChunk(chunk: AudioChunk): Result<Unit> {
        if (!initialized) return Result.failure(IllegalStateException("Not initialized"))
        val utterance = buffer.push(chunk.samples, chunk.sampleRate, isVoiceActive(chunk))
        if (utterance != null) enqueue(utterance)
        return Result.success(Unit)
    }

    override suspend fun getPartialTranscript(): PartialTranscript? = null

    fun takeFinals(): List<String> = buildList { while (true) add(finals.poll() ?: break) }

    /**
     * Drains the last cloud failure for UI surfacing (the caption poller
     * turns non-null into an overlay notice). Errors are consumed once —
     * they are already non-sticky for inference itself.
     */
    fun takeLastErrorForUi(): String? = lastError?.also { lastError = null }

    /** Drains a non-fatal notice (e.g. translation-endpoint fallback). */
    fun takeLastNoticeForUi(): String? = lastNotice?.also { lastNotice = null }

    override suspend fun finalize(): Result<TranscriptResult> {
        buffer.flush()?.let { enqueue(it) }
        return Result.success(
            TranscriptResult(
                segments = emptyList(),
                fullText = published,
                language = null,
                processingTimeMs = 0,
                engineUsed = engineType,
            ),
        )
    }

    override suspend fun reset(): Result<Unit> {
        buffer.clear()
        published = ""
        finals.clear()
        lastError = null
        return Result.success(Unit)
    }

    override suspend fun release() {
        initialized = false
        requests.close()
        client.cancel()
        scope.cancel()
        finals.clear()
        buffer.clear()
        published = ""
        lastError = null
    }

    override suspend fun transcribeBatch(
        samples: ShortArray,
        sampleRate: Int,
        onProgress: ((Float) -> Unit)?,
    ): Result<TranscriptResult> {
        val cfg = config ?: return Result.failure(IllegalStateException("Not initialized"))
        return try {
            val wav = WavEncoder.encodePcm16(samples, sampleRate)
            val text = if (cfg.translateToEnglish) {
                client.translateToEnglish(wav)
            } else {
                client.transcribe(wav, languageCode(cfg.language))
            }
            published = text
            Result.success(
                TranscriptResult(
                    segments = emptyList(),
                    fullText = text,
                    language = null,
                    processingTimeMs = 0,
                    engineUsed = engineType,
                ),
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun enqueue(pcm: ShortArray) {
        if (requests.trySend(pcm).isFailure) {
            // Drop queued stale work, never accumulate seconds indefinitely.
            requests.tryReceive()
            requests.trySend(pcm)
            lastError = "Cloud STT cannot keep up: skipped queued audio. Choose Streaming · OpenAI for live captions."
        }
    }

    /** Simple RMS gate mirroring the native gate's -45 dB threshold. */
    private fun isVoiceActive(chunk: AudioChunk): Boolean {
        if (chunk.isEmpty) return false
        var sum = 0.0
        for (s in chunk.samples) {
            val v = s / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / chunk.samples.size)
        return rms > Math.pow(10.0, -45.0 / 20.0)
    }

    private fun languageCode(language: LanguageConfig): String? = when (language) {
        is LanguageConfig.Specific -> language.code.takeIf { it.isNotBlank() && it != "auto" }
        else -> null
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "whisper-1"
        const val NETWORK_DISABLED_MESSAGE =
            "Cloud engines need the play (BYOK) distribution; the FOSS build has no network"
        private const val TARGET_SAMPLE_RATE = 16_000
    }
}

package com.sal7one.transiber.byok

import com.sal7one.common_jni.engine.SttEngine
import com.sal7one.common_jni.model.AudioChunk
import com.sal7one.common_jni.model.LanguageConfig
import com.sal7one.common_jni.model.ModelInfo
import com.sal7one.common_jni.model.PartialTranscript
import com.sal7one.common_jni.model.SttConfig
import com.sal7one.common_jni.model.SttEngineType
import com.sal7one.common_jni.model.TranscriptResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * TRUE STREAMING cloud captions: a WebSocket STT engine implementing the
 * standard [SttEngine] interface so the caption controller needs no special
 * casing beyond draining finals.
 *
 * NETWORK CODE (play distribution only — see [ByokPolicy]). Audio flows in
 * as 16 kHz PCM chunks and is forwarded to the provider's WebSocket as it
 * arrives; the provider's interim hypotheses become the live partial and
 * each FINAL result lands in [takeFinals], which the caption controller
 * drains into history — utterances promote in real time, not after a batch
 * round-trip.
 */
class StreamingCloudEngine(
    private val client: StreamingSttClient,
    private val providerLabel: String,
    private val connectTimeoutMs: Long = 15_000,
) : SttEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioChannel = Channel<ShortArray>(capacity = 4)
    private val finals = ConcurrentLinkedQueue<String>()
    private val transcriptLock = Any()
    data class Snapshot(val partial: String, val finals: List<String>, val error: String?)
    fun takeSnapshot(): Snapshot = synchronized(transcriptLock) {
        val finished = buildList { while (true) add(finals.poll() ?: break) }
        Snapshot(publishedInterim, finished, lastError).also { lastError = null }
    }

    @Volatile private var publishedInterim: String = ""
    @Volatile private var lastError: String? = null
    @Volatile private var initialized = false

    override val engineType: SttEngineType = SttEngineType.WHISPER
    override val isInitialized: Boolean get() = initialized
    override val currentModel: ModelInfo? = null

    override suspend fun initialize(modelPath: String, config: SttConfig): Result<Unit> {
        if (!ByokPolicy.FEATURE_BYOK) {
            return Result.failure(IllegalStateException(RemoteWhisperEngine.NETWORK_DISABLED_MESSAGE))
        }
        val ready = CompletableDeferred<Unit>()
        wireCallbacks()
        client.onConnected = { ready.complete(Unit) }
        val errorHandler = client.onError
        client.onError = { message ->
            ready.completeExceptionally(IllegalStateException(message))
            errorHandler?.invoke(message)
        }
        return try {
            client.connect()
            withTimeout(connectTimeoutMs) { ready.await() }
            initialized = true
            scope.launch {
                for (pcm in audioChannel) {
                    if (!initialized) break
                    try { client.sendPcm(pcm) }
                    catch (e: Exception) { lastError = "stream send: ${e.message}"; break }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            client.close()
            if (e is TimeoutCancellationException) {
                Result.failure(IllegalStateException("$providerLabel connection timed out after $connectTimeoutMs ms", e))
            } else {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }
    }

    override suspend fun pushAudioChunk(chunk: AudioChunk): Result<Unit> {
        if (!initialized) return Result.failure(IllegalStateException("Not initialized"))
        if (chunk.samples.isNotEmpty()) {
            if (audioChannel.trySend(chunk.samples).isFailure) {
                return Result.failure(IllegalStateException("Streaming audio queue full"))
            }
        }
        return Result.success(Unit)
    }

    override suspend fun getPartialTranscript(): PartialTranscript? {
        if (lastError != null) return null
        val interim = publishedInterim
        return if (interim.isBlank()) null
        else PartialTranscript(text = interim, timestampMs = now(), isStable = false)
    }

    /** Settled utterances, drained by the caption controller into history. */
    fun takeFinals(): List<String> {
        val out = mutableListOf<String>()
        while (true) out.add(finals.poll() ?: break)
        return out
    }

    /** The audio source ended — flush the provider's buffered audio so the
     * trailing utterance still finalizes (see [StreamingSttClient.onCaptureEnded]). */
    fun onCaptureEnded() = client.onCaptureEnded()

    /** Non-sticky error for UI surfacing (same pattern as RemoteWhisperEngine). */
    fun takeLastErrorForUi(): String? = lastError?.also { lastError = null }

    override suspend fun finalize(): Result<TranscriptResult> {
        client.close()
        return Result.success(
            TranscriptResult(
                segments = emptyList(),
                fullText = finals.toList().joinToString(" "),
                language = null,
                processingTimeMs = 0,
                engineUsed = engineType,
            ),
        )
    }

    override suspend fun reset(): Result<Unit> {
        publishedInterim = ""
        finals.clear()
        lastError = null
        return Result.success(Unit)
    }

    override suspend fun release() {
        initialized = false
        client.close()
        audioChannel.close()
        finals.clear()
        scope.cancel()
    }

    override suspend fun transcribeBatch(
        samples: ShortArray,
        sampleRate: Int,
        onProgress: ((Float) -> Unit)?,
    ): Result<TranscriptResult> = Result.failure(UnsupportedOperationException("streaming engine"))

    private fun wireCallbacks() {
        client.onInterim = { text -> synchronized(transcriptLock) {
            if (initialized) publishedInterim = text
        } }
        client.onFinal = { text -> synchronized(transcriptLock) {
            if (initialized) {
                if (text.isNotBlank()) finals.add(text)
                publishedInterim = ""
            }
        } }
        client.onError = { message -> synchronized(transcriptLock) {
            lastError = "$providerLabel streaming: $message"
        } }
    }

    private fun now() = System.currentTimeMillis()
}

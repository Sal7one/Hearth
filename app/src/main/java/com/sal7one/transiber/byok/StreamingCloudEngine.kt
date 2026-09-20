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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
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
    private val captionUpdates = linkedMapOf<Long, CloudCaptionUpdate>()
    private var translatedInterim = ""
    data class Snapshot(val partial: String, val finals: List<String>, val error: String?,
        val captions: List<CloudCaptionUpdate> = emptyList(), val translation: String = "")
    fun takeSnapshot(): Snapshot = synchronized(transcriptLock) {
        val finished = buildList { while (true) add(finals.poll() ?: break) }
        Snapshot(publishedInterim, finished, lastError, captionUpdates.values.toList(), translatedInterim).also { lastError = null; captionUpdates.clear() }
    }

    @Volatile private var publishedInterim: String = ""
    @Volatile private var lastError: String? = null
    @Volatile private var initialized = false
    @Volatile private var released = false
    @Volatile private var failure: String? = null
    private var providerFailure = false
    @Volatile private var inputEnded = false
    private val ready = CompletableDeferred<Unit>()
    private val clientClosed = AtomicBoolean(false)
    private var sender: Job? = null

    override val engineType: SttEngineType = SttEngineType.WHISPER
    override val isInitialized: Boolean get() = initialized
    override val currentModel: ModelInfo? = null

    override suspend fun initialize(modelPath: String, config: SttConfig): Result<Unit> {
        if (!ByokPolicy.FEATURE_BYOK) {
            return Result.failure(IllegalStateException(RemoteWhisperEngine.NETWORK_DISABLED_MESSAGE))
        }
        wireCallbacks()
        client.onConnected = { synchronized(transcriptLock) {
            if (!released && failure == null) ready.complete(Unit)
        } }
        val errorHandler = client.onError
        client.onError = { message ->
            ready.completeExceptionally(IllegalStateException(message))
            errorHandler?.invoke(message)
        }
        return try {
            client.connect()
            withTimeout(connectTimeoutMs) { ready.await() }
            synchronized(transcriptLock) {
                check(!released) { "Streaming session released" }
                failure?.let { error(it) }
                initialized = true
            }
            sender = scope.launch {
                for (pcm in audioChannel) {
                    if (!initialized) break
                    try { client.sendPcm(pcm) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        recordFailure("stream send: ${e.message ?: e.javaClass.simpleName}")
                        break
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            retire()
            if (e is TimeoutCancellationException) {
                Result.failure(IllegalStateException("$providerLabel connection timed out after $connectTimeoutMs ms", e))
            } else {
                if (e is CancellationException) throw e
                Result.failure(e)
            }
        }
    }

    override suspend fun pushAudioChunk(chunk: AudioChunk): Result<Unit> {
        failure?.let { return Result.failure(IllegalStateException(it)) }
        if (!initialized) return Result.failure(IllegalStateException(failure ?: "Not initialized"))
        if (chunk.samples.isNotEmpty()) {
            if (audioChannel.trySend(chunk.samples).isFailure) {
                return Result.failure(IllegalStateException(failure ?: if (inputEnded) "Streaming audio has ended" else "Streaming audio queue full"))
            }
        }
        return Result.success(Unit)
    }

    override suspend fun getPartialTranscript(): PartialTranscript? {
        if (failure != null || released) return null
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
    suspend fun onCaptureEnded() {
        if (inputEnded || released) return
        inputEnded = true
        audioChannel.close()
        // A controller queue drain is not enough: this engine has its own queue.
        sender?.join()
        if (initialized && !released) {
            try { client.onCaptureEnded() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { recordFailure("stream end: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    /** Non-sticky error for UI surfacing (same pattern as RemoteWhisperEngine). */
    fun takeLastErrorForUi(): String? = lastError?.also { lastError = null }

    override suspend fun finalize(): Result<TranscriptResult> {
        onCaptureEnded()
        retire()
        failure?.let { return Result.failure(IllegalStateException(it)) }
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
        synchronized(transcriptLock) { captionUpdates.clear(); translatedInterim = "" }
        finals.clear()
        // Clearing text must not make a dead socket look healthy again.
        return Result.success(Unit)
    }

    override suspend fun release() {
        retire()
        withContext(NonCancellable) { sender?.join() }
        finals.clear()
    }

    private fun retire() {
        synchronized(transcriptLock) {
            released = true
            initialized = false
            ready.cancel(CancellationException("Streaming session released"))
        }
        audioChannel.cancel()
        scope.cancel()
        closeClient()
    }

    private fun closeClient() { if (clientClosed.compareAndSet(false, true)) client.close() }

    private fun recordFailure(message: String, fromProvider: Boolean = false) {
        synchronized(transcriptLock) {
            // Retired sessions cannot publish errors; retain the original server cause
            // when rejecting subsequent audio also throws a generic send failure.
            if (released || (failure != null && (!fromProvider || providerFailure))) return
            providerFailure = fromProvider
            failure = message
            lastError = message
            initialized = false
            audioChannel.cancel()
        }
    }

    override suspend fun transcribeBatch(
        samples: ShortArray,
        sampleRate: Int,
        onProgress: ((Float) -> Unit)?,
    ): Result<TranscriptResult> = Result.failure(UnsupportedOperationException("streaming engine"))

    private fun wireCallbacks() {
        (client as? StructuredCaptionClient)?.let { structured ->
            structured.onCaption = { update -> synchronized(transcriptLock) {
                if (initialized) {
                    if (captionUpdates.size >= 128 && update.id !in captionUpdates) lastError = "Cloud caption queue is full"
                    else if (update.revision > (captionUpdates[update.id]?.revision ?: -1L)) captionUpdates[update.id] = update
                }
            } }
            structured.onTranslatedInterim = { value -> synchronized(transcriptLock) { if (initialized) translatedInterim = value } }
        }
        client.onInterim = { text -> synchronized(transcriptLock) {
            if (initialized) publishedInterim = text
        } }
        client.onFinal = { text -> synchronized(transcriptLock) {
            if (initialized) {
                if (text.isNotBlank()) finals.add(text)
                publishedInterim = ""
            }
        } }
        client.onError = { message -> recordFailure("$providerLabel streaming: $message", fromProvider = true) }
    }

    private fun now() = System.currentTimeMillis()
}

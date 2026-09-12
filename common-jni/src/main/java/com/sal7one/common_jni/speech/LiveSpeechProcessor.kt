package com.sal7one.common_jni.speech

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface SpeechProcessorState {
    data object Running : SpeechProcessorState
    data object Finished : SpeechProcessorState
    data object Closed : SpeechProcessorState
    data class Failed(val error: Throwable) : SpeechProcessorState
}

/**
 * Capture-independent bounded worker. tryAccept copies accepted audio exactly once;
 * false means the stream failed and the capture owner must stop submitting audio.
 * Never silently drops audio or accumulates minutes of lag. Drain final events regularly.
 */
class LiveSpeechProcessor(
    private val session: SpeechSession,
    /** Queue holds at most this many samples, independent of recorder read size. */
    private val maxQueuedSamples: Int = 16000,
) {
    init { require(maxQueuedSamples in 320..48000) }
    private data class Frame(val samples: ShortArray, val offset: Long, val generation: Long, val reset: CompletableDeferred<Unit>? = null)
    private val lock = Any()
    private val queue = Channel<Frame>(Channel.UNLIMITED) // samples bounded under lock, including in-flight work
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<SpeechProcessorState>(SpeechProcessorState.Running)
    val state: StateFlow<SpeechProcessorState> = mutableState
    private var queued = 0
    private var submitted = 0L
    private var finishing = false
    private var generation = 0L
    private var resetting = false
    private var partial: SpeechTranscript? = null
    private val finals = ArrayDeque<SpeechTranscript>()
    private var inferenceNanos = 0L
    private var processedSamples = 0L
    // Enter try/finally before construction returns. Otherwise Stop/failure can
    // cancel a not-yet-started coroutine and leak its already-loaded native session.
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            for (frame in queue) {
                if (frame.reset != null) {
                    try {
                        session.reset()
                        synchronized(lock) { resetting = false }
                        frame.reset.complete(Unit)
                    } catch (e: Throwable) { frame.reset.completeExceptionally(e); throw e }
                    continue
                }
                if (synchronized(lock) { frame.generation != generation }) continue
                val update = session.accept(frame.samples, frame.offset)
                synchronized(lock) {
                    if (frame.generation == generation) { queued -= frame.samples.size; publish(update) }
                }
            }
            synchronized(lock) { check(finishing) { "Speech queue closed without finish" } }
            val final = session.finish()
            synchronized(lock) {
                if (mutableState.value == SpeechProcessorState.Running) { publish(final); mutableState.value = SpeechProcessorState.Finished }
            }
        } catch (e: CancellationException) {
            synchronized(lock) {
                if (mutableState.value == SpeechProcessorState.Running) mutableState.value = SpeechProcessorState.Closed
            }
        } catch (e: Throwable) { fail(e) }
        finally { session.close(); queue.cancel(); synchronized(lock) { queued = 0 }; scope.cancel() }
    }

    fun tryAccept(samples: ShortArray): Boolean = synchronized(lock) {
        if (mutableState.value != SpeechProcessorState.Running || finishing || resetting) return false
        if (samples.isEmpty() || samples.size > 16000) {
            fail(IllegalArgumentException("Speech capture frame must contain 1..16000 samples")); return false
        }
        if (samples.size > maxQueuedSamples - queued) {
            fail(IllegalStateException("Speech inference cannot keep up: $queued samples pending; queue limit $maxQueuedSamples"))
            return false
        }
        val copy = samples.copyOf()
        queued += copy.size
        val offset = submitted; submitted += copy.size
        check(queue.trySend(Frame(copy, offset, generation)).isSuccess)
        true
    }

    /** Discard the old timeline without unloading weights; reset is ordered after in-flight inference. */
    suspend fun reset() {
        val done = CompletableDeferred<Unit>()
        synchronized(lock) {
            check(mutableState.value == SpeechProcessorState.Running && !finishing && !resetting) { "Speech session cannot reset in its current state" }
            resetting = true
            ++generation
            while (queue.tryReceive().isSuccess) { }
            queued = 0; submitted = 0; processedSamples = 0; inferenceNanos = 0
            partial = null; finals.clear()
            check(queue.trySend(Frame(shortArrayOf(), 0, generation, done)).isSuccess)
        }
        val completion = worker.invokeOnCompletion { cause ->
            done.completeExceptionally(cause ?: IllegalStateException("Speech worker ended during reset"))
        }
        try { done.await() } finally { completion.dispose() }
    }

    /** Finish queued audio and flush the tail. Does not accept more input. */
    fun finish() = synchronized(lock) {
        if (mutableState.value == SpeechProcessorState.Running && !finishing) { finishing = true; queue.close() }
    }

    /** Removes only delivered finals. Current partial is a replaceable snapshot. */
    fun snapshot(): SpeechProcessorSnapshot = synchronized(lock) {
        SpeechProcessorSnapshot(finals.toList(), partial, submitted, processedSamples, queued, inferenceNanos, mutableState.value)
            .also { finals.clear() }
    }

    /** Abort, discard queued audio, suppress late native results, and wait for safe cleanup. */
    suspend fun close() {
        synchronized(lock) {
            mutableState.value = SpeechProcessorState.Closed; partial = null; finals.clear()
            queue.cancel(); worker.cancel()
        }
        withContext(NonCancellable) { worker.join() }
    }

    private fun publish(update: SpeechUpdate) {
        if (mutableState.value != SpeechProcessorState.Running) return
        processedSamples = update.acceptedSamples
        inferenceNanos += update.inferenceNanos
        update.transcripts.forEach { text ->
            if (text.isFinal) {
                partial = null
                if (finals.size >= 128) throw IllegalStateException("Speech final-event queue full: consumer must drain snapshots")
                finals.addLast(text)
            } else partial = text
        }
    }
    private fun fail(error: Throwable) = synchronized(lock) {
        if (mutableState.value == SpeechProcessorState.Running) {
            mutableState.value = SpeechProcessorState.Failed(error); partial = null
            queue.cancel(); scope.cancel()
        }
    }
}

data class SpeechProcessorSnapshot(
    val finals: List<SpeechTranscript>, val partial: SpeechTranscript?,
    val submittedSamples: Long, val processedSamples: Long, val pendingSamples: Int,
    val inferenceNanos: Long, val state: SpeechProcessorState,
) {
    /** >1 means inference costs more time than the audio duration; queue growth then becomes likely. */
    val realTimeFactor: Double? get() = if (processedSamples == 0L) null else inferenceNanos / 1e9 / (processedSamples / 16000.0)
}

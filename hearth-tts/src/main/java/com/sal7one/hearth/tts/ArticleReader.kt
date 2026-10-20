package com.sal7one.hearth.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

sealed interface ArticleSpeech {
    data class VoiceSelected(val resolution: VoiceResolution) : ArticleSpeech
    data class Audio(val part: Int, val text: String, val voice: SpeechVoice, val pcm: PcmAudio) : ArticleSpeech
}

/**
 * A reusable, network-free article provider. One article at a time, at most one queued PCM frame.
 * Slow playback applies backpressure to synthesis. Cancellation requests native stop immediately;
 * the engine is closed only on its owning worker after synthesis has returned.
 */
class ArticleReader(private val engine: SpeechEngine) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "hearth-tts-${engine.id}").apply { isDaemon = true } }
    private val lock = Any()
    private var closed = false
    private var busy = false
    private var stopActive: (() -> Unit)? = null
    private var activeToken: SpeechCancellation? = null
    private val terminated = CompletableDeferred<Unit>()

    val voices: List<SpeechVoice> get() = engine.voices.toList()

    fun read(text: String, language: String, voice: VoicePreference = VoicePreference(), options: SpeechOptions = SpeechOptions()): Flow<ArticleSpeech> = flow {
        coroutineScope {
            // An explicitly owned channel lets cancellation abort a blocked native send before teardown waits.
            val events = Channel<ArticleSpeech>(1)
            val articleJob = currentCoroutineContext().job
            val cancellation = SpeechCancellation()
            val taskDone = CompletableDeferred<Unit>()
            var reserved = false
            try {
                val parts = ArticleText.chunks(text, engine.maximumTextCharacters)
                val resolution = SpeechVoices.resolve(engine.voices, language, voice)
                synchronized(lock) {
                    check(!closed) { "Article reader is closed" }
                    check(!busy) { "Article reader is busy; stop or finish the current article first" }
                    busy = true; reserved = true; activeToken = cancellation
                    stopActive = {
                        cancellation.cancel()
                        events.cancel(CancellationException("Article speech stopped"))
                        if (!taskDone.isCompleted) engine.cancel()
                        articleJob.cancel(CancellationException("Article speech stopped"))
                    }
                    worker.execute {
                        fun send(event: ArticleSpeech) {
                            cancellation.throwIfCancelled()
                            events.trySendBlocking(event).getOrThrow()
                        }
                        var failure: Throwable? = null
                        try {
                            send(ArticleSpeech.VoiceSelected(resolution))
                            for ((index, part) in parts.withIndex()) {
                                cancellation.throwIfCancelled()
                                var emitted = false
                                engine.synthesize(part, language, resolution.voice, options, cancellation) { pcm ->
                                    // Adapter callbacks may reuse their borrowed sample array after returning.
                                    val owned = PcmAudio(pcm.samples.copyOf(), pcm.sampleRate)
                                    send(ArticleSpeech.Audio(index, part, resolution.voice, owned)); emitted = true
                                }
                                check(emitted) { "${engine.id} produced no audio for article part ${index + 1}" }
                            }
                        } catch (error: Throwable) {
                            failure = error
                        } finally {
                            taskDone.complete(Unit)
                            events.close(failure)
                        }
                    }
                }
                for (event in events) emit(event)
            } finally {
                cancellation.cancel()
                // close() leaves pending senders suspended; cancel() discards buffered events and wakes them.
                events.cancel(CancellationException("Article collection finished"))
                if (reserved) {
                    if (!taskDone.isCompleted) engine.cancel()
                    // A replacement cannot reset the session while its predecessor still owns native inference.
                    withContext(NonCancellable) { taskDone.await() }
                    synchronized(lock) {
                        if (activeToken === cancellation) { busy = false; stopActive = null; activeToken = null }
                    }
                }
            }
        }
    }

    fun stop() { synchronized(lock) { stopActive?.invoke() } }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            stopActive?.invoke()
            worker.execute {
                try { engine.close(); terminated.complete(Unit) }
                catch (error: Throwable) { terminated.completeExceptionally(error) }
            }
            worker.shutdown()
        }
    }

    /** Use at lifecycle boundaries when another engine must not start before native teardown. */
    suspend fun closeAndJoin() { close(); terminated.await() }
}

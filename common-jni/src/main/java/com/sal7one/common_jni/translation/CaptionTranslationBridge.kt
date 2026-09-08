package com.sal7one.common_jni.translation

import com.sal7one.common_jni.speech.TranslationDirection
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, final-only second stage. It never blocks ASR, guesses a missing language, or publishes stale work. */
class CaptionTranslationBridge(
    scope: CoroutineScope,
    private val target: String,
    private val open: suspend () -> CancellableTextTranslator,
    private val result: (Long, String, Long) -> Unit,
    private val notice: (String?) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    capacity: Int = 3,
    private val maxAgeMs: Long = 20_000,
) : AutoCloseable {
    private data class Request(val id: Long, val text: String, val source: String?, val at: Long)
    private val closed = AtomicBoolean(false)
    private val queue = Channel<Request>(capacity)
    @Volatile private var translator: CancellableTextTranslator? = null
    private val job = scope.launch(Dispatchers.IO) {
        try {
            for (request in queue) {
                if (closed.get()) break
                try {
                    check(clock() - request.at < maxAgeMs) { "Translation queue is behind; this line stays CC only" }
                    val source = request.source?.let(TranslationLanguages::normalize)
                    check(source != null && source !in setOf("", "auto", "mul", "und")) {
                        "Source language is unknown or mixed. Choose the spoken language to enable local translation; CC continues"
                    }
                    val to = TranslationLanguages.normalize(target)
                    if (source == to) { if (!closed.get()) notice(null); continue }
                    val active = translator ?: withContext(NonCancellable) { open() }.also { translator = it }
                    if (closed.get() || !isActive) break
                    val direction = TranslationDirection(source, to)
                    check(direction in active.directions) { "${active.id} does not support $source → $to; CC continues" }
                    val text = active.translate(request.text, direction)
                    check(text.isNotBlank()) { "${active.id} returned empty translation" }
                    check(clock() - request.at < maxAgeMs) { "Translation arrived too late; this line stays CC only" }
                    if (!closed.get() && isActive) { result(request.id, text, clock() - request.at); notice(null) }
                } catch (e: CancellationException) { throw e }
                catch (e: LinkageError) { if (!closed.get()) notice(e.toString()) }
                catch (e: Exception) { if (!closed.get()) notice(e.message ?: e.toString()) }
            }
        } finally { withContext(NonCancellable) { translator?.close(); translator = null } }
    }
    fun offer(id: Long, text: String, source: String?): Boolean {
        if (closed.get() || text.isBlank()) return false
        if (text.length > 2000) { notice("Caption exceeds 2000 characters; this line stays CC only"); return false }
        return queue.trySend(Request(id, text, source, clock())).isSuccess.also {
            if (!it && !closed.get()) notice("Local translation queue is full; this line stays CC only")
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        translator?.cancel()
        queue.cancel(); job.cancel()
    }
    suspend fun awaitClosed() { job.join() }
}

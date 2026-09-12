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
    private val progress: (String) -> Unit = {},
) : AutoCloseable {
    private data class Request(val id: Long, val text: String, val source: String?, val at: Long, val generation: Long)
    private val lock = Any()
    private var generation = 0L
    private var pending = 0
    /** Current timeline's queued and in-flight work, including requests waiting for model load. */
    val hasPendingWork: Boolean get() = synchronized(lock) { !closed.get() && pending > 0 }
    private val closed = AtomicBoolean(false)
    private val queue = Channel<Request>(capacity)
    @Volatile private var translator: CancellableTextTranslator? = null
    private val job = scope.launch(Dispatchers.IO) {
        try {
            // Prepare once, before the first final, instead of repeatedly hashing/loading
            // large weights on the critical path of each failed caption.
            progress("Preparing translator · CC continues")
            try {
                translator = withContext(NonCancellable) { open() }
                if (closed.get() || !isActive) return@launch
                progress("Translator ready · waiting for a completed caption")
            } catch (e: CancellationException) { throw e }
            catch (e: LinkageError) { if (!closed.get()) notice(e.toString()); return@launch }
            catch (e: Exception) { if (!closed.get()) notice(e.message ?: e.toString()); return@launch }
            val preparedAt = clock()
            for (request in queue) {
                if (closed.get()) break
                if (!isCurrent(request)) continue
                try {
                    check(clock() - maxOf(request.at, preparedAt) < maxAgeMs) { "Translation queue is behind; this line stays CC only" }
                    val source = request.source?.let(TranslationLanguages::normalize)
                    check(source != null && source !in setOf("", "auto", "mul", "und")) {
                        "Source language is unknown or mixed. Choose the spoken language to enable local translation; CC continues"
                    }
                    val to = TranslationLanguages.normalize(target)
                    if (source == to) { publish(request) { notice(null) }; continue }
                    val active = checkNotNull(translator)
                    if (closed.get() || !isActive) break
                    val direction = TranslationDirection(source, to)
                    check(direction in active.directions) { "${active.id} does not support $source → $to; CC continues" }
                    if (!isCurrent(request)) continue
                    publish(request) { progress("Translating ${source} → $to · CC continues") }
                    val text = active.translate(request.text, direction)
                    check(text.isNotBlank()) { "${active.id} returned empty translation" }
                    check(clock() - maxOf(request.at, preparedAt) < maxAgeMs) { "Translation arrived too late; this line stays CC only" }
                    if (isActive) publish(request) { result(request.id, text, clock() - request.at); notice(null) }
                    publish(request) { progress("Translator ready") }
                } catch (e: CancellationException) { throw e }
                catch (e: LinkageError) { publish(request) { notice(e.toString()) } }
                catch (e: Exception) { publish(request) { notice(e.message ?: e.toString()) } }
                finally { synchronized(lock) { if (!closed.get() && request.generation == generation) pending-- } }
            }
        } finally {
            synchronized(lock) { closed.set(true); pending = 0; queue.cancel() }
            withContext(NonCancellable) { translator?.close(); translator = null }
        }
    }
    fun offer(id: Long, text: String, source: String?): Boolean = synchronized(lock) {
        if (closed.get() || text.isBlank()) return false
        if (text.length > 2000) { notice("Caption exceeds 2000 characters; this line stays CC only"); return false }
        queue.trySend(Request(id, text, source, clock(), generation)).isSuccess.also {
            if (it) pending++
            else if (!closed.get()) notice("Local translation queue is full; this line stays CC only")
        }
    }

    /**
     * Clear the caption timeline without hashing/reloading the translator. An already-running
     * native call may finish, but its result and notices are discarded. cancel() permanently
     * retires the current native session, so it is reserved for close().
     */
    fun reset() = synchronized(lock) {
        if (closed.get()) return
        generation++
        while (queue.tryReceive().isSuccess) { }
        pending = 0
        notice(null)
    }

    private fun isCurrent(request: Request) = synchronized(lock) {
        !closed.get() && request.generation == generation
    }
    private inline fun publish(request: Request, block: () -> Unit) = synchronized(lock) {
        if (!closed.get() && request.generation == generation) block()
    }

    override fun close() {
        synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return
            pending = 0
            queue.cancel(); job.cancel()
        }
        translator?.cancel()
    }
    suspend fun awaitClosed() { job.join() }
}

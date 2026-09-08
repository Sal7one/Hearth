package com.sal7one.common_jni.speech

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

internal interface SpeechDriver {
    fun probe(backend: String): String
    fun create(config: String): Long
    fun push(handle: Long, buffer: ByteBuffer, byteOffset: Int, byteCount: Int, sampleOffset: Long): String
    fun finish(handle: Long): String
    fun reset(handle: Long)
    fun destroy(handle: Long)
}
internal object SpeechNative : SpeechDriver {
    init { System.loadLibrary("common_jni") }
    external override fun probe(backend: String): String
    external override fun create(config: String): Long
    external override fun push(handle: Long, buffer: ByteBuffer, byteOffset: Int, byteCount: Int, sampleOffset: Long): String
    external override fun finish(handle: Long): String
    external override fun reset(handle: Long)
    external override fun destroy(handle: Long)
}

/** Local-only entry point. UI and downloaders supply a verified, app-private package directory. */
class SpeechRuntime internal constructor(private val driver: () -> SpeechDriver) {
    constructor() : this({ SpeechNative })
    fun availability(backend: SpeechBackend): SpeechAvailability = try {
        val p = JSONObject(driver().probe(backend.id))
        SpeechAvailability(backend, p.getBoolean("available"), p.getString("revision").ifBlank { null }, p.getString("error").ifBlank { null })
    } catch (e: LinkageError) {
        SpeechAvailability(backend, false, null, e.message ?: e.toString())
    }

    suspend fun open(packageDirectory: File, options: SpeechOptions = SpeechOptions(), onStage: (String) -> Unit = {}): SpeechSession {
        var handle = 0L
        var native: SpeechDriver? = null
        try {
            return withContext(Dispatchers.IO) {
                onStage("verifying package files")
                val model = SpeechModelPackage.verify(packageDirectory)
                currentCoroutineContext().ensureActive()
                options.validate(model.profile)
                native = driver()
                val c = JSONObject().put("backend", model.profile.backend.id)
                listOf("model", "frontend", "encoder", "decoder", "tokenizer").forEach { c.put(it, model.path(it)) }
                c.put("language", if (model.profile.backend == SpeechBackend.NEMOTRON_3_5) SpeechLanguage.nemoLocale(options.sourceLanguage) else "auto")
                    .put("numThreads", options.numThreads ?: 4).put("rightContext", options.rightContext)
                    .put("maxUtteranceMs", options.maxUtteranceMs).put("silenceMs", options.silenceMs)
                    .put("silenceThresholdDb", options.silenceThresholdDb)
                onStage("creating native recognizer")
                handle = native!!.create(c.toString())
                check(handle != 0L) { "Speech backend returned an invalid handle" }
                onStage("native recognizer created")
                SpeechSession(native!!, handle, model.profile)
            }
        } catch (e: Throwable) {
            // Includes cancellation on the IO -> caller dispatcher boundary after native allocation.
            if (handle != 0L) withContext(NonCancellable + Dispatchers.IO) { native!!.destroy(handle) }
            throw e
        }
    }
}

/**
 * One stream, with natural backpressure: accept() returns after inference.
 * Use LiveSpeechProcessor for a non-blocking recorder callback. Never call on the recorder thread.
 * PCM is mono 16kHz, little-endian signed 16-bit. Silence must be passed through.
 * Native decoding is not preemptible: close invalidates immediately and waits safely for an in-flight call.
 */
class SpeechSession internal constructor(
    private val driver: SpeechDriver,
    private var handle: Long,
    val profile: SpeechProfile,
) {
    private val mutex = Mutex()
    private val closing = AtomicBoolean(false)
    private var accepted = 0L
    private var finished = false
    private var failure: Throwable? = null
    private val pcm = ByteBuffer.allocateDirect(32000).order(ByteOrder.LITTLE_ENDIAN)

    suspend fun accept(samples: ShortArray, sampleOffset: Long): SpeechUpdate = operation {
        require(samples.size in 1..16000) { "Speech accept requires 1..16000 samples" }
        require(sampleOffset == accepted) { "Speech audio discontinuity: expected sample $accepted, received $sampleOffset" }
        check(!finished) { "Speech input is already finished" }
        pcm.clear()
        samples.forEach { pcm.putShort(it) }
        val expected = accepted + samples.size
        val start = System.nanoTime()
        val update = parse(driver.push(handle, pcm, 0, samples.size * 2, sampleOffset), System.nanoTime() - start)
        check(update.acceptedSamples == expected) { "Speech backend accepted an unexpected sample count" }
        accepted = expected
        update
    }

    /** Flushes the remaining utterance. Explicit Stop should call close(), which discards the tail. */
    suspend fun finish(): SpeechUpdate = operation {
        check(!finished) { "Speech input is already finished" }
        val start = System.nanoTime()
        parse(driver.finish(handle), System.nanoTime() - start).also { finished = true }
    }

    /** New audio timeline starting at offset zero. Keep the loaded model. */
    suspend fun reset() {
        mutex.withLock {
            check(!closing.get()) { "Speech session is closed" }
            try {
                withContext(Dispatchers.IO) { driver.reset(handle) }
                accepted = 0; finished = false; failure = null
            } catch (e: Throwable) { failure = e; throw e }
        }
    }

    suspend fun close() {
        closing.set(true)
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                val old = handle; handle = 0
                if (old != 0L) driver.destroy(old)
            }
        }
    }

    private suspend fun <T> operation(block: () -> T): T = mutex.withLock {
        check(!closing.get()) { "Speech session is closed" }
        failure?.let { throw it }
        try {
            val result = withContext(Dispatchers.IO) { block() }
            check(!closing.get()) { "Speech session is closed" }
            result
        } catch (e: Throwable) {
            // If cancellation discarded a native result, continuing would lose a final.
            // Require reset/close before reuse, rather than silently continuing that stream.
            failure = e
            throw e
        }
    }

    private fun parse(raw: String, nanos: Long): SpeechUpdate {
        val j = JSONObject(raw)
        val events = j.getJSONArray("events")
        require(events.length() <= 64) { "Unbounded speech results" }
        val transcripts = (0 until events.length()).map { i ->
            val e = events.getJSONObject(i)
            SpeechTranscript(e.getLong("utteranceId"), e.getLong("revision"), e.getString("text"),
                e.getString("language").ifBlank { null }?.let(SpeechLanguage::normalize), e.getBoolean("final"), e.getLong("audioEndSamples"))
        }
        return SpeechUpdate(transcripts, j.getLong("acceptedSamples"), nanos)
    }
}

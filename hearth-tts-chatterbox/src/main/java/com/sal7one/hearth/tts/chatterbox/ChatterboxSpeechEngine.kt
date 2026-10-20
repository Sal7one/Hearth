package com.sal7one.hearth.tts.chatterbox

import com.sal7one.common_jni.voice.ChatterboxNative
import com.sal7one.common_jni.voice.ChatterboxPcmCallback
import com.sal7one.hearth.tts.*
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Full-case NFKD V3 frontend, cached greedy Q4 generator, mandatory Perth watermark. */
class ChatterboxSpeechEngine(private val directory: File, private val threads: Int = 2) : SpeechEngine {
    override val id = "chatterbox-v3"
    override val maximumTextCharacters = 120
    override val voices = ChatterboxCatalog.voices.map { SpeechVoice(it, it, ChatterboxCatalog.languages, VoiceGender.UNKNOWN) }
    private val handle = AtomicLong(0)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var closed = false
    @Synchronized override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
        check(!closed) { "Chatterbox engine is closed" }
        require(voice in voices && voice.supports(language)) { "Unsupported Chatterbox voice or language" }
        require(options.rate == 1f) { "Chatterbox synthesis supports normal speed; apply speed in playback" }
        cancellation.throwIfCancelled()
        if(cancelled.getAndSet(false))handle.getAndSet(0).takeIf { it != 0L }?.let(ChatterboxNative::destroy)
        if(handle.get() == 0L) {
            check(available()) { "Chatterbox CPU runtime is unavailable for this process ABI" }
            ChatterboxModels(directory).verify { cancellation.throwIfCancelled(); checkCancelled() }
            val created = ChatterboxNative.create(directory.absolutePath.toByteArray(), threads)
            check(created != 0L) { "Chatterbox returned an invalid native handle" }
            try { check(ChatterboxNative.voices(created).toList() == ChatterboxCatalog.voices) { "Chatterbox reference voice inventory does not match pinned metadata" } }
            catch(error: Throwable) { ChatterboxNative.destroy(created); throw error }
            handle.set(created)
            if(closed){handle.getAndSet(0).takeIf { it != 0L }?.let(ChatterboxNative::destroy);error("Chatterbox engine is closed")}
        }
        cancellation.throwIfCancelled();checkCancelled()
        try {
            ChatterboxNative.synthesize(handle.get(),text.toByteArray(),language.substringBefore('-').lowercase().toByteArray(),voice.id.toByteArray(),ChatterboxPcmCallback { samples, rate ->
                cancellation.throwIfCancelled();if(cancelled.get())return@ChatterboxPcmCallback false
                emit(PcmAudio(samples,rate));true
            })
        } catch(error: Throwable) {
            // A callback/ORT failure may leave terminal per-run cancellation or partial codec state.
            cancelled.set(true)
            throw error
        }
    }
    private fun checkCancelled() { if(cancelled.get())throw CancellationException("Chatterbox speech stopped") }
    override fun cancel() { cancelled.set(true);handle.get().takeIf { it != 0L }?.let(ChatterboxNative::cancel) }
    override fun close() { closed=true;cancelled.set(true);handle.getAndSet(0).takeIf { it != 0L }?.let(ChatterboxNative::destroy) }
    companion object { fun available(): Boolean = try { ChatterboxNative.available() } catch (_: LinkageError) { false } }
}

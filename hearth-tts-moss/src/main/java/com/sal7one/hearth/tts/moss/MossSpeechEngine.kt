package com.sal7one.hearth.tts.moss

import com.sal7one.common_jni.voice.MossNative
import com.sal7one.common_jni.voice.MossPcmCallback
import com.sal7one.hearth.tts.*
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Real SentencePiece text frontend and cached ONNX generator/codec; stereo is explicitly averaged. */
class MossSpeechEngine(private val directory: File, private val threads: Int = 2) : SpeechEngine {
    override val id = "moss-nano"
    override val maximumTextCharacters = 120
    override val voices = MossCatalog.voices.map { SpeechVoice(it, it, MossCatalog.languages, VoiceGender.UNKNOWN) }
    private val handle = AtomicLong(0)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var closed = false
    @Synchronized override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
        check(!closed) { "MOSS engine is closed" }
        require(voice in voices && voice.supports(language)) { "Unsupported MOSS voice or language" }
        require(options.rate == 1f) { "MOSS synthesis supports normal speed; apply speed in playback" }
        cancellation.throwIfCancelled()
        if(cancelled.getAndSet(false))handle.getAndSet(0).takeIf { it != 0L }?.let(MossNative::destroy)
        if(handle.get() == 0L) {
            check(available()) { "MOSS CPU runtime is unavailable for this process ABI" }
            MossModels(directory).verify { cancellation.throwIfCancelled(); checkCancelled() }
            val created = MossNative.create(directory.absolutePath.toByteArray(), threads)
            check(created != 0L) { "MOSS returned an invalid native handle" }
            try { check(MossNative.voices(created).toList() == MossCatalog.voices) { "MOSS reference voice inventory does not match pinned metadata" } }
            catch(error: Throwable) { MossNative.destroy(created); throw error }
            handle.set(created)
            if(closed){handle.getAndSet(0).takeIf { it != 0L }?.let(MossNative::destroy);error("MOSS engine is closed")}
        }
        cancellation.throwIfCancelled();checkCancelled()
        try {
            MossNative.synthesize(handle.get(),text.toByteArray(),voice.id.toByteArray(),MossPcmCallback { samples, rate ->
                cancellation.throwIfCancelled();if(cancelled.get())return@MossPcmCallback false
                emit(PcmAudio(samples,rate));true
            })
        } catch(error: Throwable) {
            // A callback/ORT failure may leave terminal per-run cancellation or partial codec state.
            cancelled.set(true)
            throw error
        }
    }
    private fun checkCancelled() { if(cancelled.get())throw CancellationException("MOSS speech stopped") }
    override fun cancel() { cancelled.set(true);handle.get().takeIf { it != 0L }?.let(MossNative::cancel) }
    override fun close() { closed=true;cancelled.set(true);handle.getAndSet(0).takeIf { it != 0L }?.let(MossNative::destroy) }
    companion object { fun available(): Boolean = try { MossNative.available() } catch (_: LinkageError) { false } }
}

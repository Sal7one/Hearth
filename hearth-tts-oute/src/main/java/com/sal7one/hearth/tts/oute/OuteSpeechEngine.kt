package com.sal7one.hearth.tts.oute

import com.sal7one.common_jni.voice.OuteNative
import com.sal7one.common_jni.voice.OutePcmCallback
import com.sal7one.hearth.tts.*
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** CPU-only Oute 1.0. Generation finishes before bounded PCM delivery begins. */
class OuteSpeechEngine(private val directory: File) : SpeechEngine {
    override val id = "oute"
    override val maximumTextCharacters = 180
    override val voices = OuteCatalog.voices.map { SpeechVoice(it, "English reference 1 · cross-lingual Arabic", OuteCatalog.languages) }
    private val handle = AtomicLong(0)
    private val cancelled = AtomicBoolean(false)
    private var closed = false
    @Synchronized override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions,
        cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
        check(!closed) { "OuteTTS engine is closed" }
        require(voice in voices && voice.supports(language)) { "Unsupported OuteTTS reference voice or language" }
        require(text.isNotBlank() && text.codePointCount(0,text.length) <= maximumTextCharacters) { "OuteTTS text exceeds its bounded utterance size" }
        require(options.rate == 1f) { "OuteTTS synthesis requires normal speed; apply speed in playback" }
        cancellation.throwIfCancelled()
        if(cancelled.getAndSet(false)) handle.getAndSet(0).takeIf { it != 0L }?.let(OuteNative::destroy)
        if(handle.get() == 0L) {
            check(available()) { "OuteTTS CPU runtime is unavailable for this process ABI" }
            OuteModels(directory).verify { cancellation.throwIfCancelled(); checkCancelled() }
            val assets=OuteCatalog.assets
            handle.set(OuteNative.create(File(directory,assets[0].filename).absolutePath.toByteArray(),
                File(directory,assets[1].filename).absolutePath.toByteArray()))
            check(handle.get()!=0L) { "OuteTTS returned an invalid native handle" }
        }
        cancellation.throwIfCancelled();checkCancelled()
        OuteNative.synthesize(handle.get(),text.toByteArray(),OutePcmCallback { samples,rate ->
            cancellation.throwIfCancelled();if(cancelled.get()) return@OutePcmCallback false
            emit(PcmAudio(samples,rate));true
        })
    }
    private fun checkCancelled(){if(cancelled.get())throw CancellationException("OuteTTS speech stopped")}
    override fun cancel(){cancelled.set(true);handle.get().takeIf {it!=0L}?.let(OuteNative::cancel)}
    @Synchronized override fun close(){if(!closed){closed=true;handle.getAndSet(0).takeIf {it!=0L}?.let(OuteNative::destroy)}}
    companion object { fun available(): Boolean = try { OuteNative.available() } catch (_: LinkageError) { false } }
}

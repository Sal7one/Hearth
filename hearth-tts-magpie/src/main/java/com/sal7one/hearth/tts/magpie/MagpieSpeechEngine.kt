package com.sal7one.hearth.tts.magpie

import com.sal7one.common_jni.voice.MagpieNative
import com.sal7one.common_jni.voice.MagpiePcmCallback
import com.sal7one.hearth.tts.*
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Real native NeMo Magpie PCM streaming. CPU runtime is currently supplied for arm64 only. */
class MagpieSpeechEngine(private val directory: File,private val threads: Int=4) : SpeechEngine {
    override val id="magpie-v2607"
    override val maximumTextCharacters=180
    override val voices=MagpieCatalog.speakers.map { SpeechVoice(it,it,MagpieCatalog.languages,VoiceGender.UNKNOWN) }
    private val handle=AtomicLong(0)
    private val cancelled=AtomicBoolean(false)
    @Volatile private var closed=false
    @Synchronized override fun synthesize(text: String,language: String,voice: SpeechVoice,options: SpeechOptions,cancellation: SpeechCancellation,emit: (PcmAudio)->Unit) {
        check(!closed){"Magpie engine is closed"};require(voice in voices && voice.supports(language)){"Unsupported Magpie voice or language"}
        require(options.rate==1f){"Magpie native synthesis currently supports normal speed only"}
        cancellation.throwIfCancelled()
        if(cancelled.getAndSet(false))handle.getAndSet(0).takeIf {it!=0L}?.let(MagpieNative::destroy)
        if(handle.get()==0L) {
            check(available()){"Magpie CPU runtime is unavailable for this Android ABI"}
            MagpieModels(directory).verify {cancellation.throwIfCancelled();if(cancelled.get())throw CancellationException("Magpie speech stopped")}
            val created=MagpieNative.create(File(directory,MagpieCatalog.generator).absolutePath.toByteArray(),File(directory,MagpieCatalog.codec).absolutePath.toByteArray(),File(directory,"tokenizer").absolutePath.toByteArray(),threads)
            check(created!=0L){"Magpie returned an invalid native handle"}
            try { check(MagpieNative.voices(created).toList()==MagpieCatalog.speakers){"Magpie speaker metadata does not match pinned v2607 voices"} }
            catch(error: Throwable){MagpieNative.destroy(created);throw error}
            handle.set(created)
            if(closed){handle.getAndSet(0).takeIf {it!=0L}?.let(MagpieNative::destroy);error("Magpie engine is closed")}
        }
        cancellation.throwIfCancelled();if(cancelled.get())throw CancellationException("Magpie speech stopped")
        MagpieNative.synthesize(handle.get(),text.toByteArray(),MagpieCatalog.nativeLanguage(language).toByteArray(),voice.id.toByteArray(),MagpiePcmCallback {samples,rate ->
            cancellation.throwIfCancelled();if(cancelled.get())return@MagpiePcmCallback false
            emit(PcmAudio(FloatArray(samples.size){samples[it]/32768f},rate));true
        })
    }
    override fun cancel(){cancelled.set(true);handle.get().takeIf {it!=0L}?.let(MagpieNative::cancel)}
    override fun close(){closed=true;cancelled.set(true);handle.getAndSet(0).takeIf {it!=0L}?.let(MagpieNative::destroy)}
    companion object { fun available(): Boolean = try {MagpieNative.available()}catch(_: LinkageError){false} }
}

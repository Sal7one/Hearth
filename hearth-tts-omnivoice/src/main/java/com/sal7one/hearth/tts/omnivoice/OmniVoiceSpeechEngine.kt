package com.sal7one.hearth.tts.omnivoice

import com.sal7one.common_jni.voice.OmniVoiceNative
import com.sal7one.common_jni.voice.OmniVoicePcmCallback
import com.sal7one.hearth.tts.*
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Optional CPU-only native engine. Designed voices are attributes, not fixed speaker identities. */
class OmniVoiceSpeechEngine(private val directory: File) : SpeechEngine {
    override val id = "omnivoice"
    override val maximumTextCharacters = 180
    override val voices = OmniVoiceCatalog.designs.map { name -> SpeechVoice(name, when(name) {
        "design-female" -> "Female voice design (unverified)"
        "design-male" -> "Male voice design (unverified)"
        else -> "Automatic voice design"
    }, OmniVoiceCatalog.languages, VoiceGender.UNKNOWN) }
    private val handle = AtomicLong(0)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var closed = false
    @Synchronized override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
        check(!closed) { "OmniVoice engine is closed" }
        require(voice in voices && voice.supports(language)) { "Unsupported OmniVoice voice or language" }
        require(options.rate == 1f) { "OmniVoice synthesis supports normal speed; apply playback speed in your player" }
        cancellation.throwIfCancelled()
        if (cancelled.getAndSet(false)) handle.getAndSet(0).takeIf { it != 0L }?.let(OmniVoiceNative::destroy)
        if (handle.get() == 0L) {
            check(available()) { "OmniVoice CPU runtime is unavailable for this process ABI" }
            OmniVoiceModels(directory).verify { cancellation.throwIfCancelled(); checkCancelled() }
            val nativeLanguages = OmniVoiceNative.languages().toSet()
            check(OmniVoiceCatalog.languages.all { OmniVoiceCatalog.nativeLanguage(it) in nativeLanguages }) { "OmniVoice runtime does not support the pinned languages" }
            val assets = OmniVoiceCatalog.assets
            val created = OmniVoiceNative.create(File(directory, assets[0].filename).absolutePath.toByteArray(), File(directory, assets[1].filename).absolutePath.toByteArray())
            check(created != 0L) { "OmniVoice returned an invalid native handle" }; handle.set(created)
            if (closed) { handle.getAndSet(0).takeIf { it != 0L }?.let(OmniVoiceNative::destroy); error("OmniVoice engine is closed") }
        }
        cancellation.throwIfCancelled(); checkCancelled()
        OmniVoiceNative.synthesize(handle.get(), text.toByteArray(), OmniVoiceCatalog.nativeLanguage(language).toByteArray(), OmniVoiceCatalog.designs.indexOf(voice.id), OmniVoicePcmCallback { samples, rate ->
            cancellation.throwIfCancelled(); if (cancelled.get()) return@OmniVoicePcmCallback false
            emit(PcmAudio(samples, rate)); true
        })
    }
    private fun checkCancelled() { if (cancelled.get()) throw CancellationException("OmniVoice speech stopped") }
    override fun cancel() { cancelled.set(true); handle.get().takeIf { it != 0L }?.let(OmniVoiceNative::cancel) }
    override fun close() { closed = true; cancelled.set(true); handle.getAndSet(0).takeIf { it != 0L }?.let(OmniVoiceNative::destroy) }
    companion object { fun available(): Boolean = try { OmniVoiceNative.available() } catch (_: LinkageError) { false } }
}

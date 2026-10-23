package com.sal7one.hearth.tts.ming

import com.sal7one.common_jni.MingNative
import com.sal7one.hearth.tts.PcmAudio
import com.sal7one.hearth.tts.SpeechCancellation
import com.sal7one.hearth.tts.SpeechEngine
import com.sal7one.hearth.tts.SpeechOptions
import com.sal7one.hearth.tts.SpeechVoice
import com.sal7one.hearth.tts.VoiceGender
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** The blocking JNI call, separated so host tests can exercise engine preflight without an ELF. */
internal fun interface MingSynthesizer {
    fun synthesize(bundle: File, text: String, voice: String, outWav: File, threads: Int): String
}

internal object MingJniSynthesizer : MingSynthesizer {
    override fun synthesize(bundle: File, text: String, voice: String, outWav: File, threads: Int): String =
        MingNative.synthesizeWav(
            bundle.absolutePath, text, voice, outWav.absolutePath,
            MingCatalog.PRESET, MingCatalog.SEED, threads,
        )
}

internal interface MingAvailability {
    fun isAvailable(): Boolean
    fun reason(): String
}

internal object MingProcessAvailability : MingAvailability {
    override fun isAvailable(): Boolean = MingNative.availability.loaded
    override fun reason(): String = MingNative.availability.reason
}

/**
 * Experimental CPU-only Ming-3.0-tts-0.5B engine for Android.
 *
 * The shared JNI is one-shot: it loads the ~850 MB bundle, produces the complete utterance, and
 * returns. There is no streaming and no native cancel. [cancel] prevents later chunks from being
 * delivered and stops the next request, but a JNI call that already started finishes before the
 * engine can observe it. This class advertises English and Chinese only and is capped at 300
 * characters; article chunking and playback speed remain [com.sal7one.hearth.tts.ArticleReader]'s job.
 */
class MingSpeechEngine internal constructor(
    private val directory: File,
    private val models: MingModels,
    private val synthesizer: MingSynthesizer,
    private val availability: MingAvailability,
) : SpeechEngine {
    constructor(directory: File) : this(directory, MingModels(directory), MingJniSynthesizer, MingProcessAvailability)

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hearth-tts-ming-jni").apply { isDaemon = true }
    }
    private val cancelled = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var verified = false

    override val id: String = MingCatalog.ENGINE_ID
    override val maximumTextCharacters: Int = MingCatalog.MAXIMUM_TEXT_CHARACTERS
    override val voices: List<SpeechVoice> = MingCatalog.voices.map {
        SpeechVoice(it, it, MingCatalog.languages, VoiceGender.UNKNOWN)
    }

    @Synchronized
    override fun synthesize(
        text: String,
        language: String,
        voice: SpeechVoice,
        options: SpeechOptions,
        cancellation: SpeechCancellation,
        emit: (PcmAudio) -> Unit,
    ) {
        check(!closed) { "Ming local engine is closed" }
        require(text.length in 1..maximumTextCharacters) { "Ming local accepts 1–300 characters per request" }
        require(MingCatalog.language(language) != null) { "Ming local built-in voices support English and Chinese only" }
        require(voice.id in MingCatalog.voices && voice.supports(language)) { "Unsupported Ming voice or language" }
        require(options.rate == 1f) { "Ming local synthesis requires normal speed; apply speed in playback" }
        cancellation.throwIfCancelled()
        if (cancelled.getAndSet(false)) throw CancellationException("Ming speech stopped")
        if (!availability.isAvailable()) error("Ming local TTS is unavailable: ${availability.reason()}")
        if (!verified) {
            models.verify {
                cancellation.throwIfCancelled()
                if (cancelled.get()) throw CancellationException("Ming speech stopped")
            }
            verified = true
        }
        val output = File.createTempFile("hearth-ming-", ".wav", directory)
        try {
            synthesizeOnWorker(text, voice.id, output)
            val pcm = MingWav.decode(output.readBytes())
            check(pcm.samples.isNotEmpty()) { "Ming produced no speech" }
            var offset = 0
            while (offset < pcm.samples.size) {
                cancellation.throwIfCancelled()
                if (cancelled.get()) throw CancellationException("Ming speech stopped")
                val end = minOf(offset + MingCatalog.SAMPLE_RATE, pcm.samples.size)
                emit(PcmAudio(pcm.samples.copyOfRange(offset, end), pcm.sampleRate))
                offset = end
            }
        } finally {
            output.delete()
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun close() {
        closed = true
        cancelled.set(true)
        worker.shutdown()
    }

    private fun synthesizeOnWorker(text: String, voice: String, output: File): String {
        val future = try {
            worker.submit(Callable {
                synthesizer.synthesize(directory, text, voice, output, MingCatalog.defaultThreads())
            })
        } catch (error: RejectedExecutionException) {
            throw IllegalStateException("Ming local engine is closed", error)
        }
        return try {
            future.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Ming speech stopped")
        }
    }

    companion object {
        /** No fake availability: this is false when the arm64 JNI is absent or not loadable. */
        fun available(): Boolean = try {
            MingProcessAvailability.isAvailable()
        } catch (_: LinkageError) {
            false
        }

        /** The real load/ABI reason shown by the settings gate. */
        fun availabilityReason(): String = try {
            MingProcessAvailability.reason()
        } catch (error: LinkageError) {
            "Ming JNI is unavailable in this process: ${error.message}"
        }
    }
}

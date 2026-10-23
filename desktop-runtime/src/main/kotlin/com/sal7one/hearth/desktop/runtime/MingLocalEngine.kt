package com.sal7one.hearth.desktop.runtime

import com.sal7one.common_jni.MingNative
import com.sal7one.hearth.tts.PcmAudio
import com.sal7one.hearth.tts.SpeechCancellation
import com.sal7one.hearth.tts.SpeechEngine
import com.sal7one.hearth.tts.SpeechOptions
import com.sal7one.hearth.tts.SpeechVoice
import com.sal7one.hearth.tts.VoiceGender
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental desktop-only Ming TTS. The JNI one-shot loads the 850 MB bundle, writes
 * a 44.1 kHz mono WAV and returns; there is no streaming or native cancel.
 */
internal class MingLocalEngine(private val bundle: File, threads: Int) : SpeechEngine {
    private val threadCount = threads.coerceIn(1, 8)
    init { MingLocalModel.prepare(bundle.toPath()) }
    override val id = "ming-local"
    override val maximumTextCharacters = 300
    override val voices = listOf("US_James", "US_Emma", "US_Stella", "UK_George", "UK_Charlotte", "灵小柔", "灵楷")
        .map { SpeechVoice(it, it, setOf("en", "zh"), VoiceGender.UNKNOWN) }
    private val cancelled = AtomicBoolean(false)
    @Volatile private var closed = false

    @Synchronized
    override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions, cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
        check(!closed) { "Ming local engine is closed" }
        require(text.length in 1..maximumTextCharacters) { "Ming local accepts 1–300 characters per request" }
        val normalized = MingLocalModel.language(language)
            ?: throw IllegalArgumentException("Ming local built-in voices support English and Chinese only")
        require(voice.supports(normalized)) { "Unsupported Ming voice or language" }
        require(options.rate == 1f) { "Ming local synthesis requires normal speed; apply speed in playback" }
        require(voice in voices) { "Unsupported Ming voice" }
        cancellation.throwIfCancelled()
        if (cancelled.getAndSet(false)) throw CancellationException("Ming speech stopped")
        val output = Files.createTempFile("hearth-ming-", ".wav")
        try {
            MingNative.synthesizeWav(bundle.absolutePath, text, voice.id, output.toString(), 0, 1895L, threadCount)
            val samples = readWav(output)
            check(samples.isNotEmpty()) { "Ming produced no speech" }
            var offset = 0
            while (offset < samples.size) {
                cancellation.throwIfCancelled()
                if (cancelled.get()) throw CancellationException("Ming speech stopped")
                val end = minOf(offset + 44_100, samples.size)
                emit(PcmAudio(samples.copyOfRange(offset, end), 44_100))
                offset = end
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }

    override fun cancel() { cancelled.set(true) }
    override fun close() { closed = true; cancelled.set(true) }

    private fun readWav(path: java.nio.file.Path): FloatArray {
        val bytes = Files.readAllBytes(path)
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Ming returned a non-WAV file" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var format = -1; var channels = -1; var rate = -1; var bits = -1
        var dataStart = -1; var dataSize = -1
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            require(size >= 0 && body + size <= bytes.size) { "Ming returned a malformed WAV" }
            when (id) {
                "fmt " -> { format = buffer.getShort(body).toInt(); channels = buffer.getShort(body + 2).toInt(); rate = buffer.getInt(body + 4); bits = buffer.getShort(body + 14).toInt() }
                "data" -> { dataStart = body; dataSize = size }
            }
            offset = body + size + (size and 1)
        }
        require(format == 1 && channels == 1 && rate == 44_100 && bits == 16 && dataStart >= 0 && dataSize > 0) {
            "Ming must return mono 44.1 kHz PCM16 WAV"
        }
        val samples = FloatArray(dataSize / 2)
        var index = 0
        while (index < samples.size) { samples[index] = buffer.getShort(dataStart + index * 2) / 32768f; index++ }
        return samples
    }
}

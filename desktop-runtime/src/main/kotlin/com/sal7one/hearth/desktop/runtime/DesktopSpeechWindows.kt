package com.sal7one.hearth.desktop.runtime

import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PcmFrame
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

internal data class DesktopSpeechWindow(val bytes: ByteArray, val startSample: Long, val endSample: Long)

/**
 * Bounded 16 kHz PCM16 utterances. Live input retains 200 ms before voice onset instead of
 * sending an entire idle window to the model. Offsets always refer to the original source,
 * including skipped silence. Files keep every sample and use their longer batching default.
 */
internal class DesktopSpeechWindows(private val live: Boolean, windowMs: Int) {
    init { require(windowMs in 1000..30000) { "Speech window must be 1000–30000 ms" } }
    private val limit = windowMs * 32
    private val buffer = ByteArrayOutputStream(limit)
    private val preRoll = ByteArray(200 * 32)
    private var preRollHead = 0
    private var preRollSize = 0
    private var position = 0L
    private var startSample = 0L
    private var speechSamples = 0
    private var silenceSamples = 0

    suspend fun push(frame: PcmFrame, emit: suspend (DesktopSpeechWindow) -> Unit) {
        require(frame.bytes.size % 2 == 0) { "Speech requires complete PCM16 samples" }
        var cursor = 0
        while (cursor < frame.bytes.size) {
            val count = minOf(frame.bytes.size - cursor, 640, limit - buffer.size())
            val voiced = live && voiced(frame.bytes, cursor, count)
            if (live && buffer.size() == 0 && !voiced) {
                rememberSilence(frame.bytes, cursor, count)
            } else {
                if (buffer.size() == 0) {
                    startSample = position - preRollSize / 2
                    if (preRollSize > 0) {
                        val first = minOf(preRollSize, preRoll.size - preRollHead)
                        buffer.write(preRoll, preRollHead, first)
                        buffer.write(preRoll, 0, preRollSize - first)
                        preRollHead = 0; preRollSize = 0
                    }
                }
                buffer.write(frame.bytes, cursor, count)
                if (voiced) { speechSamples += count / 2; silenceSamples = 0 }
                else silenceSamples += count / 2
            }
            cursor += count
            position += count / 2
            if (buffer.size() >= limit || live && speechSamples >= 4000 && silenceSamples >= 9600) flush(emit)
        }
    }

    suspend fun finish(emit: suspend (DesktopSpeechWindow) -> Unit) = flush(emit)

    private suspend fun flush(emit: suspend (DesktopSpeechWindow) -> Unit) {
        if (buffer.size() == 0) return
        val window = DesktopSpeechWindow(buffer.toByteArray(), startSample, position)
        buffer.reset(); speechSamples = 0; silenceSamples = 0
        emit(window)
    }

    private fun rememberSilence(bytes: ByteArray, offset: Int, count: Int) {
        for (index in offset until offset + count) {
            preRoll[(preRollHead + preRollSize) % preRoll.size] = bytes[index]
            if (preRollSize < preRoll.size) preRollSize++ else preRollHead = (preRollHead + 1) % preRoll.size
        }
    }

    private fun voiced(bytes: ByteArray, offset: Int, count: Int): Boolean {
        var energy = 0.0
        for (index in offset until offset + count step 2) {
            val sample = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort() / 32768.0
            energy += sample * sample
        }
        return sqrt(energy / (count / 2)) >= 0.008
    }
}

/** Matches the four-second Standard choice on desktop and Android; explicit tuning wins. */
internal fun desktopSpeechWindowMs(selection: EngineSelection, live: Boolean): Int {
    val spokenWindow = selection.engineId in setOf(
        QwenSpeechModel.ENGINE_ID, "transcribepsy-local", "nemotron-asr-local", "moonshine-local", "vosk-local",
    )
    return (selection.options["windowMs"]?.toIntOrNull() ?: if (live) 4000 else if (spokenWindow) 15000 else 20000)
        .also { require(it in 1000..30000) { "Speech window must be 1000–30000 ms" } }
}

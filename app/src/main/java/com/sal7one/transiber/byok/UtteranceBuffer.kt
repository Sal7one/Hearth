package com.sal7one.transiber.byok

/**
 * Accumulates streaming PCM into whole utterances for cloud STT (BYOK).
 *
 * Pure Kotlin — unit-testable. An utterance closes on either:
 *   • [closeSilenceMs] of continuous "inactive" audio after voice was
 *     seen (the caller supplies the per-chunk activity decision, e.g. a
 *     simple RMS gate), or
 *   • [maxMs] of buffered audio (hard cap so continuous speech uploads
 *     in bounded pieces instead of growing forever).
 *
 * Silence before any voice is dropped, mirroring the native engines'
 * gate-but-never-lose-speech rule.
 */
class UtteranceBuffer(
    private val sampleRate: Int,
    private val maxMs: Int = 12_000,
    private val closeSilenceMs: Int = 650,
) {
    private var samples = ShortArray(sampleRate) // grows on demand
    private var size = 0
    private var bufferedMs = 0.0
    private var silenceMs = 0.0
    private var hasVoice = false

    /**
     * Pushes one chunk; returns the finished utterance when one closes,
     * else null. [active] is the caller's voice-activity decision for the
     * whole chunk.
     */
    fun push(chunk: ShortArray, chunkSampleRate: Int, active: Boolean): ShortArray? {
        if (chunk.isEmpty()) return null

        val silenceBefore = !hasVoice && !active
        if (!silenceBefore) {
            append(resampleToBufferRate(chunk, chunkSampleRate))
            bufferedMs = size * 1000.0 / sampleRate
        }

        if (active) {
            hasVoice = true
            silenceMs = 0.0
        } else {
            silenceMs += chunk.size * 1000.0 / chunkSampleRate
        }

        return when {
            hasVoice && silenceMs >= closeSilenceMs -> take()
            hasVoice && bufferedMs >= maxMs -> take()
            else -> null
        }
    }

    /** Flushes whatever is buffered (end of session), or null if no voice. */
    fun flush(): ShortArray? = if (hasVoice && size > 0) take() else null

    fun clear() {
        size = 0
        bufferedMs = 0.0
        silenceMs = 0.0
        hasVoice = false
    }

    private fun take(): ShortArray {
        val out = samples.copyOf(size)
        clear()
        return out
    }

    private fun append(chunk: ShortArray) {
        if (size + chunk.size > samples.size) {
            var cap = samples.size
            while (cap < size + chunk.size) cap = cap * 2 + 1
            samples = samples.copyOf(cap)
        }
        chunk.copyInto(samples, size)
        size += chunk.size
    }

    private fun resampleToBufferRate(chunk: ShortArray, chunkSampleRate: Int): ShortArray =
        com.sal7one.common_jni.audio.Pcm16Resampler.frame(chunk, chunkSampleRate, sampleRate)
}

package com.sal7one.hearth.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Bounded, anti-aliased PCM16 conversion for 44.1/48 kHz input to 16 kHz speech. */
class SpeechDownsampler private constructor(private val inputRate: Int, private val phases: Int, private val step: Int) {
    private val weights = phaseWeights(inputRate, phases)
    private val history = ShortArray(2 * HISTORY)
    private var received = 0L
    private var produced = 0L
    private var center = 0L
    private var phase = 0
    private var finished = false

    fun push(input: ShortArray, length: Int = input.size): ShortArray {
        check(!finished)
        require(length in 0..input.size)
        if (length == 0) return ShortArray(0)
        if (received == 0L) history.fill(input[0])
        val ready = (received + length - RADIUS).coerceAtLeast(0)
        val available = (ready * phases + step - 1) / step
        val output = ShortArray((available - produced).toInt())
        var offset = 0
        for (index in 0 until length) {
            store(input[index])
            if (center + RADIUS < received) output[offset++] = next()
        }
        return output
    }

    /** Flush the delayed tail, holding the last sample at the input boundary. */
    fun finish(): ShortArray {
        if (finished) return ShortArray(0)
        finished = true
        if (received == 0L) return ShortArray(0)
        val count = (received * OUTPUT_RATE / inputRate - produced).toInt()
        val last = history[(received - 1).toInt() and MASK]
        repeat(RADIUS) { store(last) }
        return ShortArray(count) { next() }
    }

    private fun store(sample: Short) {
        val slot = received.toInt() and MASK
        history[slot] = sample
        history[slot + HISTORY] = sample
        received++
    }

    private fun next(): Short {
        val start = (center - RADIUS).toInt() and MASK
        val bank = weights[phase]
        var sum = 0f
        for (tap in bank.indices) sum += history[start + tap] * bank[tap]
        phase += step
        center += (phase / phases).toLong()
        phase %= phases
        produced++
        return sum.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    companion object {
        private const val OUTPUT_RATE = 16_000
        private const val RADIUS = 64
        private const val HISTORY = 256
        private const val MASK = HISTORY - 1

        fun forRate(inputRate: Int): SpeechDownsampler? = when (inputRate) {
            44_100 -> SpeechDownsampler(inputRate, phases = 160, step = 441)
            48_000 -> SpeechDownsampler(inputRate, phases = 1, step = 3)
            else -> null
        }

        private fun phaseWeights(inputRate: Int, phases: Int): Array<FloatArray> {
            val cutoff = 15_000.0 / inputRate
            return Array(phases) { phase ->
                val taps = DoubleArray(2 * RADIUS + 1) { tap ->
                    val distance = tap - RADIUS - phase.toDouble() / phases
                    val window = distance / RADIUS
                    if (abs(window) >= 1.0) 0.0 else {
                        val x = PI * cutoff * distance
                        val sinc = if (x == 0.0) 1.0 else sin(x) / x
                        cutoff * sinc * (0.42 + 0.5 * cos(PI * window) + 0.08 * cos(2 * PI * window))
                    }
                }
                val sum = taps.sum()
                FloatArray(taps.size) { (taps[it] / sum).toFloat() }
            }
        }
    }
}

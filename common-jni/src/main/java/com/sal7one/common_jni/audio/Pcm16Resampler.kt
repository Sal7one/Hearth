package com.sal7one.common_jni.audio

/** Linear PCM16 interpolation with a continuous sample clock across read boundaries. */
class Pcm16Resampler(private val inputRate: Int, private val outputRate: Int) {
    init { require(inputRate > 0 && outputRate > 0) }
    private var inputIndex = 0L
    private var nextOutputNumerator = 0L
    private var previous: Short = 0

    fun push(samples: ShortArray): ShortArray {
        if (inputRate == outputRate) return samples.copyOf()
        val output = ShortArray(((samples.size.toLong() + 1) * outputRate / inputRate + 1).toInt())
        var size = 0
        for (sample in samples) {
            val end = inputIndex * outputRate
            while (nextOutputNumerator <= end) {
                output[size++] = if (inputIndex == 0L) sample else {
                    val fraction = (nextOutputNumerator - (inputIndex - 1) * outputRate).toDouble() / outputRate
                    (previous * (1 - fraction) + sample * fraction).toInt().toShort()
                }
                nextOutputNumerator += inputRate
            }
            previous = sample
            inputIndex++
        }
        return output.copyOf(size)
    }

    companion object {
        /** Isolated frames have no following sample, so hold their final sample. */
        fun frame(samples: ShortArray, inputRate: Int, outputRate: Int): ShortArray {
            require(inputRate > 0 && outputRate > 0)
            if (samples.isEmpty() || inputRate == outputRate) return samples.copyOf()
            return ShortArray((samples.size.toLong() * outputRate / inputRate).toInt()) { i ->
                val position = i.toLong() * inputRate
                val index = (position / outputRate).toInt().coerceAtMost(samples.lastIndex)
                val fraction = (position % outputRate).toDouble() / outputRate
                (samples[index] * (1 - fraction) + samples[minOf(index + 1, samples.lastIndex)] * fraction).toInt().toShort()
            }
        }
    }
}

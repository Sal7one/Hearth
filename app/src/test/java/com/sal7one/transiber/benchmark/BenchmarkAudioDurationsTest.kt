package com.sal7one.transiber.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkAudioDurationsTest {
    @Test fun spokenRangeIsSeparateFromSilenceWhileTotalIncludesEveryInput() {
        val durations = BenchmarkAudioDurations(listOf(3_600, 8_640, 7_140), listOf(1_000, 2_500))
        assertEquals(3_600L, durations.shortestMs)
        assertEquals(8_640L, durations.longestMs)
        assertEquals(22_880L, durations.totalMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unmeasuredDurationCannotMasqueradeAsAZeroSecondClip() {
        BenchmarkAudioDurations(listOf(0), emptyList())
    }
}

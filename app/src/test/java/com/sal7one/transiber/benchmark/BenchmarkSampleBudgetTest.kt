package com.sal7one.transiber.benchmark

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BenchmarkSampleBudgetTest {
    @Test fun loadingAndRepeatedClipsDoNotShareOneInferenceDeadline() = runBlocking {
        var now = 0L
        val budget = BenchmarkSampleBudget(20) { now }
        now += 95_000_000_000L // Integrity checks and model loading have their own bounded phase.
        val measured = mutableListOf<Double>()
        repeat(3) {
            repeat(7) { sample ->
                val result = budget.measure { started ->
                    assertEquals(now, started)
                    now += 6_000_000_000L
                    "transcript $sample"
                }
                measured += result.elapsedMs
                assertTrue(result.value.startsWith("transcript"))
            }
        }
        assertEquals(21, measured.size)
        assertEquals(126_000.0, measured.sum(), 0.0)
        assertTrue(measured.all { it == 6_000.0 })
    }

    @Test fun lateNativeOutputIsDisqualifiedEvenIfTheTimerThreadDidNotRun() = runBlocking {
        var now = 0L
        val budget = BenchmarkSampleBudget(20) { now }
        var recorded = false
        try {
            budget.measure {
                now += 20_001_000_000L
                "late transcript"
            }
            recorded = true
            fail("A late native return must never become a completed measurement")
        } catch (expected: BenchmarkSampleTimeoutException) {
            assertTrue(checkNotNull(expected.message).contains("one input exceeded 20 seconds"))
        }
        assertFalse(recorded)
    }

    @Test fun wholeStreamSharesOneBudgetAcrossCaptureFramesAndFinish() = runBlocking {
        var now = 0L
        val budget = BenchmarkSampleBudget(20) { now }
        try {
            budget.measure {
                repeat(400) { now += 49_000_000L } // 19.6s of accept() calls.
                now += 500_000_000L // finish() makes this single input exceed 20s.
                "late final"
            }
            fail("An utterance final belongs to the same input budget")
        } catch (_: BenchmarkSampleTimeoutException) { }
    }

    @Test fun eachInputHonorsTheChosenLowerCapAndAnOverrunStopsThePass() = runBlocking {
        var now = 0L
        val budget = BenchmarkSampleBudget(5) { now }
        var completed = 0
        try {
            listOf(4_999L, 5_000L, 1L).forEach { duration ->
                budget.measure { now += duration * 1_000_000L }
                completed++
            }
            fail("A sample at its limit must stop this model's pass")
        } catch (_: BenchmarkSampleTimeoutException) { }
        assertEquals(1, completed)
    }

    @Test fun aFailedNativeCallKeepsItsRealError() = runBlocking {
        val failure = IllegalStateException("model cannot allocate device memory")
        try {
            BenchmarkSampleBudget(20) { 0L }.measure<Nothing> { throw failure }
            fail("Expected native failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }
}

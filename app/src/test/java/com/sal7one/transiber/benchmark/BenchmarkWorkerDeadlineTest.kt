package com.sal7one.transiber.benchmark

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BenchmarkWorkerDeadlineTest {
    @Test fun loadingAndManySuccessfulInputsDoNotShareOneTwentySecondBudget() {
        var clock = 0L
        val deadline = BenchmarkWorkerDeadline(20, { clock }, 40)
        deadline.advance(BenchmarkWorkerPhase.LOAD)
        clock = 110_000
        repeat(18) {
            deadline.advance(BenchmarkWorkerPhase.INFERENCE)
            clock += 8_000
        }
        deadline.advance(BenchmarkWorkerPhase.CLEANUP)
        clock += 500
        deadline.advance(BenchmarkWorkerPhase.RESULT)
        deadline.finish()
        clock += 1_000_000
        assertNull(deadline.expired())
        assertEquals(Long.MAX_VALUE, deadline.remainingMillis())
    }

    @Test fun completeSampleExpiresAtTwentySecondsAndCannotRenewAsResetOrAnotherSample() {
        var clock = 0L
        val deadline = BenchmarkWorkerDeadline(20, { clock }, 30)
        deadline.advance(BenchmarkWorkerPhase.LOAD)
        deadline.advance(BenchmarkWorkerPhase.INFERENCE)
        clock = 20_000
        val expired = checkNotNull(deadline.expired())
        assertEquals(BenchmarkWorkerPhase.INFERENCE, expired.phase)
        assertEquals(20_000L, expired.elapsedMillis)
        for (next in listOf(BenchmarkWorkerPhase.INFERENCE, BenchmarkWorkerPhase.RESET, BenchmarkWorkerPhase.CLEANUP)) {
            val failure = runCatching { deadline.advance(next) }.exceptionOrNull()
            assertTrue(failure is BenchmarkWorkerTimeoutException)
            assertEquals(BenchmarkWorkerPhase.INFERENCE, (failure as BenchmarkWorkerTimeoutException).phase)
        }
    }

    @Test fun illegalTransitionsClockForgeryAndUnboundedMessagesCannotExtendWorker() {
        var clock = 0L
        val deadline = BenchmarkWorkerDeadline(20, { clock }, 4)
        assertTrue(runCatching { deadline.advance(BenchmarkWorkerPhase.INFERENCE) }.isFailure)
        // A rejected boundary must not replace the startup deadline.
        assertEquals(BenchmarkWorkerPhase.STARTUP, deadline.phase)
        assertTrue(runCatching { deadline.advance(BenchmarkWorkerPhase.LOAD, 60_000) }.isFailure)
        deadline.advance(BenchmarkWorkerPhase.LOAD)
        deadline.advance(BenchmarkWorkerPhase.INFERENCE)
        assertTrue(runCatching { deadline.advance(BenchmarkWorkerPhase.LOAD) }.isFailure)
        clock = 21_000
        assertEquals(BenchmarkWorkerPhase.INFERENCE, deadline.expired()!!.phase)
    }

    @Test fun phaseWatchdogStopsOneBlockedSampleAfterLongValidLoading() = runTest {
        val deadline = BenchmarkWorkerDeadline(20, { testScheduler.currentTime }, 30)
        val never = CompletableDeferred<String>()
        var released = false
        val worker = object : BenchmarkWorkerLifetime {
            override suspend fun terminateAndAwaitDeath() { released = true }
        }
        val outcome = async {
            runCatching { BenchmarkWorkerWatchdog.run(20, worker, elapsedMs = { 0 }, deadline = deadline) {
                deadline.advance(BenchmarkWorkerPhase.LOAD)
                kotlinx.coroutines.delay(100_000)
                deadline.advance(BenchmarkWorkerPhase.INFERENCE)
                never.await()
            } }.exceptionOrNull()
        }
        runCurrent(); advanceTimeBy(119_999); runCurrent()
        assertFalse(released)
        advanceTimeBy(1); runCurrent()
        val failure = outcome.await() as BenchmarkWorkerTimeoutException
        assertEquals(BenchmarkWorkerPhase.INFERENCE, failure.phase)
        assertEquals(20_000L, failure.elapsedMs)
        assertTrue(released)
    }
}

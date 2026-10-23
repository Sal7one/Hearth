package com.sal7one.transiber.benchmark

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Cancelling the result waiter leaves simulated native work active until its process is killed. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BenchmarkWorkerWatchdogTest {
    private class BlockedWorker : BenchmarkWorkerLifetime {
        val result = CompletableDeferred<String>()
        val death = CompletableDeferred<Unit>()
        var kills = 0
        var nativeRunning = true
        override suspend fun terminateAndAwaitDeath() {
            kills++
            nativeRunning = false // Process.killProcess, independently of the blocked native call.
            death.await() // Binder death is required before ownership is released.
        }
    }

    @Test fun twentySecondHardCeilingKillsBlockedNativeAndWaitsForDeathBeforeNextCandidate() = runTest {
        val worker = BlockedWorker()
        var nextCandidateStarted = false
        val attempt = async {
            val failure = runCatching {
                BenchmarkWorkerWatchdog.run(20, worker, elapsedMs = { testScheduler.currentTime }) { worker.result.await() }
            }.exceptionOrNull()
            nextCandidateStarted = true
            failure
        }
        runCurrent()
        advanceTimeBy(19_999); runCurrent()
        assertTrue(worker.nativeRunning)
        assertEquals(0, worker.kills)
        advanceTimeBy(1); runCurrent()
        assertFalse(worker.nativeRunning)
        assertEquals(1, worker.kills)
        assertFalse(nextCandidateStarted)
        worker.death.complete(Unit); runCurrent()
        val timeout = attempt.await() as BenchmarkWorkerTimeoutException
        assertEquals(20, timeout.timeoutSeconds)
        assertEquals(20_000L, timeout.elapsedMs)
        assertTrue(nextCandidateStarted)
    }

    @Test fun stopKillsWorkerAndStillWaitsForBinderDeath() = runTest {
        val worker = BlockedWorker()
        val attempt = launch { BenchmarkWorkerWatchdog.run(20, worker, elapsedMs = { 100 }) { worker.result.await() } }
        runCurrent()
        attempt.cancel(); runCurrent()
        assertEquals(1, worker.kills)
        assertFalse(worker.nativeRunning)
        assertFalse(attempt.isCompleted)
        worker.death.complete(Unit)
        attempt.cancelAndJoin()
        assertTrue(attempt.isCompleted)
    }

    @Test fun successfulResultAlsoReapsResidentModelBeforeItCanReturn() = runTest {
        val worker = BlockedWorker()
        worker.result.complete("measured")
        val attempt = async { BenchmarkWorkerWatchdog.run(10, worker, elapsedMs = { 123 }) { worker.result.await() } }
        runCurrent()
        assertEquals(1, worker.kills)
        assertFalse(attempt.isCompleted)
        worker.death.complete(Unit)
        assertEquals("measured", attempt.await())
    }

    @Test fun unconfirmedTerminationIsFatalRatherThanPermittingAnotherNativeModel() = runTest {
        val worker = object : BenchmarkWorkerLifetime {
            override suspend fun terminateAndAwaitDeath() { throw BenchmarkWorkerReapException("death not confirmed") }
        }
        val failure = runCatching {
            BenchmarkWorkerWatchdog.run(5, worker, elapsedMs = { 1 }) { "result" }
        }.exceptionOrNull()
        assertTrue(failure is BenchmarkWorkerReapException)
    }

    @Test fun onlyRemainingBudgetIsAvailableAfterPrivateInputPreparation() = runTest {
        val worker = BlockedWorker().apply { death.complete(Unit) }
        val attempt = async {
            runCatching {
                BenchmarkWorkerWatchdog.run(10, worker, remainingMs = 1_000, elapsedMs = { 10_000 }) { worker.result.await() }
            }.exceptionOrNull()
        }
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertTrue(attempt.await() is BenchmarkWorkerTimeoutException)
        assertEquals(1, worker.kills)
    }

    @Test fun noCallerCanRaiseTheHardCeilingAboveTwentySeconds() = runTest {
        val worker = BlockedWorker().apply { death.complete(Unit) }
        for (invalid in listOf(0, 4, 21, 45, 3600)) {
            assertTrue(runCatching {
                BenchmarkWorkerWatchdog.run(invalid, worker, elapsedMs = { 0 }) { "must not run" }
            }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0, worker.kills)
    }
}

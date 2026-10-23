package com.sal7one.transiber.benchmark

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sal7one.transiber.runtime.LocalWorkGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The real controller owns the lease and recovery state; fake IPC never opens native models. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BenchmarkRunControllerRecoveryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val previousFactory = BenchmarkRunController.workerFactory
    private lateinit var worker: FakeWorker
    private val candidate = BenchmarkCandidate("recovery-fixture-${UUID.randomUUID()}", "Fixture", "Speech",
        setOf("en", "ru"), acceleration = "cpu", artifactFingerprint = "a".repeat(64))
    private val clip = BenchmarkAudio.fromPcm16(ShortArray(16_000) { (it % 100).toShort() })

    @Before fun installWorker() {
        assertFalse(BenchmarkRunController.state.value.running)
        assertFalse(BenchmarkRunController.state.value.recovering)
        assertNull(LocalWorkGate.owner.value)
        worker = FakeWorker()
        BenchmarkRunController.workerFactory = { worker }
    }

    @After fun restoreWorkerAndLease() = runBlocking {
        try {
            if (::worker.isInitialized) {
                worker.confirmed.set(true)
                worker.pendingConfirmation?.complete(true)
            }
            BenchmarkRunController.stop()
            BenchmarkRunController.recover()
            withTimeout(5_000) { BenchmarkRunController.state.first { !it.running && !it.recovering } }
            assertNull("A failed test must not poison the next test's local work gate", LocalWorkGate.owner.value)
        } finally {
            BenchmarkRunController.workerFactory = previousFactory
        }
    }

    @Test fun unconfirmedCleanupKeepsOwnershipAndAnExplicitSuccessfulRetryRestoresRun() = runBlocking {
        worker.runFailure = BenchmarkWorkerReapException("Worker termination could not be confirmed")
        start(listOf(batch()))
        val quarantined = awaitUnconfirmedRecovery()
        assertEquals(1, worker.recoveryCalls.get()) // One automatic confirmation attempt.
        assertEquals("Benchmark", LocalWorkGate.owner.value)
        assertEquals(1, quarantined.failedRows)
        assertTrue(quarantined.error.orEmpty().contains("termination could not be confirmed"))
        val rejected = runCatching { BenchmarkRunController.start(context, listOf(batch())) }.exceptionOrNull()
        assertTrue(rejected is IllegalStateException)
        assertTrue(rejected!!.message.orEmpty().contains("Retry cleanup"))
        assertEquals(1, worker.requests.size)

        worker.confirmed.set(true)
        BenchmarkRunController.recover()
        awaitState { !it.running && !it.recovering }
        assertEquals(2, worker.recoveryCalls.get())
        assertNull(LocalWorkGate.owner.value)
        assertNull(BenchmarkRunController.state.value.error)

        // A new user action may start a comparison after actual ownership confirmation.
        BenchmarkRunController.start(context, listOf(batch()))
        assertTrue(BenchmarkRunController.state.value.running)
    }

    @Test fun concurrentRecoveryRequestsCannotReleaseOrRetryAnUnconfirmedWorkerTwice() = runBlocking {
        worker.runFailure = BenchmarkWorkerReapException("Worker is still shutting down")
        start(listOf(batch()))
        awaitUnconfirmedRecovery()
        worker.pendingConfirmation = CompletableDeferred()
        worker.confirmationEntered = CompletableDeferred()
        coroutineScope { repeat(20) { launch(Dispatchers.Default) { BenchmarkRunController.recover() } } }
        withTimeout(5_000) { worker.confirmationEntered!!.await() }
        assertEquals(2, worker.recoveryCalls.get())
        assertTrue(BenchmarkRunController.state.value.recovering)
        assertTrue(BenchmarkRunController.state.value.stopping)
        assertEquals("Benchmark", LocalWorkGate.owner.value)

        worker.pendingConfirmation!!.complete(true)
        awaitState { !it.running && !it.recovering }
        assertEquals(2, worker.recoveryCalls.get())
        assertNull(LocalWorkGate.owner.value)
    }

    @Test fun aReturnedDeadlineSkipsTheArtifactOnLaterLanguagesButKeepsOtherBackendsEligible() = runBlocking {
        worker.result = { request -> result(request, deadline = request.candidate.acceleration == "cpu") }
        val gpu = candidate.copy(acceleration = "vulkan")
        val id = start(listOf(batch(models = listOf(candidate, gpu)), batch("ru", listOf(candidate, gpu))))
        val completed = awaitState { !it.running && !it.recovering && it.runId == id }
        assertEquals(listOf("cpu/en", "vulkan/en", "vulkan/ru"),
            worker.requests.map { "${it.candidate.acceleration}/${it.source}" })
        assertEquals(3, completed.completedRows)
        assertEquals(1, completed.failedRows)
        assertNull(LocalWorkGate.owner.value)
        val rows = BenchmarkResults(context).load().filter { it.runId == id }
        assertEquals(3, rows.size)
        val failed = rows.single { it.terminationReason == "deadline" }
        assertTrue(failed.computeMs.isEmpty())
        assertTrue(failed.texts.isEmpty())
        assertNull(failed.wordErrorRate)
    }

    @Test fun preparationTimeoutRetainsItsActualPhaseAndBudgetInsteadOfBlamingSampleInference() = runBlocking {
        worker.runFailure = BenchmarkWorkerTimeoutException(20, 120_001, BenchmarkWorkerPhase.LOAD)
        val id = start(listOf(batch()))
        awaitState { !it.running && !it.recovering && it.runId == id }
        val failed = BenchmarkResults(context).load().single { it.runId == id }
        assertTrue(failed.error.orEmpty().contains("load"))
        assertTrue(failed.error.orEmpty().contains("120s"))
        assertFalse(failed.error.orEmpty().contains("one input exceeded"))
        assertEquals("deadline", failed.terminationReason)
        assertTrue(failed.computeMs.isEmpty())
    }

    private fun batch(source: String = "en", models: List<BenchmarkCandidate> = listOf(candidate)) =
        BenchmarkBatch(models, source, "", clip = clip)

    private fun start(batches: List<BenchmarkBatch>): String {
        BenchmarkRunController.start(context, batches)
        return BenchmarkRunController.state.value.runId!!.also { assertTrue(BenchmarkRunController.execute(it)) }
    }

    private suspend fun awaitState(predicate: (BenchmarkRunState) -> Boolean): BenchmarkRunState =
        withTimeout(5_000) { BenchmarkRunController.state.first(predicate) }

    private suspend fun awaitUnconfirmedRecovery(): BenchmarkRunState = awaitState {
        // The controller publishes quarantine before dispatching its automatic recovery.
        // Observe that recovery's final state, not the earlier idle-looking transition.
        !it.running && it.recovering && !it.stopping && worker.recoveryCalls.get() > 0 &&
            it.status.startsWith("Worker cleanup is not confirmed")
    }

    private fun result(request: BenchmarkWorkerProtocol.Request, deadline: Boolean) = BenchmarkResult(
        runId = "worker-fixture", timestamp = System.currentTimeMillis(), model = request.candidate.label,
        identity = BenchmarkAccelerationPlan.identity(request.candidate), route = request.candidate.route,
        inputHash = "fixture", source = request.source, target = request.target, audioMs = 1_000,
        loadMs = 2.0, computeMs = listOf(30.0, 10.0, 10.0), texts = List(3) { "sample text" },
        runtime = "Fake worker after confirmed native cleanup", firstTextMs = listOf(20.0, 5.0, 5.0),
        device = "Host fixture", error = if (deadline) "Disqualified: sample exceeded its inference limit" else null,
        wordErrorRate = 0.0, terminationReason = if (deadline) "deadline" else null,
        protocolVersion = BenchmarkAttemptPolicy.PROTOCOL_VERSION,
    )

    private class FakeWorker : BenchmarkWorkerSession {
        val requests = CopyOnWriteArrayList<BenchmarkWorkerProtocol.Request>()
        val recoveryCalls = AtomicInteger()
        val confirmed = AtomicBoolean(false)
        var runFailure: Exception? = null
        var result: ((BenchmarkWorkerProtocol.Request) -> BenchmarkResult)? = null
        @Volatile var pendingConfirmation: CompletableDeferred<Boolean>? = null
        @Volatile var confirmationEntered: CompletableDeferred<Unit>? = null
        override suspend fun run(request: BenchmarkWorkerProtocol.Request, progress: (String) -> Unit): BenchmarkResult {
            requests += request
            runFailure?.let { throw it }
            return checkNotNull(result) { "Choose an explicit fake result before running" }(request)
        }
        override fun cancel() = Unit
        override suspend fun recover(): Boolean {
            recoveryCalls.incrementAndGet()
            confirmationEntered?.complete(Unit)
            return pendingConfirmation?.await() ?: confirmed.get()
        }
    }
}

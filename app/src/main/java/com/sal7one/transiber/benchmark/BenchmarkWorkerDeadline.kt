package com.sal7one.transiber.benchmark

/** Only explicit native operation boundaries renew a deadline; status messages never do. */
internal enum class BenchmarkWorkerPhase {
    STARTUP, LOAD, INFERENCE, RESET, CLEANUP, RESULT;

    fun budgetMillis(inferenceSeconds: Int): Long = when (this) {
        STARTUP -> 30_000L
        LOAD -> 120_000L
        INFERENCE -> BenchmarkAttemptPolicy.seconds(inferenceSeconds) * 1_000L
        RESET, CLEANUP, RESULT -> 10_000L
    }
}

/** Shared by the parent and worker. A wedged operation cannot renew its own timer. */
internal class BenchmarkWorkerDeadline(
    private val inferenceSeconds: Int,
    private val now: () -> Long,
    private val maximumBoundaries: Int,
) {
    private var boundaries = 0
    private var terminal = false
    @Volatile var phase: BenchmarkWorkerPhase = BenchmarkWorkerPhase.STARTUP
        private set
    @Volatile var operationStartedMillis: Long = now()
        private set
    @Volatile var deadlineMillis: Long = operationStartedMillis + phase.budgetMillis(inferenceSeconds)
        private set

    data class Expiry(val phase: BenchmarkWorkerPhase, val elapsedMillis: Long)

    @Synchronized fun advance(next: BenchmarkWorkerPhase, startedMillis: Long = now()): Long {
        check(!terminal) { "Benchmark worker already completed" }
        if (startedMillis >= deadlineMillis) throw BenchmarkWorkerTimeoutException(inferenceSeconds,
            operationElapsedMillis(), phase)
        require(startedMillis <= now() + 50 && startedMillis >= operationStartedMillis) { "Invalid benchmark operation clock" }
        check(++boundaries <= maximumBoundaries) { "Benchmark worker exceeded its bounded operation count" }
        val allowed = when (phase) {
            BenchmarkWorkerPhase.STARTUP -> setOf(BenchmarkWorkerPhase.LOAD)
            BenchmarkWorkerPhase.LOAD -> setOf(BenchmarkWorkerPhase.RESET, BenchmarkWorkerPhase.INFERENCE, BenchmarkWorkerPhase.CLEANUP)
            BenchmarkWorkerPhase.INFERENCE -> setOf(BenchmarkWorkerPhase.RESET, BenchmarkWorkerPhase.INFERENCE, BenchmarkWorkerPhase.CLEANUP)
            BenchmarkWorkerPhase.RESET -> setOf(BenchmarkWorkerPhase.INFERENCE, BenchmarkWorkerPhase.CLEANUP)
            BenchmarkWorkerPhase.CLEANUP -> setOf(BenchmarkWorkerPhase.RESULT)
            BenchmarkWorkerPhase.RESULT -> emptySet()
        }
        check(next in allowed) { "Invalid benchmark operation transition: $phase → $next" }
        phase = next
        operationStartedMillis = startedMillis
        deadlineMillis = operationStartedMillis + next.budgetMillis(inferenceSeconds)
        return deadlineMillis
    }

    @Synchronized fun finish() { terminal = true }
    @Synchronized fun remainingMillis(): Long = if (terminal) Long.MAX_VALUE else deadlineMillis - now()
    @Synchronized fun operationElapsedMillis(): Long = now() - operationStartedMillis
    @Synchronized fun expired(): Expiry? {
        val time = now()
        return if (!terminal && time >= deadlineMillis) Expiry(phase, time - operationStartedMillis) else null
    }

    companion object {
        // Every input can reset and infer, plus bounded setup/cleanup/result transitions.
        fun maximumBoundaries(samples: Int, passes: Int): Int = samples.coerceIn(1, 32) * passes.coerceIn(3, 10) * 3 + 16
    }
}

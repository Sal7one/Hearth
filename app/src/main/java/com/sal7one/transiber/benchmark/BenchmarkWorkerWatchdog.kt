package com.sal7one.transiber.benchmark

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal open class BenchmarkWorkerException(message: String, cause: Throwable? = null) : Exception(message, cause)
internal class BenchmarkWorkerTimeoutException(
    val timeoutSeconds: Int, val elapsedMs: Long, val phase: BenchmarkWorkerPhase = BenchmarkWorkerPhase.INFERENCE,
) : BenchmarkWorkerException(if (phase == BenchmarkWorkerPhase.INFERENCE)
    "Disqualified: one input exceeded the ${timeoutSeconds}s inference limit; model worker stopped"
else "Disqualified: ${phase.name.lowercase()} exceeded its ${phase.budgetMillis(timeoutSeconds) / 1000}s safety limit; model worker stopped")
internal class BenchmarkWorkerReapException(message: String, cause: Throwable? = null) : BenchmarkWorkerException(message, cause)

/** Release requires acknowledged native cleanup, or confirmed process death for hung work. */
internal interface BenchmarkWorkerLifetime {
    suspend fun terminateAndAwaitDeath()
}

internal object BenchmarkWorkerWatchdog {
    suspend fun <T : Any> run(
        timeoutSeconds: Int, worker: BenchmarkWorkerLifetime, remainingMs: Long = timeoutSeconds * 1000L,
        elapsedMs: () -> Long, deadline: BenchmarkWorkerDeadline? = null, execute: suspend () -> T,
    ): T {
        require(timeoutSeconds in 5..20) { "Benchmark hard limit must be between 5 and 20 seconds" }
        try {
            return if (deadline == null) {
                withTimeoutOrNull(remainingMs.coerceAtLeast(1)) { execute() }
                    ?: throw BenchmarkWorkerTimeoutException(timeoutSeconds, elapsedMs())
            } else coroutineScope {
                val result = async { execute() }
                val expiry = async<Nothing> {
                    while (true) {
                        deadline.expired()?.let { throw BenchmarkWorkerTimeoutException(timeoutSeconds, it.elapsedMillis, it.phase) }
                        val remaining = deadline.remainingMillis()
                        delay(remaining.coerceIn(1, 100))
                    }
                    @Suppress("UNREACHABLE_CODE") error("Unreachable deadline")
                }
                try { select<T> { result.onAwait { it }; expiry.onAwait { it } } }
                finally { expiry.cancel(); result.cancel() }
            }
        } finally {
            // A completed result acknowledges closed native handles. A timeout/cancellation
            // requires process death before another candidate may own native work.
            withContext(NonCancellable) { worker.terminateAndAwaitDeath() }
        }
    }
}

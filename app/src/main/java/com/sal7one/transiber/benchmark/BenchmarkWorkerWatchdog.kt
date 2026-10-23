package com.sal7one.transiber.benchmark

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal open class BenchmarkWorkerException(message: String, cause: Throwable? = null) : Exception(message, cause)
internal class BenchmarkWorkerTimeoutException(val timeoutSeconds: Int, val elapsedMs: Long) :
    BenchmarkWorkerException("Disqualified: exceeded the ${timeoutSeconds}s hard limit; isolated model process terminated")
internal class BenchmarkWorkerReapException(message: String, cause: Throwable? = null) : BenchmarkWorkerException(message, cause)

/** Reaping is part of the ownership contract, including when native cancellation is ignored. */
internal interface BenchmarkWorkerLifetime {
    suspend fun terminateAndAwaitDeath()
}

internal object BenchmarkWorkerWatchdog {
    suspend fun <T : Any> run(
        timeoutSeconds: Int, worker: BenchmarkWorkerLifetime, remainingMs: Long = timeoutSeconds * 1000L,
        elapsedMs: () -> Long, execute: suspend () -> T,
    ): T {
        require(timeoutSeconds in 5..20) { "Benchmark hard limit must be between 5 and 20 seconds" }
        try {
            return withTimeoutOrNull(remainingMs.coerceAtLeast(1)) { execute() }
                ?: throw BenchmarkWorkerTimeoutException(timeoutSeconds, elapsedMs())
        } finally {
            // Do not start another candidate, even after a successful result, until all native
            // allocations/threads in this disposable process have actually gone away.
            withContext(NonCancellable) { worker.terminateAndAwaitDeath() }
        }
    }
}

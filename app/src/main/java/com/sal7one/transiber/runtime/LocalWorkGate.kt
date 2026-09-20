package com.sal7one.transiber.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** One inference workload at a time; a lease outlives cancellation until native cleanup finishes. */
object LocalWorkGate {
    private val current = MutableStateFlow<String?>(null)
    val owner: StateFlow<String?> = current
    private var lease: Lease? = null
    @Synchronized fun acquire(owner: String): Lease {
        require(owner.isNotBlank())
        check(lease == null) { "${current.value} is still running or releasing its models. Stop it before starting $owner." }
        return Lease(owner).also { lease = it; current.value = owner }
    }
    /** Wait only after an explicit stop; callers must still acquire their own lease. */
    suspend fun awaitIdle(timeoutMillis: Long = 30_000) {
        withTimeout(timeoutMillis) { current.first { it == null } }
    }
    class Lease internal constructor(val owner: String) : AutoCloseable {
        override fun close() = release(this)
    }
    @Synchronized private fun release(value: Lease) {
        if (lease === value) { lease = null; current.value = null }
    }
}

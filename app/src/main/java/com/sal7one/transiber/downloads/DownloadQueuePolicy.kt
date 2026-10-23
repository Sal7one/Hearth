package com.sal7one.transiber.downloads

import android.content.Context
import com.sal7one.transiber.preferences.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** File concurrency and connections within one file are deliberately independent. */
internal data class DownloadQueuePolicy(
    val backend: String = "auto",
    val simultaneousFiles: Int = 2,
    val connectionsPerFile: Int = 4,
    val bandwidthKiB: Int = 0,
    val retries: Int = 3,
    val unmeteredOnly: Boolean = false,
) {
    init {
        require(backend in setOf("auto", "http", "aria2"))
        require(simultaneousFiles in 1..8 && connectionsPerFile in 1..16)
        require(bandwidthKiB in 0..1_048_576 && retries in 0..10)
    }
    companion object {
        val BALANCED = DownloadQueuePolicy()
        val FAST = DownloadQueuePolicy(simultaneousFiles = 4, connectionsPerFile = 8)
    }
}

internal class DownloadQueuePreferences(context: Context) {
    private val preferences = UserPreferences.store(context, "download-queue")
    fun read() = DownloadQueuePolicy(
        backend = preferences.getString("backend", "auto").orEmpty().takeIf { it in setOf("auto", "http", "aria2") } ?: "auto",
        simultaneousFiles = preferences.getInt("simultaneous-files", 2).coerceIn(1, 8),
        connectionsPerFile = preferences.getInt("connections-per-file", 4).coerceIn(1, 16),
        bandwidthKiB = preferences.getInt("bandwidth-kib", 0).coerceIn(0, 1_048_576),
        retries = preferences.getInt("retries", 3).coerceIn(0, 10),
        unmeteredOnly = preferences.getBoolean("unmetered-only", false),
    )
    fun save(policy: DownloadQueuePolicy) {
        check(preferences.edit().putString("backend", policy.backend)
            .putInt("simultaneous-files", policy.simultaneousFiles)
            .putInt("connections-per-file", policy.connectionsPerFile)
            .putInt("bandwidth-kib", policy.bandwidthKiB)
            .putInt("retries", policy.retries)
            .putBoolean("unmetered-only", policy.unmeteredOnly).commit()) { "Cannot save download settings" }
    }
}

/** Event-driven capacity: refresh after a changed limit; lowering it never aborts running work. */
internal class DownloadWorkSlots(private val limit: () -> Int) {
    private val mutex = Mutex()
    private var active = 0
    private val changes = MutableStateFlow(0L)
    fun refresh() { changes.update { it + 1 } }
    suspend fun <T> withSlot(block: suspend () -> T): T {
        while (true) {
            val observed = changes.value
            val acquired = mutex.withLock {
                if (active < limit().coerceAtLeast(1)) { active++; true } else false
            }
            if (acquired) break
            changes.first { it != observed }
        }
        return try { block() } finally {
            // A cancelled transfer must release its slot before another queued task starts.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { mutex.withLock { active--; refresh() } }
        }
    }
}

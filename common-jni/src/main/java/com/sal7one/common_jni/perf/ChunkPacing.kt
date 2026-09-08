package com.sal7one.common_jni.perf

/**
 * Pure-JVM mirror of [MicRecorder]'s chunk-pacing bookkeeping (see
 * `MicRecorder.recordChunkPacing`). It aggregates delivery jitter — the
 * wall-clock interval between delivered chunks minus the configured chunk
 * duration — into a [RollingStats], and recomputes the human-readable summary
 * every [logEvery] chunks.
 *
 * [MicRecorder] itself lives in the `audio` package and requires the Android
 * `AudioRecord` runtime, so the identical arithmetic is expressed here as a
 * dependency-free helper that the host JVM benchmark can exercise directly.
 * This is a measurement-only type: it never alters capture or delivery; the
 * original recorder keeps its own private copy unchanged.
 *
 * @param chunkDurationMs nominal wall duration of one chunk (MicRecorder default 100).
 * @param stats rolling window receiving the per-delivery jitter samples.
 * @param logEvery refresh cadence of [lastSummary] (MicRecorder logs every 50).
 */
class ChunkPacing(
    private val chunkDurationMs: Double,
    private val stats: RollingStats = RollingStats(capacity = 512),
    private val logEvery: Long = 50L,
) {
    private var lastDeliveryNanos = 0L
    private var delivered = 0L

    /** Cumulative number of chunks delivered through this helper. */
    val deliveredCount: Long get() = delivered

    /** Rolling window of per-delivery jitter samples (`intervalMs - chunkDurationMs`). */
    val jitterStats: RollingStats get() = stats

    /** Most recent periodic summary (mirrors the recorder's debug log line). */
    var lastSummary: String = ""
        private set

    /**
     * Record the delivery of one chunk at [nowNanos] (e.g. `System.nanoTime()`).
     * The first delivery only establishes the baseline — jitter is recorded from
     * the second delivery onward, exactly as [MicRecorder] does.
     */
    fun onChunkDelivered(nowNanos: Long) {
        if (lastDeliveryNanos != 0L) {
            val intervalMs = (nowNanos - lastDeliveryNanos) / 1_000_000.0
            stats.record(intervalMs - chunkDurationMs)
        }
        lastDeliveryNanos = nowNanos
        delivered++
        if (delivered % logEvery == 0L) {
            lastSummary = stats.format()
        }
    }

    fun reset() {
        lastDeliveryNanos = 0L
        delivered = 0L
        lastSummary = ""
        stats.reset()
    }
}

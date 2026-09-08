package com.sal7one.common_jni.perf

import kotlin.math.ceil

/**
 * Dependency-free timing and aggregation helpers for Kotlin-side performance
 * instrumentation. Mirrors the native `scoped_timer.h` / `PerformanceStats`
 * style: timings come from `System.nanoTime()` and aggregation is pure, so
 * instrumentation never changes runtime behavior. Used by the realtime audio /
 * STT paths to report latency without altering results.
 */
object PerfMetrics {
    @PublishedApi
    internal const val NS_PER_MS = 1_000_000.0

    /** Elapsed nanoseconds around [block]. */
    inline fun measureNanos(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    /** Elapsed milliseconds around [block] (derived from [measureNanos]). */
    inline fun measure(block: () -> Unit): Double = measureNanos(block) / NS_PER_MS

    /** Measures [block] and returns both its result and elapsed milliseconds. */
    inline fun <T> measureValue(block: () -> T): TimedValue<T> {
        val start = System.nanoTime()
        val value = block()
        return TimedValue(value, (System.nanoTime() - start) / NS_PER_MS)
    }

    /** Human-readable nanosecond duration, e.g. `"1.234 ms"`. */
    fun formatNanos(nanos: Long): String = "${round3(nanos / NS_PER_MS)} ms"
}

/** The result of [PerfMetrics.measureValue]: a value plus its elapsed ms. */
data class TimedValue<T>(val value: T, val elapsedMs: Double)

/**
 * Rolling window of the most recent [capacity] samples with running count,
 * mean, 95th percentile (nearest-rank), max and min.
 *
 * - [count] is cumulative over the stats object's lifetime (not capped).
 * - [avg], [p95], [max] and [min] are computed over the retained window so
 *   memory stays bounded at [capacity] doubles.
 */
class RollingStats(private val capacity: Int = DEFAULT_CAPACITY) {
    companion object {
        const val DEFAULT_CAPACITY = 1024
    }

    init {
        require(capacity > 0) { "capacity must be > 0" }
    }

    private val ring = DoubleArray(capacity)
    private var head = 0
    private var retained = 0
    private var total = 0L
    private var sum = 0.0

    /** Cumulative number of samples recorded (not capped by [capacity]). */
    val count: Long get() = total

    /** Number of samples currently retained in the rolling window. */
    val windowCount: Int get() = retained

    fun record(value: Double) {
        if (retained < capacity) {
            ring[retained] = value
            retained++
        } else {
            val evicted = ring[head]
            ring[head] = value
            head = (head + 1) % capacity
            sum -= evicted
        }
        sum += value
        total++
    }

    fun avg(): Double = if (retained == 0) 0.0 else sum / retained

    /** 95th percentile (nearest-rank) over the retained window. */
    fun p95(): Double {
        if (retained == 0) return 0.0
        val sorted = retainedSorted()
        val index = ceil(0.95 * sorted.size).toInt() - 1
        return sorted[index.coerceIn(0, sorted.size - 1)]
    }

    fun max(): Double {
        if (retained == 0) return 0.0
        var result = Double.NEGATIVE_INFINITY
        forEachRetained { if (it > result) result = it }
        return result
    }

    fun min(): Double {
        if (retained == 0) return 0.0
        var result = Double.POSITIVE_INFINITY
        forEachRetained { if (it < result) result = it }
        return result
    }

    fun reset() {
        head = 0
        retained = 0
        total = 0
        sum = 0.0
    }

    /** Single-line human-readable summary, e.g. `"count=50 avg=1.234 p95=2.1 max=8.0"`. */
    fun format(): String =
        "count=$count avg=${round3(avg())} p95=${round3(p95())} max=${round3(max())}"

    private fun retainedSorted(): DoubleArray {
        val result = DoubleArray(retained)
        for (i in 0 until retained) result[i] = ring[(head + i) % capacity]
        result.sort()
        return result
    }

    private inline fun forEachRetained(action: (Double) -> Unit) {
        for (i in 0 until retained) action(ring[(head + i) % capacity])
    }
}

/** Rounds to 3 decimal places for concise summaries. */
private fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0

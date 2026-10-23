package com.sal7one.transiber.benchmark

/**
 * A wall-clock backstop for the process watchdog. A busy native call can return just as a
 * watchdog fires: even then its late output must not earn a completed speed/quality score.
 * Starting a fresh measurement for each input avoids charging the corpus or warm repeats
 * against a single live-caption deadline. This never substitutes for hard process isolation.
 */
internal class BenchmarkSampleBudget(
    timeoutSeconds: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val seconds = BenchmarkAttemptPolicy.seconds(timeoutSeconds)
    private val limitNanos = BenchmarkAttemptPolicy.timeoutMillis(seconds) * 1_000_000L

    internal data class Measurement<T>(val value: T, val elapsedMs: Double)

    suspend fun <T> measure(infer: suspend (startedNanos: Long) -> T): Measurement<T> {
        val started = nanoTime()
        val value = infer(started)
        val elapsed = nanoTime() - started
        if (elapsed >= limitNanos) throw BenchmarkSampleTimeoutException(seconds)
        return Measurement(value, elapsed.coerceAtLeast(0L) / 1e6)
    }
}

internal class BenchmarkSampleTimeoutException(seconds: Int) :
    Exception(BenchmarkAttemptPolicy.timeoutMessage(seconds))

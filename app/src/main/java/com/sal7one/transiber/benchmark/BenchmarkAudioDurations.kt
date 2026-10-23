package com.sal7one.transiber.benchmark

/** Durations come from the exact validated PCM sent to inference, never filename estimates. */
internal data class BenchmarkAudioDurations(val recordings: List<Long>, val silenceChecks: List<Long>) {
    init { require((recordings + silenceChecks).all { it > 0 }) }
    val shortestMs: Long get() = recordings.minOrNull() ?: 0
    val longestMs: Long get() = recordings.maxOrNull() ?: 0
    val totalMs: Long get() = recordings.sum() + silenceChecks.sum()
}

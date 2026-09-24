package com.sal7one.transiber.benchmark

/** Sorting never mixes benchmark cohorts; callers supply one comparable input/device set. */
internal enum class BenchmarkTableSort { WARM, LOAD, QUALITY, RECENT }

internal object BenchmarkTableModel {
    /** Speech audio seconds processed per second of accelerated replay. */
    fun audioSpeed(result: BenchmarkResult): Double? = warmMs(result)?.takeIf { it > 0 && result.audioMs > 0 }
        ?.let { result.audioMs / it }

    fun translationMsPerSentence(result: BenchmarkResult): Double? = warmMs(result)?.takeIf { result.target.isNotBlank() }
        ?.let { total -> result.samples.count { !it.silence }.takeIf { it > 0 }?.let { total / it } }

    fun warmMs(result: BenchmarkResult): Double? = result.computeMs.drop(1)
        .takeIf { it.isNotEmpty() && it.all { time -> time.isFinite() && time >= 0 } }
        ?.let(BenchmarkMetrics::median)

    fun qualityValue(result: BenchmarkResult): Double? = if (result.target.isNotBlank()) {
        result.translationChrf
    } else if (result.source == "zh" || result.wordErrorRate == null) {
        result.characterErrorRate
    } else {
        result.wordErrorRate
    }?.takeIf(Double::isFinite)

    /** Higher is better. Speech error rates are inverted only for ranking. */
    fun qualityScore(result: BenchmarkResult): Double? = qualityValue(result)?.let { value ->
        if (result.target.isNotBlank()) value else 1.0 - value
    }

    fun sorted(rows: List<BenchmarkResult>, sort: BenchmarkTableSort): List<BenchmarkResult> = when (sort) {
        BenchmarkTableSort.WARM -> rows.sortedWith(compareBy<BenchmarkResult> {
            warmMs(it) ?: Double.POSITIVE_INFINITY
        }.thenBy { it.model })
        BenchmarkTableSort.LOAD -> rows.sortedWith(compareBy<BenchmarkResult> {
            it.loadMs.takeIf { ms -> ms.isFinite() && ms >= 0 } ?: Double.POSITIVE_INFINITY
        }.thenBy { it.model })
        BenchmarkTableSort.QUALITY -> rows.sortedWith(compareByDescending<BenchmarkResult> {
            qualityScore(it) ?: Double.NEGATIVE_INFINITY
        }.thenBy { warmMs(it) ?: Double.POSITIVE_INFINITY })
        BenchmarkTableSort.RECENT -> rows.sortedByDescending { it.timestamp }
    }
}

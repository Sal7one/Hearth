package com.sal7one.transiber.benchmark

internal data class BenchmarkSpeedRow(val result: BenchmarkResult, val warmMs: Double)

/** Only compare runs with the same exact input and timing protocol. */
internal fun latestBenchmarkSpeed(rows: List<BenchmarkResult>, task: String): List<BenchmarkSpeedRow> {
    val completed = rows.asSequence().filter { it.task == task && BenchmarkTableModel.completed(it) }
        .mapNotNull { result -> BenchmarkTableModel.warmMs(result)?.let { BenchmarkSpeedRow(result, it) } }
        .sortedByDescending { it.result.timestamp }.toList()
    val latest = completed.firstOrNull()?.result ?: return emptyList()
    val cohort = BenchmarkTableModel.cohort(latest)
    return completed.filter { it.result.runId == latest.runId && BenchmarkTableModel.cohort(it.result) == cohort }
        .distinctBy { it.result.identity to it.result.actualAcceleration }
        .sortedBy { it.warmMs }
}

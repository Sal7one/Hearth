package com.sal7one.transiber.benchmark

import org.json.JSONArray
import org.json.JSONObject

internal enum class BenchmarkExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"), JSON("json", "application/json")
}

/** Stable, machine-readable comparison export; blanks mean unmeasured, not zero. */
internal object BenchmarkExport {
    fun render(rows: List<BenchmarkResult>, format: BenchmarkExportFormat): String = when (format) {
        BenchmarkExportFormat.JSON -> JSONObject()
            .put("schema", "hearth-benchmark-comparison-v1")
            .put("notes", "Accelerated replay/synthesis measurements, not live capture latency. Requested GPU placement is not proof of actual offload. Reference similarity is not a human listening score.")
            .put("results", JSONArray(rows.map { it.json() })).toString(2)
        BenchmarkExportFormat.CSV -> csv(rows)
    }

    private fun csv(rows: List<BenchmarkResult>): String {
        val headers = listOf("model", "task", "source", "target", "requested_backend", "actual_backend", "status",
            "warm_median_ms", "load_ms", "warm_first_output_ms", "audio_realtime_multiple", "translation_ms_per_sentence",
            "word_error_rate", "character_error_rate", "translation_chrf_0_to_1", "model_bytes", "audio_ms",
            "warm_pass_count", "scoring_normalization", "timing_protocol", "device", "runtime", "input_sha256",
            "model_identity", "configuration_sha256", "run_id", "timestamp_epoch_ms", "error", "last_output", "reference",
            "battery_percent_before", "battery_percent_after", "battery_celsius_before", "battery_celsius_after",
            "thermal_status_before", "thermal_status_after", "available_system_memory_bytes_before", "available_system_memory_bytes_after",
            "process_pss_bytes_before", "process_pss_bytes_after", "system_low_memory_before", "system_low_memory_after")
        val values = rows.map { row -> listOf(
            row.model, row.task, row.source, row.target, BenchmarkTableModel.requestedBackend(row), row.actualAcceleration,
            when { row.error != null -> "failed"; BenchmarkTableModel.completed(row) -> "completed"; else -> "incomplete_or_empty" },
            BenchmarkTableModel.warmMs(row), row.loadMs.takeIf { it.isFinite() && it >= 0 && row.error == null },
            BenchmarkTableModel.firstTextMs(row), BenchmarkTableModel.audioSpeed(row),
            BenchmarkTableModel.translationMsPerSentence(row), row.wordErrorRate, row.characterErrorRate,
            row.translationChrf, row.modelBytes, row.audioMs, (row.computeMs.size - 1).coerceAtLeast(0),
            row.scoringNormalization, row.protocolVersion, row.device, row.runtime, row.inputHash, row.identity,
            row.configurationHash, row.runId, row.timestamp, row.error, row.texts.lastOrNull(), row.referenceText,
            row.environment?.before?.batteryPercent, row.environment?.after?.batteryPercent,
            row.environment?.before?.batteryCelsius, row.environment?.after?.batteryCelsius,
            row.environment?.before?.thermalStatus, row.environment?.after?.thermalStatus,
            row.environment?.before?.availableSystemMemoryBytes, row.environment?.after?.availableSystemMemoryBytes,
            row.environment?.before?.processPssBytes, row.environment?.after?.processPssBytes,
            row.environment?.before?.systemLowMemory, row.environment?.after?.systemLowMemory,
        ) }
        return (listOf(headers) + values).joinToString("\r\n", postfix = "\r\n") { row ->
            row.joinToString(",") { cell -> csvCell(cell) }
        }
    }

    /** RFC 4180 quoting plus spreadsheet formula-injection protection for user/model text. */
    private fun csvCell(value: Any?): String {
        val raw = when (value) { null -> ""; is Double -> value.takeIf(Double::isFinite)?.toString().orEmpty(); else -> value.toString() }
        val safe = if (value is String && raw.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$raw" else raw
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}

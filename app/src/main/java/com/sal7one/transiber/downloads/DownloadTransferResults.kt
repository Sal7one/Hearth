package com.sal7one.transiber.downloads

/** Observed network work only; verification and atomic installation are excluded. */
internal data class DownloadTransferResult(
    val title: String, val modelId: String, val backend: String, val connections: Int,
    val transferredBytes: Long, val elapsedMillis: Long, val retries: Int, val state: String,
) {
    val bytesPerSecond: Double get() = if (elapsedMillis > 0) transferredBytes * 1000.0 / elapsedMillis else 0.0
}

internal fun transferResults(records: List<FileDownload>): List<DownloadTransferResult> = records
    .filter { it.transferMillis > 0 && it.backend.isNotBlank() }
    .map { DownloadTransferResult(it.title, it.modelId, it.backend, it.connections, it.transferredBytes, it.transferMillis, it.retryCount, it.phase) }
    .sortedByDescending { it.bytesPerSecond }

internal fun transferResultsCsv(results: List<DownloadTransferResult>): String {
    fun cell(value: String): String {
        // Text fields remain text when opened in a spreadsheet.
        val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    return buildString {
        appendLine("title,model,backend,connections,transferred_bytes,network_ms,bytes_per_second,retries,state")
        results.forEach { row ->
            appendLine(listOf(cell(row.title), cell(row.modelId), cell(row.backend), row.connections,
                row.transferredBytes, row.elapsedMillis, java.lang.String.format(java.util.Locale.ROOT, "%.2f", row.bytesPerSecond), row.retries, cell(row.state)).joinToString(","))
        }
    }
}

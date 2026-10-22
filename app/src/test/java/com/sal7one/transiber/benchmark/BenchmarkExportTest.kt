package com.sal7one.transiber.benchmark

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BenchmarkExportTest {
    @Test fun jsonPreservesFailuresAndUnconfirmedPlacement() {
        val row = result().copy(error = "GPU driver failed", requestedAcceleration = "vulkan")
        val json = JSONObject(BenchmarkExport.render(listOf(row), BenchmarkExportFormat.JSON))
        assertEquals("hearth-benchmark-comparison-v1", json.getString("schema"))
        val restored = BenchmarkResult.from(json.getJSONArray("results").getJSONObject(0))
        assertEquals(row, restored)
        assertNull(restored.actualAcceleration)
    }

    @Test fun csvQuotesMultilineOutputAndProtectsSpreadsheetCells() {
        val csv = BenchmarkExport.render(listOf(result().copy(model = "=HYPERLINK(\"bad\")", texts = listOf("a,b\n\"quoted\""))), BenchmarkExportFormat.CSV)
        assertTrue(csv.startsWith("\"model\",\"task\""))
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"bad\"\")\""))
        assertTrue(csv.contains("\"a,b\n\"\"quoted\"\"\""))
        assertTrue(csv.contains("\"actual_backend\""))
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test fun voiceExportsMeasuredSpeedWithoutAnInventedQualityScore() {
        val voice = result().copy(task = "voice", audioMs = 2000, wordErrorRate = null,
            modelBytes = 42, actualAcceleration = "cpu", audioMsByPass = listOf(2000, 2000, 2000))
        val csv = BenchmarkExport.render(listOf(voice), BenchmarkExportFormat.CSV)
        assertTrue(csv.contains("\"voice\""))
        assertTrue(csv.contains("\"20.0\""))
        assertNull(BenchmarkTableModel.qualityValue(voice))
    }

    @Test fun exportsEnvironmentAsOptionalSamplesWithoutInventingPeakMemory() {
        val row = result().copy(environment = BenchmarkEnvironment(
            BenchmarkEnvironmentSnapshot(batteryPercent = 80, batteryCelsius = 30.5, processPssBytes = 1048576),
            BenchmarkEnvironmentSnapshot(batteryPercent = 79, batteryCelsius = 31.0, thermalStatus = 2)))
        val restored = BenchmarkResult.from(JSONObject(BenchmarkExport.render(listOf(row), BenchmarkExportFormat.JSON))
            .getJSONArray("results").getJSONObject(0))
        assertEquals(row.environment, restored.environment)
        val csv = BenchmarkExport.render(listOf(row), BenchmarkExportFormat.CSV)
        assertTrue(csv.contains("\"process_pss_bytes_before\""))
        assertTrue(csv.contains("\"1048576\""))
        assertFalse(csv.contains("peak_memory"))
    }

    private fun result() = BenchmarkResult(
        runId = "run", timestamp = 1, model = "model", identity = "id", route = "speech", inputHash = "same",
        source = "en", target = "", audioMs = 1000, loadMs = 10.0, computeMs = listOf(100.0, 100.0, 100.0),
        texts = listOf("spoken"), runtime = "runtime", firstTextMs = listOf(100.0, 50.0, 60.0), device = "phone", protocolVersion = 2,
    )
}

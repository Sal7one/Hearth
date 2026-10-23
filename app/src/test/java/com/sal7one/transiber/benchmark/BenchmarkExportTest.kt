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

    @Test fun exportsScoreAndItsDefinitionWithoutReplacingTheRawReferenceMetric() {
        val scored = result().copy(wordErrorRate = .25, referenceText = "spoken")
        val exported = JSONObject(BenchmarkExport.render(listOf(scored), BenchmarkExportFormat.JSON))
        val row = exported.getJSONArray("results").getJSONObject(0)
        assertEquals(75.0, row.getDouble("referenceScore100"), .0)
        assertEquals(.25, row.getDouble("wordErrorRate"), .0)
        assertTrue(exported.getString("reference_score_notes").contains("speed is separate"))
        assertEquals(scored, BenchmarkResult.from(row))
        val failed = JSONObject(BenchmarkExport.render(listOf(scored.copy(error = "inference failed")), BenchmarkExportFormat.JSON))
            .getJSONArray("results").getJSONObject(0)
        assertTrue(failed.isNull("referenceScore100"))
        val csv = BenchmarkExport.render(listOf(scored), BenchmarkExportFormat.CSV)
        assertTrue(csv.contains("\"reference_score_0_to_100\""))
        assertTrue(csv.contains("\"75.0\""))
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

    @Test fun exportsDecoderLanguageSeparatelyAndLeavesHistoricalControlsUnrecorded() {
        val recorded = result().copy(languageControl = "automatic", recognitionLanguage = "auto")
        val json = JSONObject(BenchmarkExport.render(listOf(recorded, result()), BenchmarkExportFormat.JSON))
        val row = json.getJSONArray("results").getJSONObject(0)
        assertEquals("en", row.getString("source"))
        assertEquals("automatic", row.getString("languageControl"))
        assertEquals("auto", row.getString("recognitionLanguage"))
        assertEquals(recorded, BenchmarkResult.from(row))
        val historical = json.getJSONArray("results").getJSONObject(1)
        assertTrue(historical.isNull("languageControl"))
        assertTrue(historical.isNull("recognitionLanguage"))
        assertTrue(json.getString("recognition_language_notes").contains("decoder language hint"))
        val lines = BenchmarkExport.render(listOf(recorded, result()), BenchmarkExportFormat.CSV).lineSequence().toList()
        assertTrue(lines[0].endsWith("\"language_control\",\"recognition_language\",\"termination_reason\",\"attempt_timeout_seconds\",\"attempt_elapsed_ms\""))
        assertTrue(lines[1].endsWith("\"automatic\",\"auto\",\"\",\"\",\"\""))
        assertTrue(lines[2].endsWith("\"\",\"\",\"\",\"\",\"\""))
    }

    @Test fun timeoutExportsAsDisqualifiedWithMeasuredCapAndNoFabricatedScore() {
        val row=BenchmarkAttemptPolicy.failureResult(result().copy(terminationReason="deadline",attemptTimeoutSeconds=20,attemptElapsedMs=20012),"Disqualified: exceeded 20 seconds")
        val json=JSONObject(BenchmarkExport.render(listOf(row),BenchmarkExportFormat.JSON)).getJSONArray("results").getJSONObject(0)
        assertEquals(row,BenchmarkResult.from(json))
        assertTrue(json.isNull("referenceScore100"))
        val csv=BenchmarkExport.render(listOf(row),BenchmarkExportFormat.CSV)
        assertTrue(csv.contains("\"disqualified\""));assertTrue(csv.contains("\"deadline\",\"20\",\"20012\""))
        val partial=BenchmarkExport.render(listOf(result().copy(terminationReason="deadline",wordErrorRate=.25)),BenchmarkExportFormat.CSV)
        assertFalse(partial.lineSequence().drop(1).first().contains("\"10.0\""))
        assertFalse(partial.lineSequence().drop(1).first().contains("\"0.25\""))
    }

    private fun result() = BenchmarkResult(
        runId = "run", timestamp = 1, model = "model", identity = "id", route = "speech", inputHash = "same",
        source = "en", target = "", audioMs = 1000, loadMs = 10.0, computeMs = listOf(100.0, 100.0, 100.0),
        texts = listOf("spoken"), runtime = "runtime", firstTextMs = listOf(100.0, 50.0, 60.0), device = "phone", protocolVersion = 2,
    )
}

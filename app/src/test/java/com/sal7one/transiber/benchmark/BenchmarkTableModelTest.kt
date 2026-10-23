package com.sal7one.transiber.benchmark

import org.junit.Assert.*
import org.junit.Test

class BenchmarkTableModelTest {
    @Test fun hundredsOfRowsRemainSearchableAcrossModelRuntimeAndErrors() {
        val rows = (0 until 1200).map { result("model-$it", it.toDouble() + 1) }
        assertEquals(1200, BenchmarkTableModel.filtered(rows).size)
        assertEquals(listOf("model-1199"), BenchmarkTableModel.filtered(rows, "MODEL-1199 runtime").map { it.identity })
        val broken = result("broken", 1.0).copy(error = "Vulkan driver initialization failed")
        assertEquals(listOf(broken), BenchmarkTableModel.filtered(rows + broken, "vulkan failed"))
        assertEquals(listOf(broken), BenchmarkTableModel.filtered(rows + broken, status = BenchmarkResultStatus.FAILED))
    }

    @Test fun comparisonCohortsIncludeDeviceInputLanguagesTaskAndScoringProtocol() {
        val seed = result("one", 100.0)
        val other = listOf(seed.copy(inputHash = "different"), seed.copy(device = "different"),
            seed.copy(source = "ar"), seed.copy(target = "ar"), seed.copy(protocolVersion = 3),
            seed.copy(scoringNormalization = "other"), seed.copy(task = "voice"))
        assertEquals(listOf(seed), BenchmarkTableModel.filtered(listOf(seed) + other, cohort = BenchmarkTableModel.cohort(seed)))
        assertEquals(BenchmarkTableModel.cohort(seed.copy(timestamp = 999)),
            BenchmarkTableModel.latestCohort(listOf(seed.copy(timestamp = 999), seed), "en", ""))
    }

    @Test fun failureOrAbsentMetricsNeverBeatMeasuredSuccess() {
        val passed = result("passed", 300.0)
        val failed = result("failed", 0.1).copy(error = "inference failed", wordErrorRate = 0.0)
        assertEquals(listOf(passed, failed), BenchmarkTableModel.sorted(listOf(failed, passed), BenchmarkTableSort.WARM))
        assertNull(BenchmarkTableModel.warmMs(failed))
        assertNull(BenchmarkTableModel.qualityValue(failed))
        assertNull(BenchmarkTableModel.firstTextMs(failed))
        assertNull(BenchmarkTableModel.warmMs(passed.copy(computeMs = listOf(1.0, Double.NaN))))
        assertNull(BenchmarkTableModel.qualityValue(passed.copy(task = "voice")))
    }

    @Test fun disqualifiedAttemptsHaveTheirOwnFilterAndNeverRetainMeasuredRankings() {
        val success = result("finished", 300.0)
        val timeout = result("slow", 1.0).copy(terminationReason = "deadline", error = "Disqualified: exceeded 20 seconds")
        val crash = timeout.copy(identity = "crashed", terminationReason = "worker_crash", error = "Worker exited before finishing")
        val skipped = timeout.copy(identity = "skipped", terminationReason = "disqualified")
        val failed = timeout.copy(identity = "failed", terminationReason = null, error = "Unsupported input")
        val all = listOf(success, timeout, crash, skipped, failed)
        assertEquals(listOf(timeout, crash, skipped), BenchmarkTableModel.filtered(all, status = BenchmarkResultStatus.DISQUALIFIED))
        assertEquals(listOf(failed), BenchmarkTableModel.filtered(all, status = BenchmarkResultStatus.FAILED))
        assertEquals(listOf(success), BenchmarkTableModel.filtered(all, status = BenchmarkResultStatus.COMPLETED))
        assertEquals(listOf(timeout, crash, skipped), BenchmarkTableModel.filtered(all, "disqualified"))
        // A historical or malformed interrupted row must not resurrect cached quality/timing metrics.
        val interrupted = timeout.copy(error = null)
        assertFalse(BenchmarkTableModel.completed(interrupted))
        assertNull(BenchmarkTableModel.referenceScore100(interrupted))
        assertNull(BenchmarkTableModel.qualityValue(interrupted))
        assertNull(BenchmarkTableModel.audioSpeed(interrupted))
        assertNull(BenchmarkTableModel.firstTextMs(interrupted))
        assertEquals(listOf(success, interrupted), BenchmarkTableModel.sorted(listOf(interrupted, success), BenchmarkTableSort.LOAD))
    }

    @Test fun firstOutputUsesWarmMedianAndDoesNotInventStreamingEvidence() {
        val row = result("one", 100.0).copy(firstTextMs = listOf(500.0, 40.0, 60.0))
        assertEquals(50.0, BenchmarkTableModel.firstTextMs(row)!!, 0.0)
        assertNull(BenchmarkTableModel.firstTextMs(row.copy(firstTextMs = emptyList())))
        assertEquals("vulkan", BenchmarkTableModel.requestedBackend(row.copy(identity = "one@vulkan")))
        assertEquals("vulkan", BenchmarkTableModel.requestedBackend(row.copy(identity = "one@vulkan#language=auto")))
        assertNull(row.copy(identity = "one@vulkan").actualAcceleration)
        assertEquals("cpu", BenchmarkTableModel.requestedBackend(row.copy(identity = "one@vulkan", requestedAcceleration = "cpu")))
    }

    @Test fun recognitionControlIsRecordedMetadataAndCannotBeInferredFromSampleLanguage() {
        val row = result("one", 100.0)
        assertNull(BenchmarkTableModel.recognitionSetting(row))
        assertNull(BenchmarkTableModel.recognitionSetting(row.copy(source = "ar")))
        val hint = row.copy(languageControl = "forced", recognitionLanguage = "en")
        assertEquals(BenchmarkRecognitionSetting("forced", "en"), BenchmarkTableModel.recognitionSetting(hint))
        assertEquals(BenchmarkRecognitionSetting("automatic", "auto"),
            BenchmarkTableModel.recognitionSetting(row.copy(languageControl = "automatic", recognitionLanguage = "auto")))
        assertEquals(BenchmarkRecognitionSetting("fixed", "model"),
            BenchmarkTableModel.recognitionSetting(row.copy(languageControl = "fixed", recognitionLanguage = "model")))
        assertNull(BenchmarkTableModel.recognitionSetting(row.copy(languageControl = "forced", recognitionLanguage = "auto")))
        assertNull(BenchmarkTableModel.recognitionSetting(row.copy(languageControl = "automatic", recognitionLanguage = "en")))
        assertNull(BenchmarkTableModel.recognitionSetting(row.copy(languageControl = "guessed", recognitionLanguage = "en")))
        assertEquals(listOf(hint), BenchmarkTableModel.filtered(listOf(row, hint), "language hint"))
    }

    @Test fun modelSizeAndFirstOutputSortMissingMeasurementsLast() {
        val one = result("one", 200.0).copy(modelBytes = 300L, firstTextMs = listOf(100.0, 100.0, 100.0))
        val two = result("two", 100.0).copy(modelBytes = 100L, firstTextMs = listOf(50.0, 50.0, 50.0))
        val unknown = result("unknown", 1.0).copy(firstTextMs = emptyList())
        assertEquals(listOf(two, one, unknown), BenchmarkTableModel.sorted(listOf(unknown, one, two), BenchmarkTableSort.SIZE))
        assertEquals(listOf(two, one, unknown), BenchmarkTableModel.sorted(listOf(unknown, one, two), BenchmarkTableSort.FIRST_TEXT))
    }

    @Test fun voicesUsePairedGeneratedDurationsAndNeverClaimUnknownAudioThroughput() {
        val row = result("voice", 100.0).copy(task = "voice", computeMs = listOf(900.0, 100.0, 200.0),
            audioMs = 10_000, audioMsByPass = listOf(500, 1_000, 10_000), wordErrorRate = null)
        assertEquals(30.0, BenchmarkTableModel.audioSpeed(row)!!, .00001)
        assertNull(BenchmarkTableModel.audioSpeed(row.copy(audioMsByPass = emptyList())))
        assertNull(BenchmarkTableModel.audioSpeed(row.copy(audioMsByPass = listOf(500, 1_000))))
        assertNull(BenchmarkTableModel.audioSpeed(row.copy(audioMsByPass = listOf(500, 0, 10_000))))
        val shortFast = row.copy(identity = "short", computeMs = listOf(50.0, 50.0, 50.0), audioMsByPass = listOf(100, 100, 100))
        val leaders = BenchmarkComparison.latest(listOf(row, shortFast), "en", "", "voice")!!
        assertEquals("voice", leaders.fastest?.identity)
        assertEquals(listOf(row, shortFast, row.copy(identity = "unknown", audioMsByPass = emptyList())),
            BenchmarkTableModel.sorted(listOf(shortFast, row.copy(identity = "unknown", audioMsByPass = emptyList()), row), BenchmarkTableSort.AUDIO_SPEED))
        assertNull(leaders.mostAccurate)
        assertNull(leaders.balanced)
        assertEquals(row.audioMsByPass, BenchmarkResult.from(row.json()).audioMsByPass)
    }

    private fun result(id: String, warm: Double) = BenchmarkResult(
        runId = id, timestamp = 1, model = id, identity = id, route = "speech", inputHash = "same",
        source = "en", target = "", audioMs = 1000, loadMs = 10.0, computeMs = listOf(warm, warm, warm),
        texts = listOf("spoken", "spoken", "spoken"), runtime = "runtime evidence", firstTextMs = listOf(warm, warm, warm),
        device = "S25 Ultra", wordErrorRate = .1, protocolVersion = 2,
    )
}

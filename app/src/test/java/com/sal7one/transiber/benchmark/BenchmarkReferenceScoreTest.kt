package com.sal7one.transiber.benchmark

import org.junit.Assert.*
import org.junit.Test

class BenchmarkReferenceScoreTest {
    @Test fun speechScoreIsReferenceAgreementWithoutSpeedWeighting() {
        val row = speech().copy(wordErrorRate = .12)
        assertEquals(88.0, BenchmarkTableModel.referenceScore100(row)!!, .0001)
        assertEquals(88.0, BenchmarkTableModel.referenceScore100(row.copy(computeMs = listOf(1e6, 1e6, 1e6)))!!, .0001)
        // Insertions can make WER exceed 100%; the score remains bounded without hiding raw WER.
        assertEquals(0.0, BenchmarkTableModel.referenceScore100(row.copy(wordErrorRate = 1.8))!!, .0)
        assertEquals(1.8, BenchmarkTableModel.qualityValue(row.copy(wordErrorRate = 1.8))!!, .0)
        assertEquals(100.0, BenchmarkTableModel.referenceScore100(row.copy(wordErrorRate = .0))!!, .0)
    }

    @Test fun chineseUsesCharacterErrorsInsteadOfWordSegmentation() {
        val row = speech().copy(source = "zh", wordErrorRate = .9, characterErrorRate = .15)
        assertEquals(85.0, BenchmarkTableModel.referenceScore100(row)!!, .0001)
        assertEquals(85.0, BenchmarkTableModel.referenceScore100(row.copy(source = "zh-CN"))!!, .0001)
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(characterErrorRate = null)))
    }

    @Test fun translationUsesChrfAndAStoredSampleReference() {
        val row = speech().copy(task = "translation", target = "ar", referenceText = null,
            samples = listOf(BenchmarkSampleResult("ar", "مرحبا", "مرحبا", 10.0, 10.0, 0, false, "hello")),
            translationChrf = .72)
        assertEquals(72.0, BenchmarkTableModel.referenceScore100(row)!!, .0001)
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(translationChrf = 1.1)))
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(translationChrf = -.1)))
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(translationChrf = Double.NaN)))
    }

    @Test fun absentReferenceFailuresIncompleteOrVoiceRunsNeverReceiveAScore() {
        val row = speech()
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(referenceText = null)))
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(error = "native inference failed")))
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(computeMs = listOf(1.0))))
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(texts = listOf(""))))
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(task = "voice")))
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(wordErrorRate = -.1)))
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(wordErrorRate = Double.POSITIVE_INFINITY)))
        assertNull(BenchmarkTableModel.referenceScore100(row.copy(referenceText = null,
            samples = listOf(BenchmarkSampleResult("silence", "", " ", 10.0, 10.0, 100, true)))))
    }

    @Test fun languageMatrixKeepsLatestExactCohortAndEveryModelWithinThatCohort() {
        val oldEnglish = speech().copy(identity = "old", timestamp = 1, inputHash = "old-input")
        val newEnglish = speech().copy(identity = "new", timestamp = 2, inputHash = "new-input")
        val otherEnglishModel = newEnglish.copy(identity = "other", timestamp = 3)
        val chinese = speech().copy(source = "zh", timestamp = 4, inputHash = "chinese-input")
        val voice = speech().copy(task = "voice", timestamp = 5)
        val translation = speech().copy(task = "translation", target = "ar", timestamp = 6)
        val rows = listOf(oldEnglish, newEnglish, otherEnglishModel, chinese, voice, translation)
        val matrix = BenchmarkTableModel.scopedRows(rows, "speech", "en", "", allLanguages = true)
        assertEquals(setOf(newEnglish, otherEnglishModel, chinese), matrix.toSet())
        assertEquals(2, matrix.map(BenchmarkTableModel::cohort).distinct().size)
        assertEquals(setOf(oldEnglish, newEnglish, otherEnglishModel, chinese),
            BenchmarkTableModel.scopedRows(rows, "speech", "en", "", allInputs = true).toSet())
        assertEquals(listOf(voice), BenchmarkTableModel.scopedRows(rows, "voice", "en", "", allInputs = true))
        assertEquals(listOf(translation), BenchmarkTableModel.scopedRows(rows, "translation", "en", "ar", allInputs = true))
    }

    @Test fun languageMatrixDoesNotCombineDeviceOrProtocolCohortsAndPrefersLatestRerun() {
        val older = speech().copy(timestamp = 1, inputHash = "same")
        val newer = older.copy(timestamp = 2, device = "another device", protocolVersion = 3)
        val repeat = newer.copy(timestamp = 3)
        assertEquals(listOf(repeat), BenchmarkTableModel.scopedRows(listOf(older, newer, repeat),
            "speech", "en", "", allLanguages = true))
        assertEquals(listOf(older), BenchmarkTableModel.scopedRows(listOf(older, newer), "speech", "en", "",
            cohort = BenchmarkTableModel.cohort(older)))
    }

    @Test fun qualitySortPlacesMissingOrFailedScoresAfterValidScores() {
        val good = speech().copy(identity = "good", wordErrorRate = .1)
        val weak = speech().copy(identity = "weak", wordErrorRate = .5)
        val noReference = speech().copy(identity = "none", referenceText = null, wordErrorRate = .0)
        assertEquals(listOf(good, weak, noReference), BenchmarkTableModel.sorted(listOf(noReference, weak, good), BenchmarkTableSort.QUALITY))
    }

    private fun speech() = BenchmarkResult(
        runId = "run", timestamp = 1, model = "model", identity = "model@cpu", route = "speech", inputHash = "input",
        source = "en", target = "", audioMs = 1000, loadMs = 10.0, computeMs = listOf(100.0, 100.0, 100.0),
        texts = listOf("hello world"), runtime = "cpu", firstTextMs = listOf(100.0), device = "phone",
        referenceText = "hello world", wordErrorRate = .2, protocolVersion = 2,
    )
}

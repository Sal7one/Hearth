package com.sal7one.transiber.benchmark

import org.junit.Assert.*
import org.junit.Test

class BenchmarkPresetsTest {
    @Test fun onlySuggestsCatalogModelsSupportingTheSelectedPair() {
        val englishArabic = BenchmarkPresets.forPair("en", "ar")
        assertEquals("moonshine-tiny-en-v2", englishArabic[0].speech?.id)
        assertEquals("marian-en-ar", englishArabic[0].translation?.id)
        assertEquals("hy-mt2-q4", englishArabic[2].translation?.id)
        val russianArabic = BenchmarkPresets.forPair("ru", "ar")
        assertEquals("omnilingual-ctc-300m-v2-int8", russianArabic[0].speech?.id)
        assertEquals("marian-ru-ar-via-en", russianArabic[0].translation?.id)
        assertEquals("qwen3-asr-0.6b", russianArabic[1].speech?.id)
        assertEquals("nemotron-3.5-asr-0.6b", russianArabic[2].speech?.id)
        assertTrue(BenchmarkPresets.forPair("en", "en").all { it.translation == null })
    }

    @Test fun comparisonsIgnoreDifferentInputDeviceErrorsAndEmptyOutput() {
        val runs = listOf(
            result("slow-good", 300.0, 0.1, 4),
            result("fast-ok", 100.0, 0.3, 3),
            result("medium-best", 200.0, 0.0, 2),
            result("empty", 1.0, 0.0, 1).copy(texts = listOf("")),
            result("failed", 1.0, 0.0, 1).copy(error = "native failure"),
            result("other-input", 1.0, 0.0, 1).copy(inputHash = "another"),
            result("other-device", 1.0, 0.0, 1).copy(device = "another"),
        )
        val leaders = requireNotNull(BenchmarkComparison.latest(runs, "en", ""))
        assertEquals(3, leaders.candidates.size)
        assertEquals("fast-ok", leaders.fastest?.identity)
        assertEquals("medium-best", leaders.mostAccurate?.identity)
        assertEquals("medium-best", leaders.balanced?.identity)
    }

    @Test fun noReferenceNeverProducesAnAccuracyClaim() {
        val leaders = requireNotNull(BenchmarkComparison.latest(listOf(
            result("one", 100.0, null, 2), result("two", 200.0, null, 1)), "en", ""))
        assertNull(leaders.mostAccurate)
        assertNull(leaders.balanced)
        assertEquals("one", leaders.fastest?.identity)
    }

    @Test fun tableSortsTimesAndPutsMissingReferenceLast() {
        val slowAccurate = result("slow-accurate", 300.0, 0.05, 3).copy(loadMs = 10.0)
        val fastUnscored = result("fast-unscored", 100.0, null, 2).copy(loadMs = 30.0)
        val middle = result("middle", 200.0, 0.20, 1).copy(loadMs = 20.0)
        val rows = listOf(slowAccurate, fastUnscored, middle)
        assertEquals(listOf("fast-unscored", "middle", "slow-accurate"),
            BenchmarkTableModel.sorted(rows, BenchmarkTableSort.WARM).map { it.identity })
        assertEquals(listOf("slow-accurate", "middle", "fast-unscored"),
            BenchmarkTableModel.sorted(rows, BenchmarkTableSort.LOAD).map { it.identity })
        assertEquals(listOf("slow-accurate", "middle", "fast-unscored"),
            BenchmarkTableModel.sorted(rows, BenchmarkTableSort.QUALITY).map { it.identity })
        assertEquals(listOf("slow-accurate", "fast-unscored", "middle"),
            BenchmarkTableModel.sorted(rows, BenchmarkTableSort.RECENT).map { it.identity })
        assertEquals(0.95, BenchmarkTableModel.qualityScore(slowAccurate)!!, 0.00001)
        assertNull(BenchmarkTableModel.qualityScore(fastUnscored))
        val translation = slowAccurate.copy(target = "ar", wordErrorRate = null, translationChrf = 0.72)
        assertEquals(0.72, BenchmarkTableModel.qualityScore(translation)!!, 0.00001)
    }

    private fun result(id: String, warm: Double, wer: Double?, timestamp: Long) = BenchmarkResult(
        runId = id, timestamp = timestamp, model = id, identity = id, route = "speech",
        inputHash = "same", source = "en", target = "", audioMs = 1000,
        loadMs = 1.0, computeMs = listOf(warm, warm, warm), texts = listOf("spoken"),
        runtime = "test", firstTextMs = listOf(warm), device = "device", wordErrorRate = wer,
    )
}

package com.sal7one.transiber.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkLanguageSweepTest {
    private val languages = listOf("ar", "en", "ru", "zh")
    private val cases = buildList {
        languages.forEach { code ->
            add(BenchmarkCase("speech-$code", code, reference = "speech", referenceStatus = "publisher",
                audio = "audio/$code/1.wav", sha256 = "a".repeat(64), pcmSha256 = "b".repeat(64), publisherSentenceId = "1"))
        }
        listOf("ar" to "en", "en" to "ar", "ru" to "ar", "zh" to "en").forEach { (source, target) ->
            add(BenchmarkCase("translation-$source-$target", source, target, text = "source",
                reference = "target", referenceStatus = "publisher", publisherSentenceId = "1"))
        }
    }
    private val suite = BenchmarkSuite("fixture", "Fixture", "rev", "CC-BY-4.0", "https://example.test",
        "hash", cases, listOf("1"), listOf("1"), null)

    @Test fun speechSweepTestsEachAdvertisedBundledLanguageAndNeverInfersAutoCoverage() {
        val multilingual = BenchmarkCandidate("nemo", "Nemotron", "speech", setOf("ar", "en", "ru", "zh", "auto"))
        assertEquals(languages.map { BenchmarkRoute(it, "") }, BenchmarkLanguageSweep.routes(multilingual, suite))
        val unknown = BenchmarkCandidate("unknown", "Unknown", "speech", setOf("auto"))
        assertTrue(BenchmarkLanguageSweep.routes(unknown, suite).isEmpty())
        val english = BenchmarkCandidate("english", "English", "speech", setOf("en"))
        assertEquals(listOf(BenchmarkRoute("en", "")), BenchmarkLanguageSweep.routes(english, suite))
    }

    @Test fun translationSweepOnlyRunsPublishedDirectionsSupportedByCandidate() {
        val model = BenchmarkCandidate("mt", "Translator", "translation", setOf("ar", "ru", "zh"), setOf("en"))
        assertEquals(listOf(BenchmarkRoute("ar", "en"), BenchmarkRoute("zh", "en")),
            BenchmarkLanguageSweep.routes(model, suite))
    }

    @Test fun resultMustMatchBundledCaseIdsAndCurrentTimingProtocol() {
        val result = BenchmarkResult("run", 1, "Nemotron", "nemo", "speech", "hash", "en", "", 1000,
            1.0, listOf(2.0, 2.0, 2.0), listOf("text"), "runtime", listOf(1.0), "device",
            samples = listOf(BenchmarkSampleResult("speech-en", "text", "speech", 2.0, 1.0, 1000, false),
                BenchmarkSampleResult("silence-1000", "", "", 1.0, 0.0, 1000, true),
                BenchmarkSampleResult("silence-2500", "", "", 1.0, 0.0, 2500, true)), protocolVersion = 2)
        assertTrue(BenchmarkLanguageSweep.isBundled(result, suite))
        assertFalse(BenchmarkLanguageSweep.isBundled(result.copy(protocolVersion = 1), suite))
        assertFalse(BenchmarkLanguageSweep.isBundled(result.copy(samples = result.samples.drop(1)), suite))
    }
}

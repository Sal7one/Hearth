package com.sal7one.transiber.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkSuiteSelectionTest {
    private val quick = (1..6).map(Int::toString)
    private val cases = buildList {
        quick.forEach { id ->
            add(BenchmarkCase("speech-$id", "ru", reference = "ref $id", referenceStatus = "publisher",
                audio = "audio/ru/$id.wav", sha256 = "a".repeat(64), pcmSha256 = "b".repeat(64), publisherSentenceId = id))
            add(BenchmarkCase("translate-$id", "ru", "ar", text = "source $id", reference = "هدف $id",
                referenceStatus = "publisher", publisherSentenceId = id))
        }
        add(BenchmarkCase("speech-warmup", "ru", reference = "warmup", referenceStatus = "publisher",
            audio = "audio/ru/warmup.wav", sha256 = "c".repeat(64), pcmSha256 = "d".repeat(64), publisherSentenceId = "warmup"))
        add(BenchmarkCase("translate-en", "ru", "en", text = "source", reference = "reference",
            referenceStatus = "publisher", publisherSentenceId = "1"))
    }
    private val suite = BenchmarkSuite("fixture", "Fixture", "rev", "CC-BY-4.0", "https://example.test",
        "e".repeat(64), cases, quick, quick, "warmup")

    @Test fun quickSpeechUsesSixPublisherSamplesThenAddsSeparateSilenceChecks() {
        val selected = suite.selected(speech = true, source = "ru", target = "ar", full = false)
        assertEquals(8, selected.size)
        assertEquals(quick, selected.filterNot { it.silenceMs > 0 }.mapNotNull { it.publisherSentenceId })
        assertEquals(listOf(1000, 2500), selected.filter { it.silenceMs > 0 }.map { it.silenceMs })
        assertTrue(selected.none { it.publisherSentenceId == "warmup" })
    }

    @Test fun translationQuickSetKeepsTheRequestedDirectionAndAlignedSentenceIds() {
        val selected = suite.selected(speech = false, source = "ru", target = "ar", full = false)
        assertEquals(quick, selected.mapNotNull { it.publisherSentenceId })
        assertTrue(selected.all { it.source == "ru" && it.target == "ar" && it.referenceStatus == "publisher" })
        assertTrue(suite.selected(speech = false, source = "ru", target = "zh", full = false).isEmpty())
    }
}

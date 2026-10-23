package com.sal7one.transiber.benchmark

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BenchmarkAttemptPolicyTest {
    private val candidate = BenchmarkCandidate("speech-model", "Speech model", "Speech / batch", setOf("en", "ar"),
        acceleration = "cpu", artifactFingerprint = "verified-artifact")

    @Test fun importedPreferencesCannotRemoveOrOverflowTheDeadline() {
        assertEquals(20, BenchmarkAttemptPolicy.DEFAULT_SECONDS)
        assertEquals(5, BenchmarkAttemptPolicy.seconds(Int.MIN_VALUE))
        assertEquals(5_000L, BenchmarkAttemptPolicy.timeoutMillis(-1))
        assertEquals(15_000L, BenchmarkAttemptPolicy.timeoutMillis(15))
        assertEquals(20_000L, BenchmarkAttemptPolicy.timeoutMillis(30))
        assertEquals(20_000L, BenchmarkAttemptPolicy.timeoutMillis(Int.MAX_VALUE))
        assertTrue(BenchmarkAttemptPolicy.timeoutMessage(60).contains("20 seconds"))
    }

    @Test fun disqualifiedArtifactIsNotRetriedForEachLanguageHintOrAlias() {
        val key = BenchmarkAttemptPolicy.disqualificationKey(candidate)
        assertEquals(key, BenchmarkAttemptPolicy.disqualificationKey(candidate.copy(
            id = "another-registration", label = "Model · Arabic", recognitionLanguage = "ar", sourceCodes = setOf("ar"))))
        assertEquals(key, BenchmarkAttemptPolicy.disqualificationKey(candidate.copy(recognitionLanguage = "auto")))
        assertEquals(key, BenchmarkAttemptPolicy.disqualificationKey(candidate.copy(acceleration = "CPU")))
        assertNotEquals(key, BenchmarkAttemptPolicy.disqualificationKey(candidate.copy(acceleration = "vulkan")))
        assertNotEquals(key, BenchmarkAttemptPolicy.disqualificationKey(candidate.copy(acceleration = "opencl")))
        assertNotEquals(key, BenchmarkAttemptPolicy.disqualificationKey(candidate.copy(artifactFingerprint = "new-weights")))
    }

    @Test fun voiceStylesShareArtifactBudgetWithoutSuppressingOtherFamilies() {
        val voice = candidate.copy(id = "voice:family:female", voiceFamily = "family", voiceId = "female")
        assertEquals(BenchmarkAttemptPolicy.disqualificationKey(voice),
            BenchmarkAttemptPolicy.disqualificationKey(voice.copy(id = "voice:family:male", voiceId = "male")))
        assertNotEquals(BenchmarkAttemptPolicy.disqualificationKey(voice),
            BenchmarkAttemptPolicy.disqualificationKey(voice.copy(voiceFamily = "other-family")))
    }

    @Test fun unpinnedImportsFallBackToTheirActualPathBeforeRegistrationId() {
        val imported = candidate.copy(artifactFingerprint = null, file = File("/private/model.bin"))
        assertEquals(BenchmarkAttemptPolicy.disqualificationKey(imported),
            BenchmarkAttemptPolicy.disqualificationKey(imported.copy(id = "duplicate-registration")))
        assertNotEquals(BenchmarkAttemptPolicy.disqualificationKey(imported),
            BenchmarkAttemptPolicy.disqualificationKey(imported.copy(file = File("/private/other.bin"))))
        assertNotEquals(BenchmarkAttemptPolicy.disqualificationKey(imported.copy(file = null)),
            BenchmarkAttemptPolicy.disqualificationKey(imported.copy(file = null, id = "other")))
    }

    @Test fun partialPassesCannotBecomeCompletedOrScoreAfterTimeout() {
        val partial = BenchmarkResult("run", 1, "Model", "model@cpu", "Speech / batch", "input", "en", "", 1000,
            50.0, listOf(100.0, 90.0, 80.0), listOf("hello", "hello", "hello"), "CPU", listOf(20.0, 10.0, 10.0), "phone",
            referenceText = "hello", wordErrorRate = 0.0, characterErrorRate = 0.0, translationChrf = 1.0,
            samples = listOf(BenchmarkSampleResult("clip", "hello", "hello", 80.0, 10.0, 1000, false)),
            audioMsByPass = listOf(1000, 1000, 1000), requestedAcceleration = "cpu", actualAcceleration = "cpu")
        assertTrue(BenchmarkTableModel.completed(partial))
        assertEquals(100.0, checkNotNull(BenchmarkTableModel.referenceScore100(partial)), 0.0)
        val failed = BenchmarkAttemptPolicy.failureResult(partial, BenchmarkAttemptPolicy.timeoutMessage(20))
        assertFalse(BenchmarkTableModel.completed(failed))
        assertNull(BenchmarkTableModel.referenceScore100(failed))
        assertNull(BenchmarkTableModel.qualityScore(failed))
        assertNull(BenchmarkTableModel.audioSpeed(failed))
        assertNull(BenchmarkTableModel.firstTextMs(failed))
        assertTrue(failed.samples.isEmpty())
        assertTrue(failed.texts.isEmpty())
        assertTrue(failed.computeMs.isEmpty())
        assertEquals(partial.identity, failed.identity)
        assertEquals(partial.inputHash, failed.inputHash)
        assertEquals(partial.actualAcceleration, failed.actualAcceleration)
        assertEquals(partial.referenceText, failed.referenceText)
    }
}

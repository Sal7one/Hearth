package com.sal7one.transiber.benchmark

import com.sal7one.common_jni.speech.SpeechProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BenchmarkLanguagePlanTest {
    @Test fun multilingualHintsAndAutomaticRecognitionRemainSeparateFromTheSampleLanguage() {
        val qwen = profile(SpeechProfile.QWEN3_ASR_0_6B)
        for (source in listOf("ar", "en")) {
            val matching = BenchmarkLanguagePlan.expand(listOf(qwen), source, BenchmarkLanguageMode.MATCH_INPUT)
            assertEquals(listOf(source), matching.models.map { it.recognitionLanguage })
            assertEquals(BenchmarkRecognitionSetting("forced", source), BenchmarkLanguagePlan.recognition(matching.models.single(), source))
            val automatic = BenchmarkLanguagePlan.expand(listOf(qwen), source, BenchmarkLanguageMode.AUTOMATIC)
            assertEquals(listOf("auto"), automatic.models.map { it.recognitionLanguage })
            assertEquals(BenchmarkRecognitionSetting("automatic", "auto"), BenchmarkLanguagePlan.recognition(automatic.models.single(), source))
            val both = BenchmarkLanguagePlan.expand(listOf(qwen), source, BenchmarkLanguageMode.BOTH)
            assertEquals(listOf(source, "auto"), both.models.map { it.recognitionLanguage })
            assertTrue(both.omitted.isEmpty())
        }
    }

    @Test fun enginesWithoutLanguageHintsAreNeverLabelledAsForcedOrDuplicated() {
        listOf(SpeechProfile.OMNILINGUAL_CTC_300M_V2, SpeechProfile.TRANSCRIBEPSY_250M_EU_SUB,
            SpeechProfile.VOXTRAL_MINI_REALTIME_2602, SpeechProfile.PHONON_2_5).forEach { speechProfile ->
            BenchmarkLanguageMode.entries.forEach { mode ->
                val expanded = BenchmarkLanguagePlan.expand(listOf(profile(speechProfile)), "en", mode)
                assertEquals(listOf("auto"), expanded.models.map { it.recognitionLanguage })
                assertEquals(BenchmarkRecognitionSetting("automatic", "auto"), BenchmarkLanguagePlan.recognition(expanded.models.single(), "en"))
            }
        }
        val autoOnly = profile(SpeechProfile.OMNILINGUAL_CTC_300M_V2)
        assertEquals(BenchmarkRecognitionSetting("automatic", "auto"), BenchmarkLanguagePlan.recognition(autoOnly, "ar"))
        assertTrue(runCatching { BenchmarkLanguagePlan.recognition(autoOnly.copy(recognitionLanguage = "ar"), "ar") }.isFailure)
    }

    @Test fun multilingualPhononRunsRussianOnceWithoutInventedLanguageForcingAndRejectsArabicAudio() {
        val modern = profile(SpeechProfile.PHONON_2_5)
        BenchmarkLanguageMode.entries.forEach { mode ->
            val russian = BenchmarkLanguagePlan.expand(listOf(modern), "ru", mode)
            assertEquals(1, russian.models.size)
            assertEquals(BenchmarkRecognitionSetting("automatic", "auto"), BenchmarkLanguagePlan.recognition(russian.models.single(), "ru"))
            val arabic = BenchmarkLanguagePlan.expand(listOf(modern), "ar", mode)
            assertTrue(arabic.models.isEmpty())
            assertTrue(arabic.omitted.single().contains("does not support recordings in ar"))
        }
    }

    @Test fun hintOnlyModelsAreExplicitlyOmittedFromAutomaticRatherThanGivenInventedDetection() {
        val cohere = profile(SpeechProfile.COHERE_ARABIC_07_2026)
        val automatic = BenchmarkLanguagePlan.expand(listOf(cohere), "ar", BenchmarkLanguageMode.AUTOMATIC)
        assertTrue(automatic.models.isEmpty())
        assertTrue(automatic.omitted.single().contains("automatic language detection is unavailable"))
        assertEquals(listOf("ar"), BenchmarkLanguagePlan.expand(listOf(cohere), "ar", BenchmarkLanguageMode.BOTH).models.map { it.recognitionLanguage })
        assertTrue(runCatching { BenchmarkLanguagePlan.recognition(cohere.copy(recognitionLanguage = "auto"), "ar") }.isFailure)
    }

    @Test fun fixedLanguageModelsAndEnglishOnlyWhisperRunExactlyOnceInEveryMode() {
        val englishWhisper = BenchmarkCandidate("english", "Whisper English", "Whisper / batch", setOf("en"))
        val english = listOf(profile(SpeechProfile.MOONSHINE_TINY_EN), profile(SpeechProfile.PHONON_2), englishWhisper)
        BenchmarkLanguageMode.entries.forEach { mode ->
            val expanded = BenchmarkLanguagePlan.expand(english, "en", mode)
            assertEquals(3, expanded.models.size)
            assertEquals(listOf("en", "en", "en"), expanded.models.map { it.recognitionLanguage })
            assertTrue(expanded.models.all { BenchmarkLanguagePlan.recognition(it, "en") == BenchmarkRecognitionSetting("fixed", "en") })
            val arabic = BenchmarkLanguagePlan.expand(listOf(profile(SpeechProfile.MOONSHINE_BASE_AR)), "ar", mode)
            assertEquals(BenchmarkRecognitionSetting("fixed", "ar"), BenchmarkLanguagePlan.recognition(arabic.models.single(), "ar"))
        }
        val unsupported = BenchmarkLanguagePlan.expand(english, "ar", BenchmarkLanguageMode.BOTH)
        assertTrue(unsupported.models.isEmpty())
        assertEquals(3, unsupported.omitted.size)
    }

    @Test fun voskKeepsItsModelLanguageRatherThanPretendingTheDecoderCanBeForced() {
        val vosk = BenchmarkCandidate("vosk", "Vosk Arabic", "Vosk / batch", setOf("model"))
        BenchmarkLanguageMode.entries.forEach { mode ->
            val expanded = BenchmarkLanguagePlan.expand(listOf(vosk), "ar", mode)
            assertEquals(listOf("model"), expanded.models.map { it.recognitionLanguage })
            assertEquals(BenchmarkRecognitionSetting("fixed", "model"), BenchmarkLanguagePlan.recognition(expanded.models.single(), "ar"))
        }
    }

    @Test fun multilingualWhisperLanguageAndAccelerationModesFormFourDistinctRuns() {
        val whisper = BenchmarkCandidate("whisper", "Whisper", "Whisper / batch", setOf("en", "ar", "auto"), artifactFingerprint = "pin")
        val planned = BenchmarkLanguagePlan.expand(listOf(whisper, whisper), "en", BenchmarkLanguageMode.BOTH).models
        val accelerated = BenchmarkAccelerationPlan.expand(planned, true)
        assertEquals(4, accelerated.size)
        assertEquals(4, accelerated.map(BenchmarkAccelerationPlan::identity).toSet().size)
        assertEquals(4, accelerated.map { BenchmarkExecutionMetadata.configurationHash(it, 3) }.toSet().size)
        assertEquals(setOf("en", "auto"), accelerated.map { it.recognitionLanguage }.toSet())
        assertEquals(setOf("cpu", "vulkan"), accelerated.map { it.acceleration }.toSet())
        assertNull(whisper.recognitionLanguage)
        assertEquals("whisper", BenchmarkAccelerationPlan.identity(whisper))
    }

    @Test fun oneUnsupportedRouteDoesNotDiscardHundredsOfCompatibleModelsAndNonSpeechIsUnchanged() {
        val qwen = profile(SpeechProfile.QWEN3_ASR_0_6B)
        val models = List(500) { qwen.copy(id = "model-$it") }
        val incompatible = profile(SpeechProfile.MOONSHINE_TINY_EN)
        val planned = BenchmarkLanguagePlan.expand(models + incompatible, "ar", BenchmarkLanguageMode.BOTH)
        assertEquals(1000, planned.models.size)
        assertEquals(1, planned.omitted.size)
        val translation = BenchmarkCandidate("translator", "Translator", "Translation", setOf("en"), setOf("ar"))
        val voice = BenchmarkCandidate("voice", "Voice", "Voice", setOf("en"), voiceFamily = "fixture", voiceId = "f")
        assertEquals(listOf(translation, voice), BenchmarkLanguagePlan.expand(listOf(translation, voice), "en", BenchmarkLanguageMode.BOTH).models)
        assertTrue(runCatching { BenchmarkLanguagePlan.expand(models, "auto", BenchmarkLanguageMode.BOTH) }.isFailure)
        assertEquals("en", BenchmarkLanguagePlan.expand(listOf(qwen), "English", BenchmarkLanguageMode.MATCH_INPUT).models.single().recognitionLanguage)
    }

    @Test fun resultJsonRoundTripsDecoderChoiceWithoutChangingTheScoringLanguageAndReadsOldRows() {
        val row = result().copy(languageControl = "automatic", recognitionLanguage = "auto")
        val restored = BenchmarkResult.from(row.json())
        assertEquals(row, restored)
        assertEquals("en", restored.source)
        assertEquals("auto", restored.recognitionLanguage)
        val old = JSONObject(row.json().toString()).apply { remove("languageControl"); remove("recognitionLanguage") }
        assertNull(BenchmarkResult.from(old).languageControl)
        assertNull(BenchmarkResult.from(old).recognitionLanguage)
    }

    @Test fun resumeCannotConfuseAutomaticRecognitionWithMatchingLanguageOrLegacyDefaults() {
        val base = BenchmarkCandidate("whisper", "Whisper", "Whisper / batch", setOf("en", "ar", "auto"),
            acceleration = "cpu", artifactFingerprint = "pin")
        val forced = base.copy(recognitionLanguage = "en")
        val automatic = base.copy(recognitionLanguage = "auto")
        val hash = checkNotNull(BenchmarkExecutionMetadata.configurationHash(forced, 3))
        val row = result().copy(identity = BenchmarkAccelerationPlan.identity(forced), configurationHash = hash,
            actualAcceleration = "cpu", languageControl = "forced", recognitionLanguage = "en",
            protocolVersion=BenchmarkAttemptPolicy.PROTOCOL_VERSION)
        fun resume(candidate: BenchmarkCandidate) = BenchmarkExecutionMetadata.canResume(row, candidate, "input",
            BenchmarkExecutionMetadata.configurationHash(candidate, 3), "en", "", "phone", 3)
        assertTrue(resume(forced))
        assertFalse(resume(automatic))
        assertFalse(resume(base))
        assertNotEquals(BenchmarkExecutionMetadata.configurationHash(base, 3), hash)
    }

    private fun profile(profile: SpeechProfile) = BenchmarkCandidate(profile.id, profile.id, "Speech / utterance",
        profile.capabilities.sourceLanguages + if (profile.capabilities.automaticSourceLanguage) setOf("auto") else emptySet(),
        profile = profile, artifactFingerprint = "pin")

    private fun result() = BenchmarkResult("run", 1, "Whisper", "whisper", "Whisper / batch", "input", "en", "", 1000,
        10.0, listOf(30.0, 20.0, 20.0), listOf("hello"), "CPU", listOf(10.0, 9.0, 9.0), "phone", protocolVersion = 2)
    @Test fun exactVoskTreePinsEnableOnlyTheirKnownFixedLanguage() {
        com.sal7one.transiber.models.VoskDownloads.packages.forEach { artifact ->
            val codes = BenchmarkLanguagePlan.voskLanguages(artifact.treeSha256)
            assertEquals(setOf(artifact.language), codes)
            val model = BenchmarkCandidate("vosk", "Vosk", "Vosk / batch", codes)
            val plan = BenchmarkLanguagePlan.expand(listOf(model), artifact.language, BenchmarkLanguageMode.BOTH)
            assertEquals(1, plan.models.size)
            assertEquals(BenchmarkRecognitionSetting("fixed", artifact.language),
                BenchmarkLanguagePlan.recognition(plan.models.single(), artifact.language))
        }
        assertEquals(setOf("model"), BenchmarkLanguagePlan.voskLanguages(null))
        assertEquals(setOf("model"), BenchmarkLanguagePlan.voskLanguages("0".repeat(64)))
    }

}

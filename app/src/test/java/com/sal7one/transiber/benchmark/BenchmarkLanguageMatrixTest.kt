package com.sal7one.transiber.benchmark

import com.sal7one.common_jni.translation.TranslationCatalog
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BenchmarkLanguageMatrixTest {
    private val assets = listOf(File("src/main/assets/benchmark"), File("app/src/main/assets/benchmark"))
        .first { File(it, BenchmarkSuite.MANIFEST).isFile }
    private val suite = BenchmarkSuite.parse(File(assets, BenchmarkSuite.MANIFEST).readBytes())
    private val multilingual = BenchmarkCandidate("multilingual", "Multilingual", "Speech", setOf("en", "ru", "zh", "ar", "auto"))

    @Test fun bundledEnglishRussianChineseUseIdenticalPinnedReferencesForEveryCompatibleModel() {
        val english = BenchmarkCandidate("english", "English", "Speech", setOf("en"))
        val plan = BenchmarkLanguageMatrix.plan(listOf(multilingual, english), suite,
            BenchmarkLanguageMatrix.defaultLanguages, BenchmarkTask.SPEECH)
        assertEquals(listOf("en", "ru", "zh"), plan.groups.map { it.route.source })
        assertEquals(4, plan.comparisonCount)
        assertSame(multilingual, plan.groups.first().models.first())
        plan.groups.forEach { group ->
            assertEquals(suite.selected(true, group.route.source, "", false), group.cases)
            val speech = group.cases.filter { it.audio != null }
            assertEquals(6, speech.size)
            assertEquals(listOf(1000, 2500), group.cases.filter { it.silenceMs > 0 }.map { it.silenceMs })
            speech.forEach { sample ->
                assertEquals(group.route.source, sample.source)
                assertEquals("publisher", sample.referenceStatus)
                assertFalse(sample.reference.isNullOrBlank())
                val bytes = File(assets, sample.audio!!).readBytes()
                assertEquals(sample.sha256, benchmarkHash(bytes))
                assertEquals(sample.pcmSha256, BenchmarkAudio.decode(bytes).sha256)
            }
        }
        assertEquals(2, plan.groups.first().models.size)
        assertTrue(plan.groups.drop(1).all { it.models == listOf(multilingual) })
    }

    @Test fun arabicOnlyRunsWhenExplicitlySelectedAndUsesThePassedPublisherCorpus() {
        val plan = BenchmarkLanguageMatrix.plan(listOf(multilingual), suite, setOf("ar"), BenchmarkTask.SPEECH)
        assertEquals(listOf(BenchmarkRoute("ar", "")), plan.groups.map { it.route })
        assertEquals(suite.selected(true, "ar", "", false), plan.groups.single().cases)
        assertTrue(plan.groups.single().cases.filter { it.audio != null }.all { it.id.startsWith("fleurs-ar-") })
        val personal = suite.copy(cases = suite.cases.map { it.copy(referenceStatus = "human-reviewed") })
        val rejected = BenchmarkLanguageMatrix.plan(listOf(multilingual), personal, setOf("ar"), BenchmarkTask.SPEECH)
        assertTrue(rejected.groups.isEmpty())
        assertTrue(rejected.skipped.single().contains("personal recordings are not substituted"))
    }

    @Test fun automaticAndUnknownVoskLanguagesNeverInventCoverage() {
        val unknown = BenchmarkCandidate("vosk-unknown", "Vosk", "Vosk / batch", setOf("model"))
        val automatic = BenchmarkCandidate("auto-only", "Unknown multilingual", "Speech", setOf("auto"))
        val plan = BenchmarkLanguageMatrix.plan(listOf(unknown, automatic), suite, setOf("en", "ru", "zh"), BenchmarkTask.SPEECH)
        assertTrue(plan.groups.isEmpty())
        assertEquals(0, plan.comparisonCount)
        assertEquals(6, plan.skipped.size)
        assertTrue(plan.skipped.any { "vosk-unknown" in it && "no explicit advertised" in it })
        assertTrue(plan.skipped.any { "auto-only" in it && "no explicit advertised" in it })
    }

    @Test fun translationsRequireBothPublishedDirectionAndEngineSupport() {
        val broad = BenchmarkCandidate("broad-mt", "Translator", "Translation", setOf("en", "ru", "zh", "ar"), setOf("en", "ru", "zh", "ar"))
        val spec = TranslationCatalog.find("riva-translate-4b-v2-q4")
        val riva = BenchmarkCandidate("riva", "Riva", "Translation", spec.sourceLanguages, spec.targetLanguages, translation = spec)
        val plan = BenchmarkLanguageMatrix.plan(listOf(broad, riva), suite,
            BenchmarkLanguageMatrix.languages.toSet(), BenchmarkTask.TRANSLATION)
        assertEquals(setOf(BenchmarkRoute("en", "ar"), BenchmarkRoute("ar", "en"),
            BenchmarkRoute("ru", "en"), BenchmarkRoute("ru", "ar"),
            BenchmarkRoute("zh", "en"), BenchmarkRoute("zh", "ar")), plan.groups.map { it.route }.toSet())
        assertEquals(10, plan.comparisonCount)
        plan.groups.forEach { group ->
            assertEquals(6, group.cases.size)
            assertTrue(group.cases.all { sample -> !sample.speech && sample.source == group.route.source &&
                sample.target == group.route.target && sample.text.isNotBlank() && !sample.reference.isNullOrBlank() &&
                sample.referenceStatus == "publisher" })
            assertEquals(group.route.source == "en" || group.route.target == "en", riva in group.models)
        }
        assertTrue(plan.skipped.any { "en → ru" in it && "no complete publisher reference" in it })
        assertTrue(plan.skipped.any { "riva" in it && "ru → ar" in it && "direction is not supported" in it })
    }

    @Test fun fiveHundredModelsHaveNoSelectionCapAndRemainSequentiallyGroupable() {
        val models = (1..500).map { multilingual.copy(id = "model-$it") }
        val plan = BenchmarkLanguageMatrix.plan(models, suite, BenchmarkLanguageMatrix.defaultLanguages, BenchmarkTask.SPEECH)
        assertEquals(3, plan.groups.size)
        assertEquals(1500, plan.comparisonCount)
        assertTrue(plan.groups.all { it.models.size == 500 && it.models.map { model -> model.id } == models.map { model -> model.id } })
        assertTrue(plan.skipped.isEmpty())
    }

    @Test fun chosenTranslationDestinationUsesEveryPublishedSourceWithoutUnrelatedSkipReasons() {
        val translator = BenchmarkCandidate("mt", "Translator", "Translation", setOf("ar", "en", "ru", "zh"), setOf("ar", "en", "ru", "zh"))
        val plan = BenchmarkLanguageMatrix.plan(listOf(translator), suite,
            setOf("ar", "en", "ru", "zh"), BenchmarkTask.TRANSLATION, translationTarget = "ar")
        assertEquals(setOf(BenchmarkRoute("en", "ar"), BenchmarkRoute("ru", "ar"), BenchmarkRoute("zh", "ar")),
            plan.groups.map { it.route }.toSet())
        assertTrue(plan.groups.all { group -> group.cases.all { it.target == "ar" } })
        assertTrue(plan.skipped.isEmpty())
    }

    @Test fun missingReferenceAudioOrSilenceAloneCannotBeAReferenceComparison() {
        val silence = BenchmarkCase("silence", "en", reference = "", referenceStatus = "human-reviewed", silenceMs = 1000)
        val silenceOnly = suite.copy(cases = listOf(silence), quickIds = emptyList(), fullIds = emptyList(), warmupId = null)
        val unpinned = suite.copy(cases = suite.cases.map { if (it.audio != null) it.copy(sha256 = null) else it })
        val withoutReferences = suite.copy(cases = suite.cases.map { it.copy(reference = null) })
        listOf(silenceOnly, unpinned, withoutReferences).forEach { invalid ->
            val plan = BenchmarkLanguageMatrix.plan(listOf(multilingual), invalid, setOf("en"), BenchmarkTask.SPEECH)
            assertTrue(plan.groups.isEmpty())
            assertTrue(plan.skipped.single().contains("no complete publisher reference"))
        }
    }

    @Test fun extendedSpeechIncludesDistinctRealLongClipAndSeparateSilenceChecks() {
        val plan = BenchmarkLanguageMatrix.plan(listOf(multilingual), suite, setOf("en"), BenchmarkTask.SPEECH, full = true)
        val samples = plan.groups.single().cases
        assertEquals(9, samples.size)
        assertEquals(7, samples.count { it.audio != null })
        assertEquals(suite.warmupId, samples[6].publisherSentenceId)
        assertEquals(listOf(1000, 2500), samples.takeLast(2).map { it.silenceMs })
    }

    @Test fun noLanguageOrNoModelsGivesActionableEmptyPlanAndUnbundledLanguagesAreRejected() {
        assertTrue(BenchmarkLanguageMatrix.plan(listOf(multilingual), suite, emptySet(), BenchmarkTask.SPEECH).skipped.single().contains("Choose"))
        assertTrue(BenchmarkLanguageMatrix.plan(emptyList(), suite, setOf("en"), BenchmarkTask.SPEECH).skipped.single().contains("Select"))
        try {
            BenchmarkLanguageMatrix.plan(listOf(multilingual), suite, setOf("ja"), BenchmarkTask.SPEECH)
            fail("Expected unbundled language to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun voiceAndOtherTaskModelsCannotAcquireFabricatedSpeechReferenceScores() {
        val voice = BenchmarkCandidate("voice", "Voice", "Voice", setOf("en"), voiceFamily = "supertonic", voiceId = "F1")
        val voicePlan = BenchmarkLanguageMatrix.plan(listOf(voice), suite, setOf("en"), BenchmarkTask.VOICE)
        assertTrue(voicePlan.groups.isEmpty())
        assertTrue(voicePlan.skipped.single().contains("Voice synthesis"))
        val speechPlan = BenchmarkLanguageMatrix.plan(listOf(voice, multilingual), suite, setOf("en"), BenchmarkTask.SPEECH)
        assertEquals(listOf(multilingual), speechPlan.groups.single().models)
        assertTrue(speechPlan.skipped.single().contains("voice: selected model belongs to voice"))
    }
}

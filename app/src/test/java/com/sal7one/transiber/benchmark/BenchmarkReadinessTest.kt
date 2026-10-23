package com.sal7one.transiber.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkReadinessTest {
    private fun issue(
        task: BenchmarkTask = BenchmarkTask.SPEECH,
        selected: Int = 20,
        loading: Boolean = false,
        preparing: Boolean = false,
        owner: String? = null,
        running: Boolean = false,
        builtIn: Boolean = true,
        cases: Int = 7,
        saudi: Boolean = false,
        audio: Boolean = false,
        text: Boolean = false,
        source: String = "ar",
        target: String = "en",
    ) = benchmarkReadinessIssue(task, selected, loading, preparing, owner, running,
        builtIn, cases, saudi, audio, text, source, target)

    @Test fun twentySelectedModelsNeedNoCustomAudioWhenBundledInputIsReady() {
        assertNull(issue())
        // Background discovery must pass loading=false once a usable snapshot exists.
        assertEquals(BenchmarkReadinessIssue.INITIALIZING, issue(loading = true))
        assertNull(issue(loading = false))
    }

    @Test fun missingArabicSaudiRecordingsAreAnInputProblemNotAModelProblem() {
        assertEquals(BenchmarkReadinessIssue.EMPTY_SAUDI_SET, issue(saudi = true, cases = 0))
        assertEquals(BenchmarkReadinessFix.INPUT, issue(saudi = true, cases = 0)!!.fix)
        assertNull(issue(saudi = true, cases = 1))
        // Switching away from Arabic uses the ordinary bundled set, even if the preference persists.
        assertEquals(BenchmarkReadinessIssue.NO_BUILT_IN_SAMPLES, issue(saudi = true, cases = 0, source = "en"))
    }

    @Test fun missingCustomSpeechAudioHasAnActionableReason() {
        assertEquals(BenchmarkReadinessIssue.NO_AUDIO, issue(builtIn = false))
        assertNull(issue(builtIn = false, audio = true))
    }

    @Test fun sampleLanguageCannotBeAnAutomaticRecognitionStrategy() {
        for (source in listOf("auto", "model", "")) {
            assertEquals(BenchmarkReadinessIssue.UNKNOWN_SAMPLE_LANGUAGE, issue(source = source))
            assertEquals(BenchmarkReadinessFix.INPUT, issue(source = source)!!.fix)
        }
    }

    @Test fun translationValidatesTheActualInputModeAndPair() {
        assertNull(issue(task = BenchmarkTask.TRANSLATION))
        assertEquals(BenchmarkReadinessIssue.NO_BUILT_IN_SAMPLES, issue(task = BenchmarkTask.TRANSLATION, cases = 0))
        assertEquals(BenchmarkReadinessIssue.NO_TEXT, issue(task = BenchmarkTask.TRANSLATION, builtIn = false))
        assertNull(issue(task = BenchmarkTask.TRANSLATION, builtIn = false, text = true))
        assertEquals(BenchmarkReadinessIssue.SAME_LANGUAGE_PAIR,
            issue(task = BenchmarkTask.TRANSLATION, builtIn = false, text = true, target = "ar"))
        assertEquals(BenchmarkReadinessIssue.SAME_LANGUAGE_PAIR,
            issue(task = BenchmarkTask.TRANSLATION, target = "ar"))
    }

    @Test fun voiceAlwaysRequiresTextAndDoesNotRequireAudioOrBundledSamples() {
        assertEquals(BenchmarkReadinessIssue.NO_TEXT, issue(task = BenchmarkTask.VOICE, cases = 0))
        assertNull(issue(task = BenchmarkTask.VOICE, cases = 0, text = true))
        assertNull(issue(task = BenchmarkTask.VOICE, builtIn = false, text = true))
    }

    @Test fun selectingNoCompatibleModelsDirectsToModels() {
        assertEquals(BenchmarkReadinessIssue.NO_MODELS, issue(selected = 0, builtIn = false))
        assertEquals(BenchmarkReadinessFix.MODELS, issue(selected = 0)!!.fix)
    }

    @Test fun ownerStaysBlockedUntilCleanupReleasesItsLease() {
        assertEquals(BenchmarkReadinessIssue.ENGINE_IN_USE, issue(owner = "Captions"))
        assertEquals(BenchmarkReadinessFix.STOP_ACTIVITY, issue(owner = "Captions")!!.fix)
        assertEquals(BenchmarkReadinessIssue.ENGINE_IN_USE, issue(owner = "Benchmark"))
        assertNull(issue(owner = "Benchmark", running = true))
        assertNull(issue(owner = null))
    }

    @Test fun initialLoadAndInputPreparationExplainTheirTemporaryBlockWithoutAFakeFix() {
        assertEquals(BenchmarkReadinessIssue.INITIALIZING, issue(loading = true, preparing = true, owner = "Captions"))
        assertEquals(BenchmarkReadinessIssue.PREPARING_INPUT, issue(preparing = true, owner = "Captions"))
        assertEquals(BenchmarkReadinessFix.NONE, issue(loading = true)!!.fix)
        assertEquals(BenchmarkReadinessFix.NONE, issue(preparing = true)!!.fix)
    }
}

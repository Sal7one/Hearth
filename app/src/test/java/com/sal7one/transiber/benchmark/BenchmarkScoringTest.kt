package com.sal7one.transiber.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkScoringTest {
    @Test fun normalizesCaseAndPunctuationBeforeWordErrorRate() {
        assertEquals(0.0, BenchmarkScoring.wordErrors("Hello, world!", "hello world").rate!!, 0.0)
    }

    @Test fun reportsInsertionsAndCharacterErrorsAgainstTheReference() {
        assertEquals(1.0 / 3.0, BenchmarkScoring.wordErrors("we saw cat", "we saw a cat").rate!!, 0.000001)
        assertEquals(1.0 / 3.0, BenchmarkScoring.characterErrors("cat", "cart").rate!!, 0.000001)
    }

    @Test fun doesNotInventAReferenceRateForInsertionsAgainstAnEmptyReference() {
        assertNull(BenchmarkScoring.wordErrors("", "unreferenced output").rate)
    }

    @Test fun publisherChineseCharacterSpacingDoesNotPenalizeUnspacedCaptions() {
        assertEquals(0.0, BenchmarkScoring.characterErrors("毋 庸 置 疑", "毋庸置疑").rate!!, 0.0)
    }

    @Test fun arabicAndRussianReferenceScriptsSurviveNormalization() {
        assertEquals(0.0, BenchmarkScoring.wordErrors("مرحبا، بالعالم!", "مرحبا بالعالم").rate!!, 0.0)
        assertEquals(0.0, BenchmarkScoring.wordErrors("Привет, мир!", "привет мир").rate!!, 0.0)
    }
    @Test fun longMixedScriptsUseBoundedScoringRowsAndPreserveEditCounts() {
        val reference = (1..500).joinToString(" ") { "كلمة$it" }
        val hypothesis = (1..500).joinToString(" ") { if (it == 250) "different" else "كلمة$it" }
        val errors = BenchmarkScoring.wordErrors(reference, hypothesis)
        assertEquals(1, errors.substitutions)
        assertEquals(0, errors.insertions)
        assertEquals(0, errors.deletions)
        assertEquals(500, errors.referenceUnits)
    }

    @Test fun editComponentsPreserveSubstitutionDeletionInsertionTieOrder() {
        assertEquals(BenchmarkScoring.Errors(1, 1, 0, 3), BenchmarkScoring.wordErrors("a b c", "a x"))
        assertEquals(BenchmarkScoring.Errors(0, 0, 1, 2), BenchmarkScoring.wordErrors("a b", "a x b"))
        assertEquals(BenchmarkScoring.Errors(0, 2, 0, 2), BenchmarkScoring.wordErrors("a b", ""))
    }
}

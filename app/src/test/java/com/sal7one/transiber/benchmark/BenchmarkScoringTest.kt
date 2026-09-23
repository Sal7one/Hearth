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
}

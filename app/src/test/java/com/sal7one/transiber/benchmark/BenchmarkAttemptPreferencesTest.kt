package com.sal7one.transiber.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkAttemptPreferencesTest {
    @Test fun oldBudgetsMigrateToAnAvailablePresetWithoutRaisingTheLimit() {
        assertEquals(listOf(5, 10, 20), benchmarkAttemptTimeoutPresets)
        assertEquals(20, benchmarkAttemptPresetSeconds(999))
        assertEquals(20, benchmarkAttemptPresetSeconds(45))
        assertEquals(20, benchmarkAttemptPresetSeconds(30))
        assertEquals(10, benchmarkAttemptPresetSeconds(15))
        assertEquals(10, benchmarkAttemptPresetSeconds(10))
        assertEquals(5, benchmarkAttemptPresetSeconds(5))
        assertEquals(5, benchmarkAttemptPresetSeconds(-1))
    }
}

package com.sal7one.transiber.benchmark

import org.junit.Assert.*
import org.junit.Test

class BenchmarkEnvironmentTest {
    @Test fun startAndEndSamplesRoundTripWithoutPretendingToBePeakOrModelMemory() {
        val environment = BenchmarkEnvironment(BenchmarkEnvironmentSnapshot(85, 32.5, 0, 4_000_000_000, false, 100_000_000),
            BenchmarkEnvironmentSnapshot(83, 35.2, 2, 3_000_000_000, false, 700_000_000))
        assertEquals(environment, BenchmarkEnvironment.from(environment.json()))
        val result = BenchmarkResult("run", 1, "model", "model", "speech", "hash", "en", "", 1000, 1.0,
            listOf(1.0, 1.0, 1.0), listOf("spoken"), "CPU", listOf(1.0), "phone", environment = environment)
        assertEquals(environment, BenchmarkResult.from(result.json()).environment)
        assertFalse(result.json().toString().contains("peakMemory"))
    }

    @Test fun unavailablePlatformMeasurementsRemainUnknownAndLegacyResultsRead() {
        val empty = BenchmarkEnvironmentSnapshot()
        assertEquals(empty, BenchmarkEnvironmentSnapshot.from(empty.json()))
        val result = BenchmarkResult("run", 1, "model", "model", "speech", "hash", "en", "", 1000, 1.0,
            listOf(1.0, 1.0, 1.0), listOf("spoken"), "CPU", listOf(1.0), "phone")
        val old = result.json().apply { remove("environment"); remove("audioMsByPass") }
        assertNull(BenchmarkResult.from(old).environment)
        assertTrue(BenchmarkResult.from(old).audioMsByPass.isEmpty())
    }
}

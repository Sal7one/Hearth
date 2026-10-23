package com.sal7one.transiber.benchmark

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BenchmarkDiscoveryTest {
    @Test fun oneDamagedModelDoesNotHideTheRestAndRetainsItsActualError() {
        val scan = BenchmarkDiscovery()
        val ready = listOf("first", "missing", "third").mapNotNull { id -> scan.inspect(id) {
            if(id == "missing") throw IOException("Pinned model file is missing")
            id
        } }
        assertEquals(listOf("first","third"),ready)
        assertEquals(listOf("missing: Pinned model file is missing"),scan.issues)
    }
    @Test fun cancellingDiscoveryDoesNotBecomeAnEmptySuccessfulScan() {
        val scan = BenchmarkDiscovery()
        assertThrows(CancellationException::class.java) { scan.inspect("cancelled") {throw CancellationException("stop")} }
        assertTrue(scan.issues.isEmpty())
    }
}

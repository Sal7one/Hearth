package com.sal7one.transiber.runtime

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class LocalWorkGateTest {
    @Test fun preventsOverlappingLoadsAndStaleReleaseCannotUnlockNextOwner() {
        val captions = LocalWorkGate.acquire("Captions")
        try { assertThrows(IllegalStateException::class.java) { LocalWorkGate.acquire("Benchmark") } }
        finally { captions.close() }
        val benchmark = LocalWorkGate.acquire("Benchmark")
        try {
            captions.close()
            assertEquals("Benchmark", LocalWorkGate.owner.value)
            assertThrows(IllegalStateException::class.java) { LocalWorkGate.acquire("Conversation") }
        } finally { benchmark.close() }
        assertNull(LocalWorkGate.owner.value)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun overlayHandoffWaitsForCleanupAndCancelledWaitNeverReleasesAnotherOwner()=runTest {
        val captions=LocalWorkGate.acquire("Captions")
        try {
            val abandoned=launch {LocalWorkGate.awaitIdle()}
            val reading=async {LocalWorkGate.awaitIdle();LocalWorkGate.acquire("Camera OCR")}
            runCurrent();assertFalse(reading.isCompleted)
            abandoned.cancelAndJoin();assertEquals("Captions",LocalWorkGate.owner.value)
            captions.close();runCurrent()
            val lease=reading.await()
            try {assertEquals("Camera OCR",LocalWorkGate.owner.value);captions.close();assertEquals("Camera OCR",LocalWorkGate.owner.value)}
            finally {lease.close()}
        } finally {captions.close()}
    }
}

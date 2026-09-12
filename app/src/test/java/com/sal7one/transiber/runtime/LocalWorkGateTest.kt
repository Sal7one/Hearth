package com.sal7one.transiber.runtime

import org.junit.Assert.*
import org.junit.Test

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
}

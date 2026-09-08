package com.sal7one.common_jni.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OneShotPcm16ProbeTest {
    @Test
    fun arrayProbeStopsAtExactRequestedPrefix() = runBlocking {
        val probe = OneShotPcm16Probe()
        val result = probe.request(5)

        probe.append(shortArrayOf(1, 2))
        probe.append(shortArrayOf(3, 4, 5, 6))
        probe.append(shortArrayOf(7, 8))

        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5), result.await())
    }

    @Test
    fun directProbeReadsNativeEndianPcmWithoutAnIntermediateArray() = runBlocking {
        val buffer = ByteBuffer.allocateDirect(10).order(ByteOrder.nativeOrder())
        buffer.asShortBuffer().put(shortArrayOf(11, 12, 13, 14, 15))
        val probe = OneShotPcm16Probe()
        val result = probe.request(3)

        probe.append(buffer, byteOffset = 2, byteCount = 8)

        assertArrayEquals(shortArrayOf(12, 13, 14), result.await())
    }

    @Test
    fun replacementAndCancellationCompletePendingRequestsWithNull() = runBlocking {
        val probe = OneShotPcm16Probe()
        val replaced = probe.request(4)
        val cancelled = probe.request(2)

        assertNull(replaced.await())
        probe.cancel()
        assertNull(cancelled.await())
    }

    @Test
    fun requestHasAHardAllocationCeiling() {
        val probe = OneShotPcm16Probe()
        assertTrue(runCatching { probe.request(0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(
            runCatching { probe.request(1_000_001) }.exceptionOrNull() is IllegalArgumentException,
        )
    }
}

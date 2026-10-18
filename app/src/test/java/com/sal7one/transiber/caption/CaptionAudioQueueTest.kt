package com.sal7one.transiber.caption

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptionAudioQueueTest {
    @Test fun normalFinishDrainsOrderedCopiedSamplesAndHonorsPartialRecorderReads() = runBlocking {
        val queue = CaptionAudioQueue(8)
        val reused = shortArrayOf(-32768, 32767, 99, 99)
        assertTrue(queue.tryAccept(reused, 2))
        reused.fill(0)
        assertTrue(queue.tryAccept(shortArrayOf(3, 4, 5), 3))
        queue.finish()
        assertFalse(queue.tryAccept(shortArrayOf(6), 1))
        val spoken = mutableListOf<Short>()
        queue.consume { spoken += it.toList() }
        assertEquals(listOf<Short>(-32768, 32767, 3, 4, 5), spoken)
        assertEquals(0, queue.pendingSamples)
        assertNull(queue.failure)
    }

    @Test fun slowInferenceIsInsideBudgetAndOverflowNeverReplacesAnAcceptedFrame() = runBlocking {
        val queue = CaptionAudioQueue(8)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val processed = mutableListOf<Short>()
        assertTrue(queue.tryAccept(shortArrayOf(1, 2, 3, 4), 4))
        val consumer = launch {
            queue.consume {
                processed += it.toList()
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        assertTrue(queue.tryAccept(shortArrayOf(5, 6, 7, 8), 4))
        assertFalse(queue.tryAccept(shortArrayOf(9), 1))
        assertTrue(queue.failure!!.message!!.contains("8 samples pending; queue limit 8"))
        assertFalse(queue.tryAccept(shortArrayOf(10), 1))
        finish.complete(Unit)
        consumer.join()
        assertEquals(listOf<Short>(1, 2, 3, 4), processed)
        assertEquals(0, queue.pendingSamples)
    }

    @Test fun explicitCancelDiscardsWaitingFramesAndDoesNotInventFailure() = runBlocking {
        val queue = CaptionAudioQueue(8)
        queue.tryAccept(shortArrayOf(1, 2, 3), 3)
        queue.cancel()
        assertEquals(0, queue.pendingSamples)
        assertNull(queue.failure)
        assertFalse(queue.tryAccept(shortArrayOf(4), 1))
        val consumer = launch { queue.consume { fail("Cancelled audio reached inference") } }
        consumer.join()
    }

    @Test fun engineFailureIsPreservedAndPendingInputIsReleased() = runBlocking {
        val queue = CaptionAudioQueue(8)
        queue.tryAccept(shortArrayOf(1, 2), 2)
        queue.tryAccept(shortArrayOf(3, 4), 2)
        val failure = IllegalStateException("actual provider/decoder failure")
        try { queue.consume { throw failure }; fail("Expected engine failure") }
        catch (error: IllegalStateException) { assertSame(failure, error) }
        assertSame(failure, queue.failure)
        assertEquals(0, queue.pendingSamples)
        assertFalse(queue.tryAccept(shortArrayOf(5), 1))
    }

    @Test fun malformedFrameFailsBeforeAdmissionAndNewTimelineCanStart() = runBlocking {
        for (count in listOf(0, 3, 16001)) {
            val old = CaptionAudioQueue()
            assertFalse(old.tryAccept(shortArrayOf(1, 2), count))
            assertTrue(old.failure is IllegalArgumentException)
            assertEquals(0, old.pendingSamples)
        }
        val fresh = CaptionAudioQueue()
        assertTrue(fresh.tryAccept(shortArrayOf(7), 1))
        fresh.finish()
        fresh.consume { assertArrayEquals(shortArrayOf(7), it) }
        assertNull(fresh.failure)
    }
}

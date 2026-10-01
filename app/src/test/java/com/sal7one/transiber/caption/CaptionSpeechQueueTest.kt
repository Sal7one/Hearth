package com.sal7one.transiber.caption

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CaptionSpeechQueueTest {
    @Test fun stopsRequestsBeforeTheWorkerStartsAndRejectsRequestsAfterClose() = runTest {
        val spoken = mutableListOf<String>()
        val queue = CaptionSpeechQueue<String>(this, { error(it) }) { spoken += it }
        assertTrue(queue.offer("old"))
        queue.stop()
        runCurrent()
        assertTrue(spoken.isEmpty())
        queue.close()
        assertFalse(queue.offer("closed"))
    }

    @Test fun queueIsBoundedAndStopAllowsANewGenerationToRun() = runTest {
        val spoken = mutableListOf<String>()
        val queue = CaptionSpeechQueue<String>(this, { error(it) }) {
            spoken += it
            if (it == "active") awaitCancellation()
        }
        queue.offer("active"); runCurrent()
        repeat(3) { assertTrue(queue.offer("queued-$it")) }
        assertFalse(queue.offer("overflow"))
        queue.stop()
        assertTrue(queue.offer("fresh"))
        runCurrent()
        assertEquals(listOf("active", "fresh"), spoken)
        queue.close()
    }

    @Test fun failedRequestSurfacesTheActualErrorAndDoesNotKillFollowingSpeech() = runTest {
        val errors = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val queue = CaptionSpeechQueue<String>(this, errors::add) {
            if (it == "bad") error("provider returned HTTP 429") else spoken += it
        }
        queue.offer("bad"); queue.offer("good"); runCurrent()
        assertEquals(listOf("provider returned HTTP 429"), errors)
        assertEquals(listOf("good"), spoken)
        queue.close()
    }

    @Test fun staleFailureAfterStopCannotOverwriteANewSession() = runTest {
        val release = CompletableDeferred<Unit>()
        val errors = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val queue = CaptionSpeechQueue<String>(this, errors::add) {
            if (it == "old") {
                withContext(NonCancellable) { release.await() }
                error("old request failed after cancellation")
            } else spoken += it
        }
        queue.offer("old"); runCurrent()
        queue.stop(); queue.offer("new"); release.complete(Unit); runCurrent()
        assertTrue(errors.isEmpty())
        assertEquals(listOf("new"), spoken)
        queue.close()
    }

    @Test fun perRequestPreferencesAreKeptInQueueOrder() = runTest {
        data class VoiceRequest(val text: String, val voice: String, val volume: Float)
        val processed = mutableListOf<VoiceRequest>()
        val queue = CaptionSpeechQueue<VoiceRequest>(this, { error(it) }) { processed += it }
        val first = VoiceRequest("first", "nova", .25f)
        val second = VoiceRequest("second", "echo", .8f)
        queue.offer(first); queue.offer(second); runCurrent()
        assertEquals(listOf(first, second), processed)
        queue.close()
    }
}

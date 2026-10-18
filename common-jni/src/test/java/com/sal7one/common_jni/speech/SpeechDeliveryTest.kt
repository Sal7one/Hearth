package com.sal7one.common_jni.speech

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Result availability is event-driven; timed metric refreshes never consume finals. */
class SpeechDeliveryTest {
    private class Driver(var text: String? = "final") : SpeechDriver {
        var accepted = 0L
        var id = 0L
        val destroyed = AtomicInteger()
        override fun probe(backend: String) = error("Not used")
        override fun create(config: String) = error("Not used")
        private fun update(value: String?): String {
            val events = JSONArray()
            if (value != null) events.put(JSONObject().put("utteranceId", ++id).put("revision", 1)
                .put("text", value).put("language", "en").put("final", true).put("audioEndSamples", accepted))
            return JSONObject().put("events", events).put("acceptedSamples", accepted).toString()
        }
        override fun push(handle: Long, buffer: ByteBuffer, byteOffset: Int, byteCount: Int, sampleOffset: Long): String {
            accepted += byteCount / 2
            return update(text)
        }
        override fun finish(handle: Long) = update(null)
        override fun reset(handle: Long) { accepted = 0 }
        override fun destroy(handle: Long) { destroyed.incrementAndGet() }
    }
    private fun processor(driver: Driver) = LiveSpeechProcessor(
        SpeechSession(driver, 1, SpeechProfile.PHONON_2))

    @Test fun firstCompletedResultWakesAnAlreadyWaitingConsumerWithoutAnotherAudioFrame() = runBlocking {
        val driver = Driver()
        val processor = processor(driver)
        try {
            val initial = processor.snapshot()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { processor.awaitChange(initial.revision) }
            assertFalse(waiting.isCompleted)
            assertTrue(processor.tryAccept(shortArrayOf(1)))
            withTimeout(5000) { waiting.await() }
            val ready = processor.snapshot()
            assertTrue(ready.revision > initial.revision)
            assertEquals(listOf("final"), ready.finals.map { it.text })
            assertTrue(processor.snapshot().finals.isEmpty())
        } finally { processor.close() }
        assertEquals(1, driver.destroyed.get())
    }

    @Test fun cancelledNotificationWaitCannotRemoveFinalText() = runBlocking {
        val processor = processor(Driver())
        try {
            val initial = processor.snapshot()
            val notified = CompletableDeferred<Unit>()
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                processor.awaitChange(initial.revision)
                notified.complete(Unit)
                awaitCancellation() // Metric-refresh timeout / consumer replacement.
            }
            processor.tryAccept(shortArrayOf(1))
            withTimeout(5000) { notified.await() }
            waiter.cancelAndJoin()
            assertEquals(listOf("final"), processor.snapshot().finals.map { it.text })
        } finally { processor.close() }
    }

    @Test fun conflatedWakeupsRetainEveryDistinctFinalIncludingRepeatedText() = runBlocking {
        val processor = processor(Driver())
        try {
            val initial = processor.snapshot()
            processor.tryAccept(shortArrayOf(1))
            processor.tryAccept(shortArrayOf(2))
            processor.finish()
            withTimeout(5000) { while (processor.state.value == SpeechProcessorState.Running) yield() }
            processor.awaitChange(initial.revision)
            val ready = processor.snapshot()
            assertEquals(listOf("final", "final"), ready.finals.map { it.text })
            assertEquals(2, ready.finals.map { it.utteranceId }.toSet().size)
            assertEquals(SpeechProcessorState.Finished, ready.state)
            assertTrue(processor.snapshot().finals.isEmpty())
        } finally { processor.close() }
    }

    @Test fun emptyAudioUpdatesDoNotWakeTextConsumerButCompletionDoes() = runBlocking {
        val processor = processor(Driver(null))
        try {
            val initial = processor.snapshot()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { processor.awaitChange(initial.revision) }
            processor.tryAccept(shortArrayOf(0))
            withTimeout(5000) { while (processor.snapshot().processedSamples == 0L) yield() }
            assertFalse(waiting.isCompleted)
            processor.finish()
            withTimeout(5000) { waiting.await() }
            assertEquals(SpeechProcessorState.Finished, processor.snapshot().state)
        } finally { processor.close() }
    }

    @Test fun failureAndCloseWakeConsumersWithoutWaitingForPollingTimeout() = runBlocking {
        val failed = processor(Driver())
        try {
            val before = failed.snapshot()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { failed.awaitChange(before.revision) }
            assertFalse(failed.tryAccept(shortArrayOf()))
            withTimeout(5000) { waiting.await() }
            assertTrue(failed.snapshot().state is SpeechProcessorState.Failed)
        } finally { failed.close() }
        val closed = processor(Driver())
        val before = closed.snapshot()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { closed.awaitChange(before.revision) }
        closed.close()
        withTimeout(5000) { waiting.await() }
        assertEquals(SpeechProcessorState.Closed, closed.snapshot().state)
    }

    @Test fun completedFinalSurvivesALaterFailureBeforeTheConsumerDrainsIt() = runBlocking {
        val processor = processor(Driver())
        try {
            val before = processor.snapshot()
            assertTrue(processor.tryAccept(shortArrayOf(1)))
            withTimeout(5000) { processor.awaitChange(before.revision) }
            assertFalse(processor.tryAccept(shortArrayOf()))
            val failed = processor.snapshot()
            assertTrue(failed.state is SpeechProcessorState.Failed)
            assertEquals(listOf("final"), failed.finals.map { it.text })
            assertNull(failed.partial)
            assertTrue(processor.snapshot().finals.isEmpty())
        } finally { processor.close() }
    }
}

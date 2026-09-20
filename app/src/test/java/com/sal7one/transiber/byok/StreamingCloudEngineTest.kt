package com.sal7one.transiber.byok

import com.sal7one.common_jni.model.AudioChunk
import com.sal7one.common_jni.model.SttConfig
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class StreamingCloudEngineTest {
    private class FakeClient(val connectAction: FakeClient.() -> Unit = { onConnected?.invoke() }) : StreamingSttClient {
        override var onInterim: ((String) -> Unit)? = null
        override var onFinal: ((String) -> Unit)? = null
        override var onError: ((String) -> Unit)? = null
        override var onConnected: (() -> Unit)? = null
        val frames = ConcurrentLinkedQueue<ShortArray>()
        var sendAction: (ShortArray) -> Unit = { frames.add(it) }
        var closeAction: () -> Unit = {}
        var closes = 0
        var endedAfterFrames = -1
        var closed = false
        var connected = false
        override fun connect() { connected = true; connectAction() }
        override fun sendPcm(pcm: ShortArray) = sendAction(pcm)
        override fun onCaptureEnded() { endedAfterFrames = frames.size }
        override fun close() { closed = true; closes++; closeAction() }
    }
    @Test fun silenceTravelsAndExplicitFinalsRemainSeparate() = runBlocking {
        val client = FakeClient()
        val engine = StreamingCloudEngine(client, "Test")
        val init = engine.initialize("", SttConfig.forStreaming())
        if (!ByokPolicy.FEATURE_BYOK) {
            assertTrue(init.isFailure); assertFalse(client.connected); return@runBlocking
        }
        try {
            init.getOrThrow()
            engine.pushAudioChunk(AudioChunk(ShortArray(800), 16000, 0, 50)).getOrThrow()
            withTimeout(1000) { while (client.frames.isEmpty()) delay(5) }
            assertEquals(800, client.frames.first().size)
            assertTrue(client.frames.first().all { it == 0.toShort() })
            client.onInterim?.invoke("正在")
            assertEquals("正在", engine.getPartialTranscript()!!.text)
            client.onFinal?.invoke("正在直播。")
            assertNull(engine.getPartialTranscript())
            assertEquals(listOf("正在直播。"), engine.takeFinals())
            assertTrue(engine.takeFinals().isEmpty())
        } finally { engine.release() }
        assertTrue(client.closed)
    }
    @Test fun snapshotCannotResurrectThePartialOfAFinishedTurn() = runBlocking {
        if (!ByokPolicy.FEATURE_BYOK) return@runBlocking
        val client = FakeClient()
        val engine = StreamingCloudEngine(client, "Test")
        try {
            engine.initialize("", SttConfig.forStreaming()).getOrThrow()
            client.onInterim?.invoke("old partial")
            client.onFinal?.invoke("settled sentence")
            client.onInterim?.invoke("next")
            val snapshot = engine.takeSnapshot()
            assertEquals(listOf("settled sentence"), snapshot.finals)
            assertEquals("next", snapshot.partial)
            assertTrue(engine.takeSnapshot().finals.isEmpty())
        } finally { engine.release() }
    }
    @Test fun serverErrorFailsInitializationVerbatim() = runBlocking {
        val client = FakeClient { onError?.invoke("quota_exceeded: test quota") }
        val engine = StreamingCloudEngine(client, "Test")
        try {
            val result = engine.initialize("", SttConfig.forStreaming())
            assertTrue(result.isFailure)
            if (ByokPolicy.FEATURE_BYOK) assertEquals("quota_exceeded: test quota", result.exceptionOrNull()!!.message)
        } finally { engine.release() }
    }
    @Test fun missingSessionAckTimesOutAndClosesSocket() = runBlocking {
        val client = FakeClient { }
        val engine = StreamingCloudEngine(client, "Test", connectTimeoutMs = 20)
        try {
            val result = engine.initialize("", SttConfig.forStreaming())
            assertTrue(result.isFailure)
            if (ByokPolicy.FEATURE_BYOK) {
                assertTrue(result.exceptionOrNull()!!.message!!.contains("connection timed out"))
                assertTrue(client.closed)
            }
        } finally { engine.release() }
    }

    @Test fun retiringWhileSendingCannotPublishSocketRejectionOrLateCallbacks() = runBlocking {
        if (!ByokPolicy.FEATURE_BYOK) return@runBlocking
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val unblock = java.util.concurrent.CountDownLatch(1)
        val client = FakeClient()
        client.sendAction = {
            entered.complete(Unit)
            try {
                check(unblock.await(3, java.util.concurrent.TimeUnit.SECONDS))
                error("Transcription socket rejected audio")
            } finally { finished.complete(Unit) }
        }
        client.closeAction = { unblock.countDown(); client.onError?.invoke("socket cancelled") }
        val engine = StreamingCloudEngine(client, "OpenAI")
        try {
            engine.initialize("", SttConfig.forStreaming()).getOrThrow()
            engine.pushAudioChunk(AudioChunk(ShortArray(800), 16000, 0, 50)).getOrThrow()
            withTimeout(2000) { entered.await() }
            engine.release()
            withTimeout(2000) { finished.await() }
            client.onConnected?.invoke()
            client.onFinal?.invoke("obsolete")
            client.onInterim?.invoke("obsolete")
            assertFalse(engine.isInitialized)
            assertNull(engine.takeSnapshot().error)
            assertTrue(engine.takeFinals().isEmpty())
            assertNull(engine.getPartialTranscript())
        } finally { unblock.countDown(); engine.release() }
        assertEquals(1, client.closes)
    }

    @Test fun providerCauseSurvivesSendFailureAndClearingText() = runBlocking {
        if (!ByokPolicy.FEATURE_BYOK) return@runBlocking
        val client = FakeClient()
        val sent = CompletableDeferred<Unit>()
        client.sendAction = {
            client.onError?.invoke("quota_exceeded: test quota")
            sent.complete(Unit)
            error("Transcription socket rejected audio")
        }
        val engine = StreamingCloudEngine(client, "OpenAI")
        try {
            engine.initialize("", SttConfig.forStreaming()).getOrThrow()
            engine.pushAudioChunk(AudioChunk(ShortArray(800), 16000, 0, 50)).getOrThrow()
            withTimeout(2000) { sent.await() }
            val expected = "OpenAI streaming: quota_exceeded: test quota"
            assertEquals(expected, engine.takeSnapshot().error)
            engine.reset().getOrThrow()
            assertFalse(engine.isInitialized)
            assertEquals(expected, engine.pushAudioChunk(AudioChunk(ShortArray(800), 16000, 0, 50)).exceptionOrNull()?.message)
        } finally { engine.release() }
    }

    @Test fun genuineSendFailureStopsAdmissionAndLaterProviderCauseRemainsVisible() = runBlocking {
        if (!ByokPolicy.FEATURE_BYOK) return@runBlocking
        val client = FakeClient()
        client.sendAction = { error("Transcription socket rejected audio") }
        val engine = StreamingCloudEngine(client, "OpenAI")
        try {
            engine.initialize("", SttConfig.forStreaming()).getOrThrow()
            engine.pushAudioChunk(AudioChunk(ShortArray(800), 16000, 0, 50)).getOrThrow()
            withTimeout(2000) { while (engine.isInitialized) delay(5) }
            assertEquals("stream send: Transcription socket rejected audio", engine.takeSnapshot().error)
            assertTrue(engine.pushAudioChunk(AudioChunk(ShortArray(800), 16000, 0, 50)).isFailure)
            client.onError?.invoke("HTTP 403: exact provider cause")
            client.onError?.invoke("secondary close")
            assertEquals("OpenAI streaming: HTTP 403: exact provider cause", engine.takeSnapshot().error)
            assertEquals("OpenAI streaming: HTTP 403: exact provider cause", engine.finalize().exceptionOrNull()?.message)
        } finally { engine.release() }
    }

    @Test fun releasingPendingConnectionCannotResurrectIt() = runBlocking {
        if (!ByokPolicy.FEATURE_BYOK) return@runBlocking
        val connecting = CompletableDeferred<Unit>()
        val client = FakeClient { connecting.complete(Unit) }
        val engine = StreamingCloudEngine(client, "OpenAI")
        val startup = async { runCatching { engine.initialize("", SttConfig.forStreaming()) } }
        withTimeout(2000) { connecting.await() }
        engine.release()
        client.onConnected?.invoke()
        withTimeout(2000) { assertTrue(startup.await().isFailure) }
        assertFalse(engine.isInitialized)
        assertEquals(1, client.closes)
        assertNull(engine.takeSnapshot().error)
    }

    @Test fun captureEndDrainsEngineQueueBeforeClosingProviderInput() = runBlocking {
        if (!ByokPolicy.FEATURE_BYOK) return@runBlocking
        val client = FakeClient()
        val engine = StreamingCloudEngine(client, "OpenAI")
        try {
            engine.initialize("", SttConfig.forStreaming()).getOrThrow()
            repeat(3) { engine.pushAudioChunk(AudioChunk(shortArrayOf(it.toShort()), 16000, 0, 1)).getOrThrow() }
            engine.onCaptureEnded()
            assertEquals(3, client.endedAfterFrames)
            assertEquals(listOf<Short>(0,1,2), client.frames.map { it.single() })
            client.onFinal?.invoke("trailing words")
            assertEquals(listOf("trailing words"), engine.takeSnapshot().finals)
            assertNull(engine.takeSnapshot().error)
        } finally { engine.release() }
    }
}

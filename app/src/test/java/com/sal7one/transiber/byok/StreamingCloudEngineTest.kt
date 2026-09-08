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
        var closed = false
        var connected = false
        override fun connect() { connected = true; connectAction() }
        override fun sendPcm(pcm: ShortArray) { frames.add(pcm) }
        override fun close() { closed = true }
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
}

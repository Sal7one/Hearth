package com.sal7one.transiber.byok

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Tests the dedicated transcription socket and its 16-to-24 kHz PCM conversion.
 */
class OpenAiRealtimeClientTest {

    private val client = OpenAiRealtimeClient(apiKey = "test-key-does-not-connect")

    @Test
    fun `dedicated transcription commits after quiet and at capture end`() {
        if (!ByokPolicy.FEATURE_BYOK) return
        val received = LinkedBlockingQueue<String>()
        val ready = CountDownLatch(1)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val type = JSONObject(text).getString("type")
                    received.add(type)
                    if (type == "session.update") webSocket.send("""{"type":"session.updated"}""")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            }))
            val realtime = OpenAiRealtimeClient("fixture-key", endpoint = server.url("/v1/realtime?intent=transcription").toString())
            try {
                realtime.onConnected = { ready.countDown() }
                realtime.connect()
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                assertEquals("/v1/realtime?intent=transcription", server.takeRequest(5, TimeUnit.SECONDS)?.path)
                realtime.sendPcm(ShortArray(8_000))
                realtime.sendPcm(ShortArray(3_200) { 1_000 })
                realtime.sendPcm(ShortArray(8_000))
                realtime.sendPcm(ShortArray(800) { 1_000 })
                realtime.onCaptureEnded()
                val append = "input_audio_buffer.append"
                val commit = "input_audio_buffer.commit"
                assertEquals(
                    listOf("session.update", append, append, commit, append, append, commit),
                    List(7) { received.poll(5, TimeUnit.SECONDS) },
                )
            } finally {
                realtime.close()
            }
        }
    }

    @Test
    fun `resamples 16k to 24k at the 3-2 ratio`() {
        val input = shortArrayOf(0, 1000, 2000, 3000)
        val out = client.resample16kTo24k(input)
        assertEquals(input.size * 3 / 2, out.size)
    }

    @Test
    fun `linear interpolation lands on grid points`() {
        val input = shortArrayOf(0, 1000, 2000, 3000)
        val out = client.resample16kTo24k(input)
        // pos = i*2/3: 0, 0.667, 1.333, 2.0, 2.667, 3.333→clamped to last;
        // Double.toInt truncates toward zero (666, 2666 — not rounded up).
        val expected = intArrayOf(0, 666, 1333, 2000, 2666, 3000)
        for (i in expected.indices) {
            assertEquals(
                "sample $i",
                expected[i],
                out[i].toInt(),
            )
        }
    }

    @Test
    fun `single-sample input is padded without crashing`() {
        val out = client.resample16kTo24k(shortArrayOf(42))
        assertEquals(1, out.size)
        assertEquals(42, out[0].toInt())
    }

    @Test
    fun `silence stays silence`() {
        val out = client.resample16kTo24k(ShortArray(1600))
        assertEquals(2400, out.size)
        assertEquals(0, out.sum().toInt())
    }
}

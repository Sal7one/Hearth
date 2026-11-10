package com.sal7one.transiber.byok

import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LiveCaptionPipelineTest {
    @Test fun translationPreservesWhitespaceTokensAndArabic() {
        val t = TranslationTranscript()
        t.append("مرحبا"); t.append(" "); t.append("بالعالم")
        assertEquals("مرحبا بالعالم", t.partial)
        assertEquals(listOf("مرحبا بالعالم!"), t.append("!"))
        assertEquals("", t.partial)
    }
    @Test fun continuousChineseIsBoundedWithoutSplittingEmoji() {
        val t = TranslationTranscript(12)
        val input = "你好世界你好世界你好世😀界你好世界你好世界"
        val finals = t.append(input)
        val output = finals.joinToString("") + t.flush()
        assertEquals(input, output)
        finals.forEach { assertFalse(Character.isHighSurrogate(it.last())) }
        assertTrue(finals.all { it.length <= 12 })
    }
    @Test fun translationFlushesTailOnce() {
        val t = TranslationTranscript()
        t.append("a translated phrase")
        assertEquals("a translated phrase", t.flush())
        assertEquals("", t.flush())
    }
    @Test fun translationWaitsForProviderCloseAndPublishesItsTail() {
        if (!ByokPolicy.FEATURE_BYOK) return
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val finishingSocket = LinkedBlockingQueue<WebSocket>()
        val finals = LinkedBlockingQueue<String>()
        val errors = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (JSONObject(text).getString("type")) {
                        "session.update" -> webSocket.send("""{"type":"session.updated"}""")
                        "session.close" -> finishingSocket.add(webSocket)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.countDown()
                }
            }))
            val client = OpenAiTranslateClient("fixture-key", "ar", server.url("/v1/realtime/translations").toString())
            try {
                client.onConnected = { ready.countDown() }
                client.onFinal = { finals.add(it) }
                client.onError = { errors.add(it) }
                client.connect()
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                client.onCaptureEnded()
                val provider = checkNotNull(finishingSocket.poll(5, TimeUnit.SECONDS))
                assertTrue(client.awaitingEndOfAudio)
                assertTrue(provider.send("""{"type":"session.output_transcript.delta","delta":"مرحبا بالعالم"}"""))
                assertTrue(provider.send("""{"type":"session.closed"}"""))
                assertTrue(provider.close(1000, null))
                assertTrue(closed.await(5, TimeUnit.SECONDS))
                assertFalse(client.awaitingEndOfAudio)
                assertEquals("مرحبا بالعالم", finals.poll(5, TimeUnit.SECONDS))
                assertTrue(finals.isEmpty())
                assertTrue(errors.isEmpty())
            } finally {
                client.close()
            }
        }
    }
    @Test fun sourceItemsDoNotMixWhenFinalsArriveInReverseOrder() {
        val t = RealtimeTranscript()
        t.begin("a"); t.append("a", "Первый")
        t.begin("b"); t.append("b", "第二")
        t.append("a", " текст")
        assertEquals("第二", t.partial)
        assertTrue(t.finish("b", "第二句话").isEmpty())
        assertEquals(listOf("Первый текст", "第二句话"), t.finish("a", "Первый текст"))
        assertTrue(t.finish("a", "Первый текст").isEmpty())
    }
    @Test fun identicalSentencesFromDifferentItemsAreBothKept() {
        val t = RealtimeTranscript()
        assertEquals(listOf("yes"), t.finish("a", "yes"))
        assertEquals(listOf("yes"), t.finish("b", "yes"))
    }
    @Test fun streamingCcUsesDedicatedTranscriptionWithManualCommits() {
        val session = OpenAiRealtimeClient.sessionUpdate("ru").getJSONObject("session")
        val input = session.getJSONObject("audio").getJSONObject("input")
        assertEquals("transcription", session.getString("type"))
        assertTrue(input.isNull("turn_detection"))
        assertEquals("gpt-live-transcribe", input.getJSONObject("transcription").getString("model"))
        assertEquals("ru", input.getJSONObject("transcription").getJSONArray("languages").getString(0))
    }
    @Test fun silenceCompletesShortBatchBeforeHardCap() {
        val b = UtteranceBuffer(16000, maxMs = 4000)
        repeat(20) { assertNull(b.push(ShortArray(800) { 1000 }, 16000, true)) }
        repeat(12) { assertNull(b.push(ShortArray(800), 16000, false)) }
        assertEquals(26400, b.push(ShortArray(800), 16000, false)!!.size)
        assertNull(b.flush())
    }
    @Test fun continuousSpeechHasFourSecondLatencyBudget() {
        val b = UtteranceBuffer(16000, maxMs = 4000)
        repeat(79) { assertNull(b.push(ShortArray(800) { 1000 }, 16000, true)) }
        assertEquals(64000, b.push(ShortArray(800) { 1000 }, 16000, true)!!.size)
    }
}

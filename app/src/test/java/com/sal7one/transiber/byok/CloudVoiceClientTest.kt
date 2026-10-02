package com.sal7one.transiber.byok

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CloudVoiceClientTest {
    private fun fixture(server: MockWebServer): CloudVoiceClient {
        val base = server.url("/").toString().trimEnd('/')
        return CloudVoiceClient { url -> java.net.URL(base + url.file).openConnection() as HttpURLConnection }
    }
    @Test fun googleAuthAndRestResponseRoundTripWithoutPaidCalls() {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        MockWebServer().use { server ->
            val wav=WavEncoder.wrapPcm16(byteArrayOf(1,0),24000)
            val base64=java.util.Base64.getEncoder().encodeToString(wav)
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"steps":[{"type":"model_output","content":[{"type":"audio","mime_type":"audio/wav","data":"$base64"}]}]}"""))
            assertArrayEquals(wav,fixture(server).speak(CloudVoiceConfig(CloudVoiceProvider.GEMINI),"fixture-key","hello").bytes)
            val request=server.takeRequest()
            assertEquals("fixture-key",request.getHeader("x-goog-api-key")); assertNull(request.getHeader("Authorization"))
            assertEquals("/v1beta/interactions",request.path)
        }
    }
    @Test fun activeNetworkStopCancelsAndRedirectNeverForwardsKey() {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(256))).setHeadersDelay(2,TimeUnit.SECONDS))
            val client=fixture(server); val executor=Executors.newSingleThreadExecutor()
            try {
                val future=executor.submit<Boolean> {
                    try { client.speak(CloudVoiceConfig(),"fixture-key","hello"); false }
                    catch(_: CancellationException) { true }
                }
                val received = server.takeRequest(5,TimeUnit.SECONDS)
                if (received == null && future.isDone) future.get()
                assertNotNull(received)
                val start = System.nanoTime(); client.cancel()
                assertTrue("Stop blocked its caller", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
                assertTrue(future.get(5,TimeUnit.SECONDS))
            } finally { executor.shutdownNow() }
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://other.example/"))
            assertThrows(ByokHttpException::class.java) { fixture(server).speak(CloudVoiceConfig(),"fixture-key","hello") }
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun boundJsonAndRedactSuccessfulErrorBody() {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"error":{"message":"invalid fixture-key"}}"""))
            val error=assertThrows(IllegalStateException::class.java) { fixture(server).speak(CloudVoiceConfig(),"fixture-key","hello") }
            assertFalse(error.toString().contains("fixture-key")); assertNull(error.cause)
            server.enqueue(MockResponse().setBody("x".repeat(CloudVoiceProtocol.CATALOG_LIMIT+1)))
            assertThrows(IllegalStateException::class.java) {
                fixture(server).catalog(CloudVoiceProtocol.modelsRequest(CloudVoiceConfig(CloudVoiceProvider.OPENROUTER)),"fixture-key")
            }
        }
    }
    @Test fun fossCannotEvenOpenAConnection() {
        if (ByokPolicy.FEATURE_BYOK) return
        val client=CloudVoiceClient { error("FOSS opened a connection") }
        val e=assertThrows(IllegalStateException::class.java) { client.speak(CloudVoiceConfig(),"fixture","hello") }
        assertEquals("Cloud services are unavailable in the offline build",e.message)
        assertThrows(IllegalStateException::class.java) { client.catalog(CloudVoiceProtocol.voicesRequest(),"fixture") }
    }
}

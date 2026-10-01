package com.sal7one.transiber.byok

import kotlinx.coroutines.CancellationException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Local HTTP fixtures only; no provider account or paid request. */
class CloudTtsClientTest {
    @Test fun cancelledRequestCannotConnect() {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        val client = OpenAiSpeechClient("fixture", "http://127.0.0.1:1/v1")
        client.cancel()
        try {
            client.speak("cancelled")
            fail("cancelled client connected")
        } catch (expected: CancellationException) {
            assertEquals("Read aloud stopped", expected.message)
        }
    }

    @Test fun emptySuccessfulResponseIsAnError() {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(""))
            try {
                OpenAiSpeechClient("fixture", server.url("/v1").toString()).speak("test")
                fail("empty audio must not succeed")
            } catch (expected: IllegalStateException) {
                assertEquals("TTS provider returned empty audio", expected.message)
            }
        }
    }

    @Test fun providerFailureRetainsStatusAndRedactsAnEchoedCredential() {
        assumeTrue(ByokPolicy.FEATURE_BYOK)
        MockWebServer().use { server ->
            val credential = "synthetic-test-credential"
            server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited $credential"))
            try {
                OpenAiSpeechClient(credential, server.url("/v1").toString()).speak("test")
                fail("provider failure must not succeed")
            } catch (expected: ByokHttpException) {
                assertTrue(expected.message.orEmpty().contains("HTTP 429"))
                assertTrue(expected.message.orEmpty().contains("rate limited"))
                assertFalse(expected.message.orEmpty().contains(credential))
            }
        }
    }
}

package com.sal7one.transiber.voice
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
class RemoteVoiceClientTest {
    @Test fun offlineGateDoesNotContactServer()=runBlocking {
        MockWebServer().use {server->RemoteVoiceClient(false).use {client->
            val failure=runCatching {client.execute(Request.Builder().url(server.url("/")).build())}.exceptionOrNull()
            assertTrue(failure!!.message!!.contains("offline build"));assertEquals(0,server.requestCount)
        }}
    }
    @Test fun redactsKeyAndNeverFollowsCredentialRedirect()=runBlocking {
        MockWebServer().use {server->MockWebServer().use {other->RemoteVoiceClient(true).use {client->
            server.enqueue(MockResponse().setResponseCode(429).setBody("provider cause: private-key"))
            val request=Request.Builder().url(server.url("/")).header("Authorization","Bearer private-key").build()
            assertEquals("Voice server HTTP 429: provider cause: <redacted>",runCatching {client.execute(request,"private-key")}.exceptionOrNull()?.message)
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location",other.url("/steal")))
            assertTrue(runCatching {client.execute(request,"private-key")}.isFailure);assertEquals(0,other.requestCount)
        }}}
    }
    @Test fun boundsResponsesAndCancellationClosesOwnership()=runBlocking {
        MockWebServer().use {server->
            val client=RemoteVoiceClient(true);val request=Request.Builder().url(server.url("/")).build()
            server.enqueue(MockResponse().setBody("x".repeat(65537)))
            assertEquals("Voice response exceeds size limit",runCatching {client.execute(request)}.exceptionOrNull()?.message)
            server.takeRequest()
            server.enqueue(MockResponse().setBody("slow").setHeadersDelay(2,TimeUnit.SECONDS))
            val work=launch {client.execute(request)}
            withContext(Dispatchers.IO){assertNotNull(server.takeRequest(1,TimeUnit.SECONDS))}
            withTimeout(1000){work.cancelAndJoin()};client.close()
            assertEquals("Voice connection is closed",runCatching {client.execute(request)}.exceptionOrNull()?.message)
            assertEquals(2,server.requestCount)
        }
    }
}

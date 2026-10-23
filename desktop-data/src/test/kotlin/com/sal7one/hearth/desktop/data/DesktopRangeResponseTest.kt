package com.sal7one.hearth.desktop.data

import com.sun.net.httpserver.HttpServer
import org.junit.Test
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopRangeResponseTest {
    @Test fun rejectedRealHttpResponsesCloseBodiesAndAcceptedRangesRemainReadable() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val status = if (exchange.requestURI.path == "/ignored") 200 else 206
            exchange.responseHeaders.add("Content-Range", if (exchange.requestURI.path == "/wrong") "bytes 1-3/6" else "bytes 0-2/6")
            exchange.sendResponseHeaders(status, 3)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3)) }
        }
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            for (path in listOf("ignored", "wrong", "accepted")) {
                val actual = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}/$path")).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream())
                var closed = false
                val input = object : FilterInputStream(actual.body()) {
                    override fun close() { closed = true; super.close() }
                }
                val tracked = object : HttpResponse<InputStream> by actual { override fun body(): InputStream = input }
                if (path == "accepted") {
                    assertSame(tracked, checkedRangeResponse(tracked, 0, 2, 6))
                    assertFalse(closed)
                    input.use { assertEquals(listOf(1, 2, 3), it.readBytes().map(Byte::toInt)) }
                } else {
                    assertFailsWith<IllegalStateException> { checkedRangeResponse(tracked, 0, 2, 6) }
                    assertTrue(closed, "Rejected $path response must release its HTTP body")
                }
            }
        } finally { server.stop(0) }
    }
}

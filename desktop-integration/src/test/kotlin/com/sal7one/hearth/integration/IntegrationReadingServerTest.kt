package com.sal7one.hearth.integration

import com.sal7one.hearth.core.*
import com.sal7one.hearth.core.vision.OcrRect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** Real loopback sockets exercise the adapter boundary; these fixtures do not claim OCR inference. */
class IntegrationReadingServerTest {
    @Test fun imageServiceAvailabilityIsExplicitAndNeverStartsUnconfiguredInference() {
        val pipeline = Pipeline()
        DesktopIntegrationServer(pipeline, ::config).start().use { server ->
            assertTrue(request(server, "/v1/capabilities").body.contains("Image reading is unavailable"))
            assertEquals(503, request(server, "/v1/reading/image", "POST", png(), "image/png").status)
        }
        val reading = Reading().apply { available = false }
        fixture(reading) { _, server ->
            val unavailable = request(server, "/v1/reading/image", "POST", png(), "image/png")
            assertEquals(409, unavailable.status)
            assertTrue(unavailable.body.contains("Install the selected manga models"))
            assertTrue(reading.requests.isEmpty())
        }
    }

    @Test fun imageUsesSavedTranslationRouteAndExposesProgressiveTextWithCoordinateMapping() = fixture { reading, server ->
        val created = request(server, "/v1/reading/image?targetLanguage=ar", "POST", png(4096, 16), "image/png")
        assertEquals(202, created.status)
        val sent = reading.requests.single()
        assertEquals(2048, sent.image.width); assertEquals(8, sent.image.height)
        assertEquals("ja", sent.config.sourceLanguage); assertEquals("ar", sent.config.targetLanguage)
        assertEquals("translate", sent.config.translation?.engineId)
        assertNull(sent.config.speech); assertNull(sent.config.voice); assertTrue(sent.translate)
        assertTrue(created.body.contains("\"sourceWidth\":4096"))
        assertTrue(created.body.contains("\"width\":2048"))
        assertFalse(created.body.contains("/private/"))
        val session = reading.sessions.single()
        session.state.value = session.state.value.copy(phase = "Translating…", regions = listOf(
            IntegrationReadingRegion("region-0", OcrRect(0, 0, 16, 8), "こんにちは", "مرحبا")))
        await { request(server, "/v1/reading/${session.id}").body.contains("مرحبا") }
        val progress = request(server, "/v1/reading/${session.id}").body
        assertTrue(progress.contains("decoded-image-pixels")); assertTrue(progress.contains("\"bounds\""))
        assertEquals(202, request(server, "/v1/reading/image?translationEnabled=false", "POST", png(), "image/png").status)
        assertFalse(reading.requests.last().translate); assertNull(reading.requests.last().config.translation)
    }

    @Test fun unsupportedLanguagesMimePathsAndMalformedImagesFailBeforeModelCreation() = fixture { reading, server ->
        assertEquals(400, request(server, "/v1/reading/image?sourceLanguage=en", "POST", png(), "image/png").status)
        assertEquals(400, request(server, "/v1/reading/image?modelPath=/private/model", "POST", png(), "image/png").status)
        assertEquals(400, request(server, "/v1/reading/image?voiceEnabled=true", "POST", png(), "image/png").status)
        assertEquals(415, request(server, "/v1/reading/image", "POST", byteArrayOf(1), "image/gif").status)
        assertEquals(400, request(server, "/v1/reading/image", "POST", png(), "image/jpeg").status)
        assertEquals(400, request(server, "/v1/reading/image", "POST", byteArrayOf(1, 2, 3), "image/png").status)
        assertEquals(400, request(server, "/v1/reading/image", "POST", png().copyOf(24), "image/png").status)
        assertTrue(reading.requests.isEmpty())
    }

    @Test fun imageHeaderLimitsPrecedeDecompressionAndOversizedUploadIsRejected() = fixture { reading, server ->
        val enormous = png().also { ByteBuffer.wrap(it).putInt(16, 10_001).putInt(20, 10_001) }
        val rejected = request(server, "/v1/reading/image", "POST", enormous, "image/png")
        assertEquals(400, rejected.status); assertTrue(rejected.body.contains("100 megapixels"))
        assertEquals(400, request(server, "/v1/reading/image", "POST", png(7, 8), "image/png").status)
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(("POST /v1/reading/image HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n" +
                "Authorization: Bearer ${server.apiToken}\r\nContent-Type: image/png\r\nContent-Length: ${IntegrationImage.MAX_BYTES + 1}\r\nConnection: close\r\n\r\n").toByteArray())
            assertEquals(413, socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt())
        }
        assertTrue(reading.requests.isEmpty())
        // Failed decoding and rejected uploads release the shared admission reservation.
        repeat(4) { assertEquals(202, request(server, "/v1/reading/image", "POST", png(), "image/png").status) }
    }

    @Test fun imageAndCaptionJobsShareAdmissionAndJoinedCancellationReleasesCapacity() = fixture { reading, server ->
        repeat(2) { assertEquals(202, request(server, "/v1/sessions", "POST", """{"text":"hello"}""".toByteArray()).status) }
        repeat(2) { assertEquals(202, request(server, "/v1/reading/image", "POST", png(), "image/png").status) }
        assertEquals(409, request(server, "/v1/reading/image", "POST", png(), "image/png").status)
        assertEquals(409, request(server, "/v1/sessions", "POST", """{"text":"full"}""".toByteArray()).status)
        val cancelled = reading.sessions.first()
        assertEquals(200, request(server, "/v1/reading/${cancelled.id}", "DELETE").status)
        assertEquals(1, cancelled.cancels.get()); assertTrue(cancelled.cleanupFinished)
        assertEquals(202, request(server, "/v1/sessions", "POST", """{"text":"capacity freed"}""".toByteArray()).status)
    }

    @Test fun imageEventsCarryActualTextAndFailuresWithoutChangingCaptionOrObsStreams() = fixture { reading, server ->
        request(server, "/v1/reading/image", "POST", png(), "image/png")
        val session = reading.sessions.single()
        val stream = connection(server, "/v1/reading/${session.id}/events")
        try {
            assertEquals(200, stream.responseCode)
            stream.inputStream.bufferedReader().use { reader ->
                readUntil(reader) { it.startsWith("data:") && it.contains("\"readings\"") }
                session.state.value = session.state.value.copy(phase = "Reading Japanese…", regions = listOf(
                    IntegrationReadingRegion("r", OcrRect(0, 0, 8, 8), "原文")))
                assertTrue(readUntil(reader) { it.startsWith("data:") && it.contains("原文") }.contains("\"status\":\"running\""))
                session.state.value = session.state.value.copy(status = IntegrationReadingStatus.FAILED,
                    error = "Reader rejected token: Bearer ${server.apiToken} https://host/?api_key=hidden")
                val failed = readUntil(reader) { it.startsWith("data:") && it.contains("Reader rejected") }
                assertTrue(failed.contains("\"terminal\":true")); assertFalse(failed.contains(server.apiToken)); assertFalse(failed.contains("api_key=hidden"))
            }
        } finally { stream.disconnect() }
        assertEquals("{\"sessions\":[]}", request(server, "/v1/sessions").body)
        assertTrue(request(server, "/v1/reading").body.contains("Reader rejected"))
    }

    @Test fun oversizedOrInconsistentReaderOutputFailsExplicitlyAndStillObservesNativeCompletion() = fixture { reading, server ->
        request(server, "/v1/reading/image", "POST", png(), "image/png")
        val session = reading.sessions.single()
        session.state.value = session.state.value.copy(regions = listOf(
            IntegrationReadingRegion("r", OcrRect(0, 0, Int.MAX_VALUE, 8), "outside")))
        await { request(server, "/v1/reading/${session.id}").body.contains("out-of-image") }
        session.state.value = session.state.value.copy(id = "changed", status = IntegrationReadingStatus.COMPLETED)
        await { request(server, "/v1/reading/${session.id}").body.contains("\"terminal\":true") }
        val failed = request(server, "/v1/reading/${session.id}").body
        assertTrue(failed.contains("observation_failed")); assertTrue(failed.contains("identity or dimensions changed"))
        repeat(4) { assertEquals(202, request(server, "/v1/reading/image", "POST", png(), "image/png").status) }
    }

    @Test fun shutdownJoinsImageCancellationAndDoesNotCloseTheSharedPipeline() {
        val reading = Reading()
        val pipeline = Pipeline()
        val server = DesktopIntegrationServer(pipeline, ::config, reading = reading).start()
        request(server, "/v1/reading/image", "POST", png(), "image/png")
        server.close(); server.close()
        assertTrue(reading.sessions.single().cleanupFinished)
        assertEquals(1, reading.sessions.single().cancels.get())
        assertTrue(pipeline.sessions.isEmpty())
    }

    @Test fun extensionTrustAllowsExactOptedInOriginsAndAlwaysRequiresWriteBearer() {
        assertTrue(DesktopIntegrationServer.validExtensionOrigin(CHROME))
        assertTrue(DesktopIntegrationServer.validExtensionOrigin(FIREFOX))
        listOf("https://example.com", "*", "$CHROME/", "chrome-extension://*", "moz-extension://bad").forEach { origin ->
            assertFalse(DesktopIntegrationServer.validExtensionOrigin(origin))
            assertThrows(IllegalArgumentException::class.java) {
                DesktopIntegrationServer(Pipeline(), ::config, extensionOrigins = setOf(origin))
            }
        }
        DesktopIntegrationServer(Pipeline(), ::config, extensionOrigins = setOf(CHROME, FIREFOX)).start().use { server ->
            val preflight = raw(server, "/v1/sessions", "OPTIONS", CHROME, authenticated = false,
                headers = mapOf("Access-Control-Request-Method" to "POST", "Access-Control-Request-Headers" to "authorization, content-type"))
            assertTrue(preflight.startsWith("HTTP/1.1 204")); assertTrue(preflight.lowercase().contains("access-control-allow-origin: $CHROME"))
            assertFalse(preflight.lowercase().contains("access-control-allow-credentials"))
            assertTrue(raw(server, "/v1/capabilities", "GET", CHROME, authenticated = false).startsWith("HTTP/1.1 401"))
            assertTrue(raw(server, "/v1/capabilities", "GET", CHROME).startsWith("HTTP/1.1 200"))
            assertTrue(raw(server, "/v1/capabilities", "GET", FIREFOX).startsWith("HTTP/1.1 200"))
            assertTrue(raw(server, "/v1/capabilities", "GET", "chrome-extension://" + "b".repeat(32)).startsWith("HTTP/1.1 403"))
            assertTrue(raw(server, "/v1/capabilities", "GET", "https://website.example").startsWith("HTTP/1.1 403"))
            assertTrue(raw(server, "/overlay/", "GET", CHROME).startsWith("HTTP/1.1 403"))
            assertTrue(raw(server, "/v1/sessions", "OPTIONS", CHROME, headers = mapOf(
                "Access-Control-Request-Method" to "POST", "Access-Control-Request-Headers" to "x-provider-api-key")).startsWith("HTTP/1.1 400"))
        }
    }

    private class Reading : IntegrationReadingService {
        var available = true
        val requests = CopyOnWriteArrayList<IntegrationReadingRequest>()
        val sessions = CopyOnWriteArrayList<ReadingSession>()
        override fun capabilities() = IntegrationReadingCapabilities(available, setOf("ja"), if (available) null else "Install the selected manga models")
        override fun start(request: IntegrationReadingRequest): IntegrationReadingSession {
            requests += request
            return ReadingSession("image-${sessions.size}", request.image.width, request.image.height).also { sessions += it }
        }
    }
    private class ReadingSession(override val id: String, width: Int, height: Int) : IntegrationReadingSession {
        override val state = MutableStateFlow(IntegrationReadingSnapshot(id, IntegrationReadingStatus.RUNNING, width, height, "Loading…"))
        val cancels = AtomicInteger()
        @Volatile var cleanupFinished = false
        override suspend fun cancel() {
            if (state.value.terminal) return
            cancels.incrementAndGet(); delay(25); cleanupFinished = true
            state.value = state.value.copy(status = IntegrationReadingStatus.CANCELLED)
        }
    }
    private class Pipeline : PipelineService {
        val sessions = CopyOnWriteArrayList<Session>()
        override fun capabilities() = emptyList<EngineDescriptor>()
        override fun start(request: PipelineRequest) = Session("text-${sessions.size}").also { sessions += it }
    }
    private class Session(override val id: String) : PipelineSession {
        override val state = MutableStateFlow(PipelineSnapshot(id, PipelineStatus.RUNNING))
        override val events = MutableSharedFlow<PipelineEvent>()
        override suspend fun cancel() { state.value = state.value.copy(status = PipelineStatus.CANCELLED) }
    }
    private data class Response(val status: Int, val body: String)
    private fun connection(server: DesktopIntegrationServer, path: String) =
        (URI(server.baseUrl + path).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000; setRequestProperty("Authorization", "Bearer ${server.apiToken}")
        }
    private fun request(server: DesktopIntegrationServer, path: String, method: String = "GET", bytes: ByteArray? = null,
        type: String = "application/json"): Response {
        val http = connection(server, path)
        try {
            http.requestMethod = method
            if (bytes != null) {
                http.doOutput = true; http.setRequestProperty("Content-Type", type); http.setFixedLengthStreamingMode(bytes.size)
                http.outputStream.use { it.write(bytes) }
            }
            val status = http.responseCode
            val body = (if (status >= 400) http.errorStream else http.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(status, body)
        } finally { http.disconnect() }
    }
    private fun raw(server: DesktopIntegrationServer, path: String, method: String, origin: String, authenticated: Boolean = true,
        headers: Map<String, String> = emptyMap()): String = Socket("127.0.0.1", server.port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\nOrigin: $origin\r\n" +
            (if (authenticated) "Authorization: Bearer ${server.apiToken}\r\n" else "") +
            headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "Connection: close\r\n\r\n").toByteArray())
        socket.getInputStream().bufferedReader().readText()
    }
    private fun png(width: Int = 16, height: Int = 16): ByteArray = ByteArrayOutputStream().use { bytes ->
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", bytes); bytes.toByteArray()
    }
    private fun readUntil(reader: java.io.BufferedReader, predicate: (String) -> Boolean): String {
        repeat(100) { val line = reader.readLine() ?: error("Image event stream ended"); if (predicate(line)) return line }
        error("Image event was not delivered")
    }
    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!predicate()) { check(System.nanoTime() < end) { "Timed out waiting for image observation" }; Thread.sleep(10) }
    }
    private fun fixture(reading: Reading = Reading(), block: (Reading, DesktopIntegrationServer) -> Unit) {
        DesktopIntegrationServer(Pipeline(), ::config, reading = reading).start().use { server -> block(reading, server) }
    }
    companion object {
        private const val CHROME = "chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val FIREFOX = "moz-extension://01234567-89ab-cdef-0123-456789abcdef"
        private fun config() = PipelineConfig(sourceLanguage = "en", targetLanguage = "ar", speech = EngineSelection("speech"),
            translation = EngineSelection("translate", modelPath = "/private/model", credentialId = "private-credential"), voice = EngineSelection("voice"))
    }
}

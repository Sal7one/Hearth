package com.sal7one.hearth.integration

import com.sal7one.hearth.core.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Injected pipeline sessions test the real HTTP transport; no model inference is claimed here. */
class DesktopIntegrationServerTest {
    @Test fun apiRequiresBearerAndRejectsCrossOriginDnsRebindingAndPathSubmission() = fixture { service, server ->
        assertEquals(401, request(server, "/v1/capabilities", authenticated = false).status)
        assertEquals(200, request(server, "/v1/capabilities").status)
        assertEquals(403, rawStatus(server, "evil.example:${server.port}", null))
        assertEquals(403, rawStatus(server, "127.0.0.1:${server.port}", "https://evil.example"))
        assertEquals(403, request(server, "/v1/capabilities", "OPTIONS").status)
        val forbidden = request(server, "/v1/sessions", "POST", """{"text":"hello","modelPath":"/private/key"}""")
        assertEquals(400, forbidden.status)
        assertTrue(service.requests.isEmpty())
        val capabilities = request(server, "/v1/capabilities").body
        assertFalse(capabilities.contains("private/model"))
        assertFalse(capabilities.contains("credential-reference"))
        assertFalse(capabilities.contains(server.apiToken))
    }

    @Test fun textUsesActualSavedIndependentRoutesAndPreservesEngineFailures() = fixture { service, server ->
        val created = request(server, "/v1/sessions", "POST",
            """{"text":"مرحبا","targetLanguage":"en","translationEnabled":true,"voiceEnabled":false}""")
        assertEquals(202, created.status)
        val sent = service.requests.single()
        assertEquals(PipelineInput.Text("مرحبا"), sent.input)
        assertEquals("translate", sent.config.translation?.engineId)
        assertEquals("recognize", sent.config.speech?.engineId)
        assertNull(sent.config.voice)
        assertEquals("en", sent.config.targetLanguage)
        val session = service.sessions.single()
        session.state.value = session.state.value.copy(status = PipelineStatus.FAILED,
            error = "Provider rejected request: https://host/api?api_key=not-for-clients; Bearer ${server.apiToken}")
        await { request(server, "/v1/sessions/${session.id}").body.contains("Provider rejected") }
        val failed = request(server, "/v1/sessions/${session.id}").body
        assertTrue(failed.contains("failed"))
        assertTrue(failed.contains("Provider rejected request"))
        assertFalse(failed.contains("not-for-clients"))
        assertFalse(failed.contains(server.apiToken))
    }

    @Test fun wavAndRawPcmUploadsAreCanonicalPrivateFilesAndCancellationJoinsBeforeResponse() = fixture { service, server ->
        val pcm = ByteArray(320) { it.toByte() }
        val wave = IntegrationAudio.canonicalWave(pcm, rawPcm = true)
        assertEquals(202, request(server, "/v1/sessions/audio", "POST", bytes = wave, type = "audio/wav").status)
        val path = java.nio.file.Path.of((service.requests.single().input as PipelineInput.AudioFile).path)
        assertTrue(Files.exists(path))
        assertArrayEquals(wave, Files.readAllBytes(path))
        val session = service.sessions.single()
        assertEquals(200, request(server, "/v1/sessions/${session.id}", "DELETE").status)
        assertEquals(1, session.cancels.get())
        assertEquals(PipelineStatus.CANCELLED, session.state.value.status)
        await { !Files.exists(path) }
        assertEquals(400, request(server, "/v1/sessions/audio", "POST", bytes = pcm, type = "audio/pcm").status)
        assertEquals(202, request(server, "/v1/sessions/audio?voiceEnabled=false", "POST", bytes = pcm,
            type = "audio/pcm", headers = mapOf("X-Hearth-Audio-Format" to "pcm-s16le-16000-mono")).status)
    }

    @Test fun malformedAndOversizedUploadsDoNotStartPipelineWork() = fixture { service, server ->
        assertEquals(400, request(server, "/v1/sessions", "POST", bytes = byteArrayOf(0xc3.toByte(), 0x28)).status)
        assertEquals(400, request(server, "/v1/sessions", "POST", """{"text":""}""").status)
        assertEquals(400, request(server, "/v1/sessions", "POST", "[".repeat(20) + "]".repeat(20)).status)
        assertEquals(413, request(server, "/v1/sessions", "POST", bytes = ByteArray(65_537)).status)
        assertEquals(400, request(server, "/v1/sessions/audio", "POST", bytes = ByteArray(44), type = "audio/wav").status)
        val stereo = IntegrationAudio.canonicalWave(ByteArray(320), true).also { it[22] = 2 }
        assertEquals(400, request(server, "/v1/sessions/audio", "POST", bytes = stereo, type = "audio/wav").status)
        assertTrue(service.requests.isEmpty())
    }

    @Test fun mediaUploadPreservesPrivateBytesExtensionAndSavedRoutesForTheActualDecoder() = fixture { service, server ->
        // MIME routing is not a claim that this fixture is playable; the real pipeline validates codecs.
        val bytes = "media bytes for the platform decoder".toByteArray()
        val created = request(server, "/v1/sessions/media?targetLanguage=ar&voiceEnabled=false", "POST", bytes = bytes, type = "video/mp4")
        assertEquals(202, created.status)
        val submitted = service.requests.single()
        val file = java.nio.file.Path.of((submitted.input as PipelineInput.AudioFile).path)
        assertTrue(file.fileName.toString().endsWith(".mp4"))
        assertTrue(file.parent.fileName.toString().startsWith("hearth-integration-"))
        assertArrayEquals(bytes, Files.readAllBytes(file))
        assertEquals("recognize", submitted.config.speech?.engineId)
        assertEquals("ar", submitted.config.targetLanguage); assertNull(submitted.config.voice)
        assertFalse(created.body.contains(file.toString()))
        val capabilities = request(server, "/v1/capabilities").body
        assertTrue(capabilities.contains("\"maxMediaUploadBytes\":67108864"))
        assertTrue(capabilities.contains("\"mediaUploadRequiresNativeDecoder\":true"))
        assertEquals(200, request(server, "/v1/sessions/${service.sessions.single().id}", "DELETE").status)
        await { !Files.exists(file) }
    }

    @Test fun mediaRejectsUnsupportedMimeEmptyAndOversizedBodiesBeforeStartingAJob() = fixture { service, server ->
        assertEquals(415, request(server, "/v1/sessions/media", "POST", bytes = byteArrayOf(1), type = "image/png").status)
        assertEquals(400, request(server, "/v1/sessions/media", "POST", bytes = byteArrayOf(), type = "audio/mpeg").status)
        openMediaUpload(server, IntegrationMedia.MAX_BYTES.toLong() + 1).use { socket ->
            assertEquals(413, socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt())
        }
        assertTrue(service.requests.isEmpty())
    }

    @Test fun pendingMediaUploadsShareAdmissionAndDisconnectReclaimsTheirPrivateFiles() {
        val directory = Files.createTempDirectory("hearth-media-http-test-")
        val service = ControlledPipeline()
        try {
            DesktopIntegrationServer(service, ::configuration, directory).start().use { server ->
                val uploads = (1..4).map { openMediaUpload(server, 1_024).apply { getOutputStream().write(byteArrayOf(1, 2)) } }
                try {
                    await { regularFiles(directory).size == 4 }
                    assertEquals(409, request(server, "/v1/sessions", "POST", """{"text":"blocked by pending uploads"}""").status)
                } finally { uploads.forEach(Socket::close) }
                await { regularFiles(directory).isEmpty() }
                assertTrue(service.requests.isEmpty())
                assertEquals(202, request(server, "/v1/sessions", "POST", """{"text":"after disconnected uploads"}""").status)
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun stoppingIntegrationDuringMediaUploadJoinsAndDeletesThePartialFile() {
        val directory = Files.createTempDirectory("hearth-stop-media-http-test-")
        val service = ControlledPipeline()
        val server = DesktopIntegrationServer(service, ::configuration, directory).start()
        try {
            openMediaUpload(server, 1_024).use { socket ->
                socket.getOutputStream().write(byteArrayOf(1, 2))
                await { regularFiles(directory).size == 1 }
                server.close()
                assertTrue(regularFiles(directory).isEmpty())
                assertEquals(0L, Files.list(directory).use { it.count() })
            }
            assertTrue(service.requests.isEmpty())
        } finally { server.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun livePcmStartsBeforeFinishAndBackpressureNeverDropsAnAcceptedFrame() = fixture { service, server ->
        val start = request(server, "/v1/streams", "POST", "{}")
        assertEquals(202, start.status)
        val session = service.sessions.single()
        val input = service.requests.single().input as PipelineInput.AudioStream
        val path = "/v1/streams/${session.id}"
        val format = mapOf("X-Hearth-Audio-Format" to "pcm-s16le-16000-mono")
        repeat(8) { index -> assertEquals(202, request(server, path, "PUT", bytes = ByteArray(320) { index.toByte() }, type = "audio/pcm", headers = format).status) }
        assertEquals(429, request(server, path, "PUT", bytes = ByteArray(320) { 8 }, type = "audio/pcm", headers = format).status)
        val first = runBlocking { withTimeout(5_000) { input.frames.take(8).toList() } }
        assertEquals((0..7).toList(), first.map { it.bytes.first().toInt() })
        assertEquals(202, request(server, path, "PUT", bytes = ByteArray(320) { 8 }, type = "audio/pcm", headers = format).status)
        assertEquals(200, request(server, "$path/finish", "POST").status)
        val remaining = runBlocking { withTimeout(5_000) { input.frames.toList() } }
        assertEquals(8, remaining.single().bytes.first().toInt())
        // Closing input is not fabricated model completion; the pipeline still owns its actual result.
        assertEquals(PipelineStatus.RUNNING, session.state.value.status)
        assertEquals(409, request(server, path, "PUT", bytes = ByteArray(320), type = "audio/pcm", headers = format).status)
    }

    @Test fun sessionAndEventLimitsAreExplicitAndTerminalHistoryCanBeReclaimed() = fixture { service, server ->
        repeat(4) { assertEquals(202, request(server, "/v1/sessions", "POST", """{"text":"sample"}""").status) }
        assertEquals(409, request(server, "/v1/sessions", "POST", """{"text":"overflow"}""").status)
        service.sessions.forEach { it.state.value = it.state.value.copy(status = PipelineStatus.COMPLETED) }
        await { request(server, "/v1/sessions").body.let { text -> !text.contains("\"status\":\"running\"") } }
        assertEquals(202, request(server, "/v1/sessions", "POST", """{"text":"after completion"}""").status)
    }

    @Test fun authenticatedSseDeliversActualPartialFinalAndErrorStateWithoutLocalPaths() = fixture { service, server ->
        request(server, "/v1/sessions", "POST", """{"text":"initial"}""")
        val session = service.sessions.single()
        val connection = connection(server, "/v1/events?session=${session.id}")
        try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.bufferedReader().use { reader ->
                readUntil(reader) { it.startsWith("event: snapshot") }
                session.state.value = session.state.value.copy(segments = listOf(CaptionSegment("line", "partial", final = false)))
                readUntil(reader) { it.startsWith("data:") && it.contains("partial") && it.contains("\"final\":false") }
                session.state.value = session.state.value.copy(status = PipelineStatus.COMPLETED,
                    segments = listOf(CaptionSegment("line", "complete", "مكتمل")),
                    audio = listOf(GeneratedAudio("/private/generated.wav", text = "complete")))
                val final = readUntil(reader) { it.startsWith("data:") && it.contains("complete") && it.contains("\"terminal\":true") }
                assertTrue(final.contains("مكتمل"))
                assertFalse(final.contains("/private/"))
                assertFalse(final.contains(server.apiToken))
            }
            val resumed = connection(server, "/v1/events?session=${session.id}").apply {
                setRequestProperty("Last-Event-ID", Long.MAX_VALUE.toString())
            }
            try {
                assertEquals(200, resumed.responseCode)
                resumed.inputStream.bufferedReader().use { reader ->
                    assertTrue("Resync snapshots carry a replay watermark", reader.readLine().startsWith("id: "))
                    assertEquals("event: resync", reader.readLine())
                    assertTrue(reader.readLine().contains("complete"))
                }
            } finally { resumed.disconnect() }
        } finally { connection.disconnect() }
    }

    @Test fun fourEventStreamsLeaveStatusInputAndCancellationWorkersAvailable() = fixture { service, server ->
        request(server, "/v1/sessions", "POST", """{"text":"owned"}""")
        val connections = (1..4).map { connection(server, "/v1/events") }
        try {
            connections.forEach { assertEquals(200, it.responseCode) }
            assertEquals(200, request(server, "/v1/capabilities").status)
            assertEquals(202, request(server, "/v1/sessions", "POST", """{"text":"still responsive"}""").status)
            assertEquals(200, request(server, "/v1/sessions/${service.sessions.first().id}", "DELETE").status)
            val excess = connection(server, "/v1/events")
            try { assertEquals(429, excess.responseCode) } finally { excess.disconnect() }
        } finally { connections.forEach(HttpURLConnection::disconnect) }
    }

    @Test fun boundedViewFailureStillObservesCompletionCleansUploadAndReleasesAdmission() = fixture { service, server ->
        assertEquals(202, request(server, "/v1/sessions/audio", "POST", bytes = ByteArray(320), type = "audio/pcm",
            headers = mapOf("X-Hearth-Audio-Format" to "pcm-s16le-16000-mono")).status)
        val file = java.nio.file.Path.of((service.requests.single().input as PipelineInput.AudioFile).path)
        val session = service.sessions.single()
        val stream = connection(server, "/v1/events")
        try {
            assertEquals(200, stream.responseCode)
            stream.inputStream.bufferedReader().use { reader ->
                readUntil(reader) { it.startsWith("event: snapshot") }
                session.state.value = session.state.value.copy(segments = (1..3).map {
                    CaptionSegment("large-$it", "x".repeat(70_000))
                })
                readUntil(reader) { it.startsWith("data:") && it.contains("observation_failed") }
                session.state.value = session.state.value.copy(status = PipelineStatus.COMPLETED)
                readUntil(reader) { it.startsWith("data:") && it.contains("observation_failed") && it.contains("\"terminal\":true") }
            }
        } finally { stream.disconnect() }
        await { !Files.exists(file) }
        repeat(4) { assertEquals(202, request(server, "/v1/sessions", "POST", """{"text":"next job"}""").status) }
    }

    @Test fun nativeLinkFailureReturnsActualHttpFailureAndDoesNotKillTheHandler() = fixture { service, server ->
        service.capabilityFailure = UnsatisfiedLinkError("Native speech symbol is missing")
        val failed = request(server, "/v1/capabilities")
        assertEquals(500, failed.status); assertTrue(failed.body.contains("Native speech symbol is missing"))
        service.capabilityFailure = null
        assertEquals(200, request(server, "/v1/capabilities").status)
    }

    @Test fun obsCapabilityIsSeparateReadOnlyAndNeverAppearsInServedPage() = fixture { _, server ->
        val page = request(server, "/overlay/", authenticated = false)
        val fragment = URI(server.overlayUrl).fragment
        assertEquals(200, page.status)
        assertNotEquals(server.apiToken, fragment)
        assertFalse(page.body.contains(fragment))
        assertFalse(page.body.contains(server.apiToken))
        assertEquals(401, request(server, "/overlay/events", authenticated = false).status)
        assertEquals(401, request(server, "/overlay/connect", "POST", """{"token":"${server.apiToken}"}""", authenticated = false).status)
        val connected = request(server, "/overlay/connect", "POST", """{"token":"$fragment"}""", authenticated = false)
        assertEquals(200, connected.status)
        val cookie = connected.headers.entries.first { it.key.equals("Set-Cookie", true) }.value.single()
        assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Strict")); assertTrue(cookie.contains("Path=/overlay/"))
        assertFalse(cookie.contains(server.apiToken))
        assertEquals(401, request(server, "/v1/sessions", authenticated = false, headers = mapOf("Cookie" to cookie.substringBefore(';'))).status)
        val stream = connection(server, "/overlay/events", authenticated = false).apply {
            setRequestProperty("Cookie", cookie.substringBefore(';'))
        }
        try { assertEquals(200, stream.responseCode); stream.inputStream.close() } finally { stream.disconnect() }
        assertEquals(400, request(server, "/overlay/?token=$fragment", authenticated = false).status)
    }

    @Test fun closingCancelsOnlyHttpOwnedSessionsAndIsIdempotent() {
        val service = ControlledPipeline()
        val server = DesktopIntegrationServer(service, ::configuration).start()
        val external = ControlledSession("desktop-session")
        val observer = server.observe(external)
        request(server, "/v1/sessions", "POST", """{"text":"owned"}""")
        assertEquals(409, request(server, "/v1/sessions/${external.id}", "DELETE").status)
        server.close(); server.close(); observer.close()
        assertFalse(server.running)
        assertEquals(1, service.sessions.single().cancels.get())
        assertEquals(0, external.cancels.get())
        assertThrows(IllegalStateException::class.java) { server.start() }
    }

    @Test fun overlayPageAndSseExposeLiveCaptionsWithoutProviderCredentials() = fixture { service, server ->
        val page = request(server, "/overlay/", authenticated = false)
        assertEquals(200, page.status)
        assertTrue(page.body.contains("Hearth captions"))
        assertTrue(page.body.contains("/overlay/app.js"))
        val script = request(server, "/overlay/app.js", authenticated = false)
        assertEquals(200, script.status)
        assertTrue(script.body.contains("/overlay/events"))

        assertEquals(202, request(server, "/v1/sessions", "POST", """{"text":"hello","voiceEnabled":false}""").status)
        val session = service.sessions.single()
        session.state.value = session.state.value.copy(
            status = PipelineStatus.RUNNING,
            segments = listOf(CaptionSegment("s1", "Hello overlay", "مرحبا overlay", true, 0, 100)),
        )
        await { request(server, "/v1/sessions/${session.id}").body.contains("Hello overlay") }

        val fragment = server.overlayUrl.substringAfter("#")
        val connect = request(server, "/overlay/connect", "POST", """{"token":"$fragment"}""", authenticated = false)
        assertEquals(200, connect.status)
        val cookie = connect.headers.entries.firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }?.value?.firstOrNull()
            ?.substringBefore(";") ?: error("Overlay connect did not set its cookie")
        val stream = connection(server, "/overlay/events", authenticated = false).apply {
            setRequestProperty("Cookie", cookie); connectTimeout = 5_000; readTimeout = 5_000
        }
        try {
            val payload = readUntil(stream.inputStream.bufferedReader()) { it.startsWith("data:") && it.contains("Hello overlay") }
            assertTrue(payload.contains("مرحبا overlay"))
            assertFalse(payload.contains(server.apiToken))
        } finally { stream.disconnect() }
    }

    @Test fun eventBackpressureSignalsResyncRatherThanGrowingOrDroppingFinalSilently() {
        val events = IntegrationEvents()
        val slow = events.subscribe(null, null)
        repeat(80) { events.publish("test", "{\"sequence\":$it}") }
        assertTrue(slow.closed); assertTrue(slow.resync)
        assertNull("A stale queued event must not follow a fresh resync snapshot", slow.poll())
        val restored = events.subscribe(null, 0)
        assertTrue(restored.resync)
        assertEquals(1, restored.initial.size)
        assertTrue(restored.initial.single().json.contains("79"))
        events.close()
    }

    private data class Response(val status: Int, val body: String, val headers: Map<String?, List<String>>)
    private fun connection(server: DesktopIntegrationServer, path: String, authenticated: Boolean = true): HttpURLConnection =
        (URI(server.baseUrl + path).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 5_000
            if (authenticated) setRequestProperty("Authorization", "Bearer ${server.apiToken}")
        }

    private fun request(server: DesktopIntegrationServer, path: String, method: String = "GET", text: String? = null,
        bytes: ByteArray? = text?.toByteArray(), type: String = "application/json", authenticated: Boolean = true,
        headers: Map<String, String> = emptyMap()): Response {
        val http = connection(server, path, authenticated)
        try {
            http.requestMethod = method
            headers.forEach(http::setRequestProperty)
            if (bytes != null) {
                http.doOutput = true; http.setRequestProperty("Content-Type", type); http.setFixedLengthStreamingMode(bytes.size)
                http.outputStream.use { it.write(bytes) }
            }
            val status = http.responseCode
            val body = (if (status >= 400) http.errorStream else http.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(status, body, http.headerFields.toMap())
        } finally { http.disconnect() }
    }

    private fun rawStatus(server: DesktopIntegrationServer, host: String, origin: String?): Int = Socket("127.0.0.1", server.port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(("GET /v1/capabilities HTTP/1.1\r\nHost: $host\r\n" +
            (origin?.let { "Origin: $it\r\n" } ?: "") + "Authorization: Bearer ${server.apiToken}\r\nConnection: close\r\n\r\n").toByteArray())
        socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
    }

    private fun openMediaUpload(server: DesktopIntegrationServer, length: Long): Socket = Socket("127.0.0.1", server.port).apply {
        soTimeout = 5_000
        getOutputStream().write(("POST /v1/sessions/media HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n" +
            "Authorization: Bearer ${server.apiToken}\r\nContent-Type: video/mp4\r\nContent-Length: $length\r\nConnection: close\r\n\r\n").toByteArray())
    }

    private fun regularFiles(directory: java.nio.file.Path): List<java.nio.file.Path> = try {
        Files.walk(directory).use { entries -> entries.filter { Files.isRegularFile(it) }.toList() }
    } catch (_: java.io.UncheckedIOException) {
        // A disconnect/close races this wait; the server is allowed to delete its partial file.
        emptyList()
    } catch (_: java.nio.file.NoSuchFileException) {
        emptyList()
    }

    private fun readUntil(reader: java.io.BufferedReader, predicate: (String) -> Boolean): String {
        repeat(100) { val value = reader.readLine() ?: error("Event stream ended early"); if (predicate(value)) return value }
        error("Expected SSE event was not delivered")
    }

    private fun await(predicate: () -> Boolean) {
        val end = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (!predicate()) { check(System.nanoTime() < end) { "Timed out waiting for pipeline observation" }; Thread.sleep(10) }
    }

    private fun fixture(block: (ControlledPipeline, DesktopIntegrationServer) -> Unit) {
        val service = ControlledPipeline()
        DesktopIntegrationServer(service, ::configuration).start().use { server -> block(service, server) }
    }

    private class ControlledPipeline : PipelineService {
        var capabilityFailure: Throwable? = null
        val requests = CopyOnWriteArrayList<PipelineRequest>()
        val sessions = CopyOnWriteArrayList<ControlledSession>()
        override fun capabilities(): List<EngineDescriptor> {
            capabilityFailure?.let { throw it }
            return listOf(EngineDescriptor("translate", "Test pipeline transport", PipelineStage.TRANSLATION, EngineKind.LOCAL))
        }
        override fun start(request: PipelineRequest): PipelineSession {
            requests += request
            return ControlledSession("job-${sessions.size}").also { sessions += it }
        }
    }
    private class ControlledSession(override val id: String) : PipelineSession {
        override val state = MutableStateFlow(PipelineSnapshot(id, PipelineStatus.RUNNING))
        override val events = MutableSharedFlow<PipelineEvent>()
        val cancels = AtomicInteger()
        override suspend fun cancel() { cancels.incrementAndGet(); state.value = state.value.copy(status = PipelineStatus.CANCELLED) }
    }
    companion object {
        private fun configuration() = PipelineConfig(
            speech = EngineSelection("recognize", modelPath = "/private/model", credentialId = "credential-reference"),
            translation = EngineSelection("translate"), voice = EngineSelection("voice"), sourceLanguage = "ar", targetLanguage = "en")
    }
}

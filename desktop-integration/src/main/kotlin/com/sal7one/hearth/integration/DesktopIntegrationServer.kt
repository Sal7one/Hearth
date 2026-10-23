package com.sal7one.hearth.integration

import com.sal7one.hearth.core.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Explicitly started, authenticated loopback integration. It never owns the desktop pipeline itself. */
class DesktopIntegrationServer(
    private val pipeline: PipelineService,
    private val configuration: () -> PipelineConfig,
    private val directory: Path? = null,
    private val requestedPort: Int = 0,
    private val reading: IntegrationReadingService? = null,
    extensionOrigins: Set<String> = emptySet(),
) : AutoCloseable {
    private val extensionOrigins = extensionOrigins.toSet()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = IntegrationEvents()
    private val readingEvents = IntegrationEvents()
    private val records = ConcurrentHashMap<String, Record>()
    private val readingRecords = ConcurrentHashMap<String, ReadingRecord>()
    private val jobsLock = Any()
    private var pendingMediaUploads = 0 // Guarded by jobsLock; uploads share the four-job admission limit.
    private val streamSlots = Semaphore(4)
    private val threadNumber = AtomicInteger()
    private val threads = ThreadFactory { task -> Thread(task, "hearth-integration-${threadNumber.incrementAndGet()}").apply { isDaemon = true } }
    // Four long-lived SSE requests must leave workers available for status, input and cancellation.
    // A smaller core plus a bounded queue would never grow until the entire queue filled.
    private val executor = ThreadPoolExecutor(12, 12, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64), threads)
    private val deadlines = ScheduledThreadPoolExecutor(1, threads).apply {
        // Completed requests must not retain a thirty-second backlog of cancelled timers.
        removeOnCancelPolicy = true
    }
    private var http: HttpServer? = null
    private var temporaryDirectory: Path? = null
    private val overlayCapability = token()
    private val overlayCookie = token()

    /** Copy explicitly for external applications. Never put this write-capable token in an OBS URL. */
    val apiToken: String = token()
    val running: Boolean get() = started.get() && !closed.get()
    val port: Int get() = checkNotNull(http) { "Start integration first" }.address.port
    val baseUrl: String get() = "http://127.0.0.1:$port"
    /** The fragment is a separate read-only caption capability; fragments are not HTTP request paths. */
    val overlayUrl: String get() = "$baseUrl/overlay/#$overlayCapability"

    init {
        require(requestedPort in 0..65535) { "Invalid integration port" }
        require(this.extensionOrigins.size <= 8 && this.extensionOrigins.all(::validExtensionOrigin)) {
            "Trust at most eight exact chrome-extension://IDs or moz-extension://UUIDs; website origins and wildcards are unavailable"
        }
    }

    @Synchronized fun start(): DesktopIntegrationServer {
        check(!closed.get()) { "Integration server is closed" }
        if (started.get()) return this
        val root = if (directory == null) Files.createTempDirectory("hearth-integration-") else {
            Files.createDirectories(directory)
            Files.createTempDirectory(directory, "hearth-integration-")
        }
        try {
            try { Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------")) }
            catch (_: UnsupportedOperationException) { /* Windows inherits the user's private temp directory. */ }
            temporaryDirectory = root
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), 16)
            http = server
            server.executor = executor
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
            started.set(true)
            return this
        } catch (error: Throwable) {
            http?.stop(0); http = null; Files.deleteIfExists(root); temporaryDirectory = null
            throw error
        }
    }

    /** Mirror a desktop-owned session into OBS/events without transferring native or cancellation ownership. */
    fun observe(session: PipelineSession): AutoCloseable {
        check(running) { "Start integration before observing a session" }
        val record = synchronized(jobsLock) {
            check(running) { "Integration server is closed" }
            records[session.id]?.also { check(it.session === session) { "Duplicate pipeline session ID" } }
                ?: register(session, owned = false, input = null)
        }
        return AutoCloseable {
            synchronized(jobsLock) {
                if (!record.owned && records.remove(record.session.id, record)) { record.observer?.cancel(); events.forget(record.session.id) }
            }
        }
    }

    private class LiveInput {
        val frames = Channel<PcmFrame>(8)
        var bytes = 0L
        var finished = false
    }

    private class Record(val session: PipelineSession, val owned: Boolean, val input: Path?, val live: LiveInput? = null) {
        @Volatile var latest = session.state.value
        @Volatile var observer: Job? = null
        var observationFailure: String? = null // Guarded by jobsLock, alongside event publication.
        val createdAt = System.nanoTime()
    }

    private class ReadingRecord(val session: IntegrationReadingSession, val sourceWidth: Int, val sourceHeight: Int,
        val width: Int, val height: Int) {
        @Volatile var latest = session.state.value
        @Volatile var observer: Job? = null
        val createdAt = System.nanoTime()
    }

    private fun activeOwnedJobs(): Int = records.values.count { it.owned && !it.latest.terminal } +
        readingRecords.values.count { !it.latest.terminal }

    private fun register(session: PipelineSession, owned: Boolean, input: Path?, live: LiveInput? = null): Record {
        require(session.id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Unsafe pipeline session ID" }
        check(records[session.id] == null) { "Duplicate pipeline session ID" }
        prune()
        check(records.size < 32) { "Integration session history is full" }
        val record = Record(session, owned, input, live)
        records[session.id] = record
        record.observer = scope.launch {
            try {
                session.state.first { snapshot ->
                    check(snapshot.id == session.id) { "Pipeline session identity changed" }
                    synchronized(jobsLock) {
                        if (records[session.id] !== record) true else {
                            record.latest = snapshot
                            publishSnapshot(record)
                            snapshot.terminal
                        }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                // Transport observation failure is explicit; it does not forge a successful pipeline result.
                events.publish(session.id, buildJsonObject {
                    put("id", session.id); put("status", "observation_failed"); put("error", safeError(error))
                }.toString())
            } finally { if (session.state.value.terminal) { live?.frames?.close(); input?.let { Files.deleteIfExists(it) } } }
        }
        return record
    }

    private fun publishSnapshot(record: Record) {
        try {
            events.publish(record.session.id, encodedSnapshot(record))
            record.observationFailure = null
        } catch (error: Exception) {
            // Keep observing the real lifecycle even when a bounded HTTP view cannot contain it.
            // Otherwise uploads and admission slots would remain retained after model completion.
            val message = safeError(error)
            if (record.observationFailure != message || record.latest.terminal) {
                events.publish(record.session.id, buildJsonObject {
                    put("id", record.session.id); put("status", "observation_failed")
                    put("pipelineStatus", record.latest.status.name.lowercase())
                    put("terminal", record.latest.terminal); put("error", message)
                }.toString())
                record.observationFailure = message
            }
        }
    }

    private fun prune() {
        if (records.size < 32) return
        records.values.filter { it.latest.terminal }.sortedBy { it.createdAt }.take(records.size - 31).forEach {
            if (records.remove(it.session.id, it)) { it.observer?.cancel(); it.input?.let { path -> Files.deleteIfExists(path) }; events.forget(it.session.id) }
        }
    }

    private class UploadReservation(var pending: Boolean = true)

    private fun reserveUpload(): UploadReservation = synchronized(jobsLock) {
        check(running) { "Integration server is closed" }
        check(activeOwnedJobs() + pendingMediaUploads < 4) {
            "At most four integration sessions or uploads may be active"
        }
        pendingMediaUploads++
        UploadReservation()
    }

    private fun releaseUpload(reservation: UploadReservation) = synchronized(jobsLock) {
        if (reservation.pending) { reservation.pending = false; pendingMediaUploads-- }
    }

    private fun submit(input: PipelineInput, config: PipelineConfig, file: Path? = null, live: LiveInput? = null,
        reservation: UploadReservation? = null): Record = synchronized(jobsLock) {
        check(running) { "Integration server is closed" }
        reservation?.let(::releaseUpload)
        check(activeOwnedJobs() + pendingMediaUploads < 4) {
            "At most four integration sessions or uploads may be active"
        }
        prune()
        check(records.size < 32) { "Integration session history is full" }
        val session = pipeline.start(PipelineRequest(input, config))
        try { register(session, owned = true, input = file, live = live) }
        catch (error: Throwable) { live?.frames?.close(); runBlocking { session.cancel() }; throw error }
    }

    private fun handle(exchange: HttpExchange) {
        val deadline = deadlines.schedule({ exchange.close() }, 30, TimeUnit.SECONDS)
        try {
            security(exchange)
            if (exchange.requestMethod == "OPTIONS") { extensionPreflight(exchange); return }
            val path = exchange.requestURI.rawPath
            when {
                path == "/overlay/" || path == "/overlay" -> { method(exchange, "GET"); noQuery(exchange); send(exchange, 200, OverlayPage.html, "text/html; charset=utf-8") }
                path == "/overlay/app.js" -> { method(exchange, "GET"); noQuery(exchange); send(exchange, 200, OverlayPage.javascript, "text/javascript; charset=utf-8") }
                path == "/overlay/connect" -> connectOverlay(exchange)
                path == "/overlay/events" -> {
                    method(exchange, "GET"); noQuery(exchange); overlayAuthentication(exchange)
                    deadline.cancel(false); stream(exchange, null)
                }
                path.startsWith("/v1/") -> {
                    authenticate(exchange)
                    when {
                        path == "/v1/capabilities" -> { method(exchange, "GET"); noQuery(exchange)
                            json(exchange, 200, IntegrationJson.capabilities(pipeline.capabilities(), reading?.capabilities())) }
                        path == "/v1/events" -> {
                            method(exchange, "GET")
                            val fields = query(exchange, setOf("session"))
                            val session = fields["session"]?.also { record(it) }
                            deadline.cancel(false); stream(exchange, session)
                        }
                        path == "/v1/sessions" && exchange.requestMethod == "GET" -> { noQuery(exchange); json(exchange, 200, snapshots()) }
                        path == "/v1/sessions" && exchange.requestMethod == "POST" -> text(exchange)
                        path == "/v1/streams" -> createStream(exchange)
                        path.startsWith("/v1/streams/") -> liveStream(exchange, path.removePrefix("/v1/streams/"))
                        path == "/v1/sessions/audio" -> audio(exchange)
                        path == "/v1/sessions/media" -> media(exchange)
                        path.startsWith("/v1/sessions/") -> session(exchange, path.removePrefix("/v1/sessions/"))
                        path == "/v1/reading/image" -> readImage(exchange)
                        path == "/v1/reading" -> { method(exchange, "GET"); noQuery(exchange); json(exchange, 200, readingSnapshots()) }
                        path.startsWith("/v1/reading/") -> readingSession(exchange, path.removePrefix("/v1/reading/"), deadline)
                        else -> throw HttpFailure(404, "Unknown integration endpoint")
                    }
                }
                else -> throw HttpFailure(404, "Unknown integration endpoint")
            }
        } catch (error: HttpFailure) { failure(exchange, error.status, error.message.orEmpty()) }
        catch (error: IllegalArgumentException) { failure(exchange, 400, safeError(error)) }
        catch (error: IllegalStateException) { failure(exchange, 409, safeError(error)) }
        catch (error: LinkageError) { failure(exchange, 500, safeError(error)) }
        catch (error: Exception) { failure(exchange, 500, safeError(error)) }
        finally { deadline.cancel(false); exchange.close() }
    }

    private fun readImage(exchange: HttpExchange) {
        method(exchange, "POST")
        val service = reading ?: throw HttpFailure(503, "Image reading is unavailable in this host")
        val capability = service.capabilities()
        if (!capability.available) throw HttpFailure(409, capability.unavailableReason ?: "Choose installed image reading models in Hearth first")
        val fields = query(exchange, setOf("sourceLanguage", "targetLanguage", "translationEnabled"))
        val config = IntegrationJson.overrides(configuration().copy(sourceLanguage = "ja", speech = null, voice = null), fields)
        require(config.sourceLanguage in capability.sourceLanguages) {
            "The selected image reader does not support ${config.sourceLanguage}; supported languages: ${capability.sourceLanguages.sorted().joinToString()}"
        }
        val type = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        if (type !in IntegrationImage.contentTypes) throw HttpFailure(415, "Upload image/png or image/jpeg")
        validateContentLength(exchange, IntegrationImage.MAX_BYTES)
        val reservation = reserveUpload()
        var accepted: ReadingRecord? = null
        var responded = false
        try {
            val image = try { IntegrationImage.decode(body(exchange, IntegrationImage.MAX_BYTES), checkNotNull(type)) }
            catch (error: IOException) { throw HttpFailure(400, "Cannot decode image: ${safeError(error)}") }
            accepted = synchronized(jobsLock) {
                check(running) { "Integration server is closed" }
                releaseUpload(reservation)
                check(activeOwnedJobs() + pendingMediaUploads < 4) { "At most four integration sessions or uploads may be active" }
                pruneReading()
                check(readingRecords.size < 32) { "Image reading session history is full" }
                val session = service.start(IntegrationReadingRequest(image.image, config, config.translation != null))
                try { registerReading(session, image) }
                catch (error: Throwable) { runBlocking { session.cancel() }; throw error }
            }
            json(exchange, 202, safeReadingSnapshot(accepted))
            responded = true
        } finally {
            releaseUpload(reservation)
            // A client losing its creation response must not leave an undiscoverable native job.
            if (!responded) accepted?.let { runBlocking { it.session.cancel() } }
        }
    }

    private fun registerReading(session: IntegrationReadingSession, image: IntegrationImage.Decoded): ReadingRecord {
        require(session.id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Unsafe image reading session ID" }
        check(readingRecords[session.id] == null) { "Duplicate image reading session ID" }
        val record = ReadingRecord(session, image.sourceWidth, image.sourceHeight, image.image.width, image.image.height)
        check(record.latest.id == session.id && record.latest.width == image.image.width && record.latest.height == image.image.height) {
            "Image reading session identity or dimensions changed"
        }
        readingRecords[session.id] = record
        record.observer = scope.launch {
            try {
                session.state.first { snapshot ->
                    synchronized(jobsLock) {
                        if (readingRecords[session.id] !== record) true else {
                            record.latest = snapshot
                            readingEvents.publish(session.id, safeReadingSnapshot(record).toString())
                            snapshot.terminal
                        }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                readingEvents.publish(session.id, buildJsonObject {
                    put("id", session.id); put("type", "image-reading"); put("status", "observation_failed"); put("error", safeError(error))
                }.toString())
            }
        }
        return record
    }

    private fun safeReadingSnapshot(record: ReadingRecord): JsonObject = try {
        require(record.latest.id == record.session.id && record.latest.width == record.width && record.latest.height == record.height) {
            "Image reading session identity or dimensions changed"
        }
        IntegrationJson.reading(record.latest.copy(error = record.latest.error?.let(::sanitize)), record.sourceWidth, record.sourceHeight)
    } catch (error: IllegalArgumentException) {
        buildJsonObject {
            put("id", record.session.id); put("type", "image-reading"); put("status", "observation_failed")
            put("readingStatus", record.latest.status.name.lowercase()); put("terminal", record.latest.terminal)
            put("error", safeError(error))
        }
    }

    private fun readingSnapshots(): JsonObject = buildJsonObject {
        putJsonArray("readings") { readingRecords.values.sortedBy { it.createdAt }.forEach { add(safeReadingSnapshot(it)) } }
    }

    private fun pruneReading() {
        if (readingRecords.size < 32) return
        readingRecords.values.filter { it.latest.terminal }.sortedBy { it.createdAt }.take(readingRecords.size - 31).forEach {
            if (readingRecords.remove(it.session.id, it)) { it.observer?.cancel(); readingEvents.forget(it.session.id) }
        }
    }

    private fun readingSession(exchange: HttpExchange, path: String, deadline: java.util.concurrent.ScheduledFuture<*>) {
        noQuery(exchange)
        val parts = path.split('/')
        val id = parts.first()
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid image reading session ID" }
        val record = readingRecords[id] ?: throw HttpFailure(404, "Unknown image reading session")
        when {
            parts.size == 1 && exchange.requestMethod == "GET" -> json(exchange, 200, safeReadingSnapshot(record))
            parts.size == 1 && exchange.requestMethod == "DELETE" -> {
                runBlocking { record.session.cancel() }
                record.latest = record.session.state.value
                json(exchange, 200, safeReadingSnapshot(record))
            }
            parts.size == 2 && parts[1] == "events" -> {
                method(exchange, "GET"); deadline.cancel(false)
                stream(exchange, id, readingEvents, "readings", "reading")
            }
            else -> throw HttpFailure(405, "Use GET for status/events or DELETE to cancel image reading")
        }
    }

    private fun text(exchange: HttpExchange) {
        noQuery(exchange)
        val body = jsonBody(exchange, 65_536)
        val input = body["text"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: throw HttpFailure(400, "A text string is required")
        require(input.isNotBlank() && input.length <= 12_000) { "Text must contain 1–12000 characters" }
        val fields = body.filterKeys { it != "text" }.mapValues { (_, value) ->
            require(value is JsonPrimitive && value !is JsonNull) { "Route overrides must be scalar strings or booleans" }
            value.content
        }
        val config = IntegrationJson.overrides(configuration(), fields)
        json(exchange, 202, safeSnapshot(submit(PipelineInput.Text(input), config).latest, owned = true))
    }

    private fun audio(exchange: HttpExchange) {
        method(exchange, "POST")
        val fields = query(exchange, setOf("sourceLanguage", "targetLanguage", "translationEnabled", "voiceEnabled"))
        val type = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        require(type in setOf("audio/wav", "audio/x-wav", "audio/vnd.wave", "audio/pcm")) {
            "Unsupported audio content type; upload audio/wav or canonical audio/pcm"
        }
        if (type == "audio/pcm") require(exchange.requestHeaders.getFirst("X-Hearth-Audio-Format") == "pcm-s16le-16000-mono") {
            "Raw PCM needs X-Hearth-Audio-Format: pcm-s16le-16000-mono"
        }
        val config = IntegrationJson.overrides(configuration(), fields)
        val wave = IntegrationAudio.canonicalWave(body(exchange, IntegrationAudio.MAX_UPLOAD_BYTES), type == "audio/pcm")
        val file = Files.createTempFile(checkNotNull(temporaryDirectory), "input-", ".wav")
        var transferred = false
        try {
            Files.write(file, wave)
            val record = submit(PipelineInput.AudioFile(file.toAbsolutePath().toString()), config, file)
            transferred = true
            json(exchange, 202, safeSnapshot(record.latest, owned = true))
        } finally { if (!transferred) Files.deleteIfExists(file) }
    }

    private fun createStream(exchange: HttpExchange) {
        method(exchange, "POST"); noQuery(exchange)
        val fields = jsonBody(exchange, 2_048).mapValues { (_, value) ->
            require(value is JsonPrimitive && value !is JsonNull) { "Stream route overrides must be scalar values" }
            value.content
        }
        val config = IntegrationJson.overrides(configuration(), fields)
        check(config.speech != null) { "Configure speech recognition in Hearth before creating an audio stream" }
        val live = LiveInput()
        try {
            val record = submit(PipelineInput.AudioStream(live.frames.receiveAsFlow()), config, live = live)
            json(exchange, 202, safeSnapshot(record.latest, owned = true))
        } catch (error: Throwable) { live.frames.close(); throw error }
    }

    private fun media(exchange: HttpExchange) {
        method(exchange, "POST")
        val fields = query(exchange, setOf("sourceLanguage", "targetLanguage", "translationEnabled", "voiceEnabled"))
        val type = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        val extension = IntegrationMedia.extensions[type] ?: throw HttpFailure(415,
            "Upload MP4/MOV video or M4A/MP3/AAC audio with its supported media Content-Type")
        validateContentLength(exchange, IntegrationMedia.MAX_BYTES)
        val config = IntegrationJson.overrides(configuration(), fields)
        check(config.speech != null) { "Configure speech recognition in Hearth before uploading media" }
        val reservation = reserveUpload()
        var file: Path? = null
        var record: Record? = null
        var accepted = false
        try {
            val upload = Files.createTempFile(checkNotNull(temporaryDirectory), "media-", extension)
            file = upload
            exchange.requestBody.use { input -> Files.newOutputStream(upload).use { output ->
                val buffer = ByteArray(65_536)
                var count = 0L
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    if (count > IntegrationMedia.MAX_BYTES) throw HttpFailure(413, "Media upload exceeds 64 MiB")
                    output.write(buffer, 0, size)
                }
                require(count > 0) { "Media upload is empty" }
            } }
            record = submit(PipelineInput.AudioFile(upload.toAbsolutePath().toString()), config, upload, reservation = reservation)
            json(exchange, 202, safeSnapshot(record.latest, owned = true))
            accepted = true
        } finally {
            releaseUpload(reservation)
            if (!accepted) {
                // An interrupted upload/response must not leave an unreported background job.
                try { record?.let { runBlocking { it.session.cancel() } } }
                finally { file?.let { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun liveStream(exchange: HttpExchange, path: String) {
        noQuery(exchange)
        val parts = path.split('/')
        val record = record(parts.first())
        val live = record.live ?: throw HttpFailure(404, "Session has no live PCM input")
        when {
            parts.size == 1 -> {
                method(exchange, "PUT")
                require(exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()?.lowercase() == "audio/pcm" &&
                    exchange.requestHeaders.getFirst("X-Hearth-Audio-Format") == "pcm-s16le-16000-mono") {
                    "Live frames require audio/pcm and X-Hearth-Audio-Format: pcm-s16le-16000-mono"
                }
                val bytes = body(exchange, 6_400)
                require(bytes.isNotEmpty() && bytes.size % 2 == 0) { "PCM frames must contain whole nonempty samples, at most 200ms" }
                synchronized(live) {
                    check(!live.finished && !record.session.state.value.terminal) { "PCM input is closed" }
                    if (live.bytes + bytes.size > IntegrationAudio.MAX_PCM_BYTES) throw HttpFailure(413, "Live session exceeds 120 seconds; finish it and create another")
                    val sent = live.frames.trySend(PcmFrame(bytes))
                    if (sent.isFailure) throw HttpFailure(if (sent.isClosed) 409 else 429,
                        if (sent.isClosed) "PCM input is closed" else "PCM queue is full; retry this frame before sending another")
                    live.bytes += bytes.size
                }
                json(exchange, 202, buildJsonObject { put("id", record.session.id); put("acceptedBytes", bytes.size) })
            }
            parts.size == 2 && parts[1] == "finish" -> {
                method(exchange, "POST")
                synchronized(live) { live.finished = true; live.frames.close() }
                json(exchange, 200, buildJsonObject { put("id", record.session.id); put("inputFinished", true) })
            }
            else -> throw HttpFailure(404, "Unknown live stream endpoint")
        }
    }

    private fun session(exchange: HttpExchange, path: String) {
        noQuery(exchange)
        val parts = path.split('/')
        val record = record(parts[0])
        when {
            parts.size == 1 && exchange.requestMethod == "GET" -> json(exchange, 200, safeSnapshot(record.latest, record.owned))
            parts.size == 1 && exchange.requestMethod == "DELETE" -> {
                check(record.owned) { "Cancel desktop-owned sessions in the desktop interface" }
                record.live?.let { synchronized(it) { it.finished = true; it.frames.close() } }
                runBlocking { record.session.cancel() }
                record.latest = record.session.state.value
                json(exchange, 200, safeSnapshot(record.latest, record.owned))
            }
            parts.size == 3 && parts[1] == "audio" -> {
                method(exchange, "GET")
                val index = parts[2].toIntOrNull()?.takeIf { it in 0..31 } ?: throw HttpFailure(404, "Unknown audio result")
                val audio = record.latest.audio.getOrNull(index) ?: throw HttpFailure(404, "Audio result is not available")
                val file = Path.of(audio.path)
                check(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "Generated audio is no longer available" }
                val size = Files.size(file)
                check(size in 1..24L * 1024 * 1024) { "Generated audio exceeds the HTTP output limit" }
                exchange.responseHeaders.set("Content-Type", "audio/wav")
                exchange.responseHeaders.set("Content-Disposition", "attachment; filename=hearth-audio-$index.wav")
                exchange.sendResponseHeaders(200, size)
                Files.newInputStream(file).use { input -> exchange.responseBody.use { out -> input.copyTo(out) } }
            }
            else -> throw HttpFailure(405, "Use GET for status/audio or DELETE to cancel")
        }
    }

    private fun record(id: String): Record {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid session ID" }
        return records[id] ?: throw HttpFailure(404, "Unknown session")
    }

    private fun security(exchange: HttpExchange) {
        if (!running) throw HttpFailure(503, "Integration server is not running")
        if (!exchange.remoteAddress.address.isLoopbackAddress) throw HttpFailure(403, "Loopback clients only")
        val hosts = exchange.requestHeaders["Host"].orEmpty()
        if (hosts.size != 1 || hosts.single() != "127.0.0.1:$port") throw HttpFailure(403, "Invalid loopback Host")
        val origins = exchange.requestHeaders["Origin"].orEmpty()
        if (origins.size > 1 || origins.any { it != baseUrl && it !in extensionOrigins }) throw HttpFailure(403, "Cross-origin requests are unavailable")
        val extension = origins.singleOrNull()?.takeIf { it in extensionOrigins }
        if (extension != null) {
            // Explicitly paired extensions can use the authenticated API, never the cookie/read-only OBS capability.
            if (!exchange.requestURI.rawPath.startsWith("/v1/")) throw HttpFailure(403, "Extensions may access the authenticated API only")
            exchange.responseHeaders.set("Access-Control-Allow-Origin", extension)
            exchange.responseHeaders.set("Vary", "Origin")
        }
        if (exchange.requestMethod == "OPTIONS" && extension == null) throw HttpFailure(403, "Cross-origin integration is unavailable")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
        exchange.responseHeaders.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
    }

    private fun extensionPreflight(exchange: HttpExchange) {
        val requestedMethod = exchange.requestHeaders["Access-Control-Request-Method"].orEmpty()
        require(requestedMethod.size == 1 && requestedMethod.single() in setOf("GET", "POST", "PUT", "DELETE")) {
            "Extension preflight requested an unsupported method"
        }
        val requestedHeaders = exchange.requestHeaders["Access-Control-Request-Headers"].orEmpty()
            .flatMap { it.split(',') }.map { it.trim().lowercase() }
        require(requestedHeaders.isNotEmpty() && requestedHeaders.all {
            it in setOf("authorization", "content-type", "last-event-id", "x-hearth-audio-format")
        }) { "Extension preflight requested unsupported headers" }
        exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE")
        exchange.responseHeaders.set("Access-Control-Allow-Headers", "Authorization, Content-Type, Last-Event-ID, X-Hearth-Audio-Format")
        exchange.responseHeaders.set("Access-Control-Max-Age", "600")
        exchange.sendResponseHeaders(204, -1)
    }

    private fun authenticate(exchange: HttpExchange) {
        val values = exchange.requestHeaders["Authorization"].orEmpty()
        if (values.size != 1 || !secretEquals(values.single(), "Bearer $apiToken")) throw HttpFailure(401, "Bearer authentication required")
    }

    private fun connectOverlay(exchange: HttpExchange) {
        method(exchange, "POST"); noQuery(exchange)
        val objectValue = jsonBody(exchange, 1_024)
        require(objectValue.keys == setOf("token")) { "Expected the read-only overlay capability" }
        val supplied = objectValue["token"]?.jsonPrimitive?.content.orEmpty()
        if (!secretEquals(supplied, overlayCapability)) throw HttpFailure(401, "Overlay capability is invalid")
        exchange.responseHeaders.add("Set-Cookie", "hearth_overlay=$overlayCookie; HttpOnly; SameSite=Strict; Path=/overlay/; Max-Age=43200")
        json(exchange, 200, buildJsonObject { put("connected", true); put("readOnly", true) })
    }

    private fun overlayAuthentication(exchange: HttpExchange) {
        val cookies = exchange.requestHeaders["Cookie"].orEmpty().flatMap { it.split(';') }
            .map { it.trim().split('=', limit = 2) }.filter { it.size == 2 && it[0] == "hearth_overlay" }
        if (cookies.size != 1 || !secretEquals(cookies.single()[1], overlayCookie)) throw HttpFailure(401, "Connect an OBS overlay first")
    }

    private fun stream(exchange: HttpExchange, session: String?, source: IntegrationEvents = events,
        snapshotField: String = "sessions", eventType: String = "session") {
        if (!streamSlots.tryAcquire()) throw HttpFailure(429, "At most four event streams may be open")
        var subscription: IntegrationEvents.Subscription? = null
        try {
            val after = exchange.requestHeaders.getFirst("Last-Event-ID")?.let { value ->
                value.toLongOrNull()?.takeIf { it >= 0 } ?: throw HttpFailure(400, "Invalid Last-Event-ID")
            }
            val active = source.subscribe(session, after)
            subscription = active
            exchange.responseHeaders.set("Content-Type", "text/event-stream; charset=utf-8")
            exchange.responseHeaders.set("X-Accel-Buffering", "no")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter(Charsets.UTF_8).use { out ->
                fun initial(kind: String, values: List<IntegrationEvent>) {
                    val value = buildJsonObject { putJsonArray(snapshotField) { values.forEach { add(IntegrationJson.codec.parseToJsonElement(it.json)) } } }
                    // Reconnect from the snapshot's watermark, never replay an older caption over it.
                    values.maxOfOrNull { it.sequence }?.let { out.write("id: $it\n") }
                    out.write("event: $kind\ndata: $value\n\n"); out.flush()
                }
                if (after == null || active.resync) initial(if (active.resync) "resync" else "snapshot", active.initial)
                active.resync = false
                while (running && !active.closed) {
                    val event = active.poll()
                    if (active.resync) {
                        initial("resync", source.snapshot(session)); active.resync = false
                        continue // An older queued event must never overwrite the fresh snapshot.
                    }
                    if (event == null) out.write(": keep-alive\n\n")
                    else out.write("id: ${event.sequence}\nevent: $eventType\ndata: ${event.json}\n\n")
                    out.flush()
                }
                if (active.resync && running) initial("resync", source.snapshot(session))
            }
        } catch (_: IOException) { /* The client disconnected; do not cancel shared/native work. */ }
        finally { subscription?.let(source::remove); streamSlots.release(); exchange.close() }
    }

    private fun snapshots(session: String? = null): JsonObject = buildJsonObject {
        putJsonArray("sessions") {
            records.values.filter { session == null || it.session.id == session }.sortedBy { it.createdAt }
                .forEach { add(safeSnapshot(it.latest, it.owned)) }
        }
    }

    private fun safeSnapshot(snapshot: PipelineSnapshot, owned: Boolean) =
        IntegrationJson.snapshot(snapshot.copy(error = snapshot.error?.let(::sanitize)), owned)

    private fun encodedSnapshot(record: Record): String = safeSnapshot(record.latest, record.owned).toString().also {
        check(it.length <= 262_144) { "Pipeline event exceeds the integration limit; retrieve a shorter session" }
    }

    private fun jsonBody(exchange: HttpExchange, limit: Int): JsonObject {
        require(exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()?.lowercase() == "application/json") {
            "Expected Content-Type: application/json"
        }
        val bytes = body(exchange, limit)
        val value = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: java.nio.charset.CharacterCodingException) { throw HttpFailure(400, "Malformed UTF-8 request") }
        var depth = 0; var quoted = false; var escaped = false
        value.forEach { character ->
            if (quoted) { if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false }
            else when (character) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 16) { "JSON nesting exceeds 16 levels" } }; '}', ']' -> depth-- }
        }
        return IntegrationJson.codec.parseToJsonElement(value) as? JsonObject ?: throw HttpFailure(400, "Expected a JSON object")
    }

    private fun body(exchange: HttpExchange, limit: Int): ByteArray {
        validateContentLength(exchange, limit)
        exchange.requestBody.use { input ->
            val result = java.io.ByteArrayOutputStream(minOf(limit, 8_192))
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (result.size().toLong() + count > limit) throw HttpFailure(413, "Request body exceeds $limit bytes")
                result.write(buffer, 0, count)
            }
            return result.toByteArray()
        }
    }

    private fun validateContentLength(exchange: HttpExchange, limit: Int) {
        exchange.requestHeaders["Content-Length"]?.let { values ->
            require(values.size == 1) { "Duplicate Content-Length" }
            val size = values.single().toLongOrNull() ?: throw HttpFailure(400, "Invalid Content-Length")
            if (size !in 0..limit.toLong()) throw HttpFailure(413, "Request body exceeds $limit bytes")
        }
    }

    private fun query(exchange: HttpExchange, allowed: Set<String>): Map<String, String> {
        val raw = exchange.requestURI.rawQuery ?: return emptyMap()
        require(raw.length <= 1_024) { "Query is too large" }
        val pairs = raw.split('&').map { entry ->
            val parts = entry.split('=', limit = 2)
            require(parts.size == 2) { "Expected named query values" }
            URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8)
        }
        require(pairs.map { it.first }.distinct().size == pairs.size && pairs.all { it.first in allowed }) { "Unknown or duplicate query parameter" }
        return pairs.toMap()
    }
    private fun noQuery(exchange: HttpExchange) { require(exchange.requestURI.rawQuery == null) { "Query parameters are unavailable here" } }
    private fun method(exchange: HttpExchange, expected: String) { if (exchange.requestMethod != expected) throw HttpFailure(405, "Expected $expected") }
    private fun safeError(error: Throwable) = sanitize(error.message ?: error.javaClass.simpleName)
    private fun sanitize(message: String) = IntegrationJson.redact(message)
        .replace(apiToken, "[redacted]").replace(overlayCapability, "[redacted]").replace(overlayCookie, "[redacted]")
    private fun failure(exchange: HttpExchange, status: Int, message: String) {
        try { json(exchange, status, buildJsonObject { put("error", message) }) } catch (_: IOException) { exchange.close() }
    }
    private fun json(exchange: HttpExchange, status: Int, value: JsonElement) = send(exchange, status, value.toString(), "application/json; charset=utf-8")
    private fun send(exchange: HttpExchange, status: Int, text: String, type: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @Synchronized override fun close() {
        val closing = synchronized(jobsLock) {
            if (!closed.compareAndSet(false, true)) return
            records.values.toList()
        }
        http?.stop(0)
        closing.forEach { it.live?.frames?.close() }
        var failure: Throwable? = null
        runBlocking {
            closing.filter { it.owned && !it.session.state.value.terminal }.forEach { record ->
                try { record.session.cancel() } catch (error: Exception) { if (failure == null) failure = error else failure?.addSuppressed(error) }
            }
            readingRecords.values.filter { !it.session.state.value.terminal }.forEach { record ->
                try { record.session.cancel() } catch (error: Exception) { if (failure == null) failure = error else failure?.addSuppressed(error) }
            }
        }
        events.close(); readingEvents.close(); scope.cancel(); executor.shutdownNow(); deadlines.shutdownNow()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            val error = IllegalStateException("Integration HTTP handlers did not finish during shutdown")
            if (failure == null) failure = error else failure?.addSuppressed(error)
        }
        closing.forEach { record -> record.input?.let { try { Files.deleteIfExists(it) } catch (error: IOException) { if (failure == null) failure = error else failure?.addSuppressed(error) } } }
        records.clear()
        readingRecords.clear()
        temporaryDirectory?.let { try { Files.deleteIfExists(it) } catch (error: IOException) { if (failure == null) failure = error else failure?.addSuppressed(error) } }
        failure?.let { throw it }
    }

    private class HttpFailure(val status: Int, message: String) : RuntimeException(message)
    private fun token(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    private fun secretEquals(left: String, right: String): Boolean = MessageDigest.isEqual(left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8))

    companion object {
        /** Browser-generated extension identities only. The desktop must ask the owner before persisting trust. */
        fun validExtensionOrigin(origin: String): Boolean = origin.matches(Regex("chrome-extension://[a-p]{32}")) ||
            origin.matches(Regex("moz-extension://[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
    }
}

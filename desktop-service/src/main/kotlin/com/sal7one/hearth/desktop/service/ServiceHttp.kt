package com.sal7one.hearth.desktop.service

import com.sal7one.hearth.core.service.ServiceErrorCode
import com.sal7one.hearth.core.service.ServiceException
import com.sal7one.hearth.core.service.ServiceJobRequest
import com.sal7one.hearth.core.service.ServiceLimits
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The loopback transport of the service contract. It only marshals: every rule lives in the
 * contract and the job manager. It binds 127.0.0.1, answers browsers nothing, and keeps
 * request and response bodies small; results are files fetched with range requests.
 */
internal class ServiceHttp(
    private val clients: ServiceClients,
    private val jobs: JobManager,
    private val capabilities: () -> JsonObject,
    private val log: ServiceLog,
    private val stop: () -> Unit,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
    private val threads = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(4) { task -> Thread(task, "hearth-service-http-${threads.incrementAndGet()}").apply { isDaemon = true } }
    val port: Int get() = server.address.port

    fun start() {
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    override fun close() { server.stop(0); executor.shutdownNow() }

    private fun handle(exchange: HttpExchange) {
        try {
            exchange.use { route(it) }
        } catch (_: java.io.IOException) {
            // The caller went away; there is nobody to answer.
        }
    }

    private fun route(exchange: HttpExchange) {
        try {
            guard(exchange)
            val path = exchange.requestURI.rawPath
            val method = exchange.requestMethod
            if (path == "/health") {
                requireMethod(method, "GET")
                return json(exchange, 200, buildJsonObject { put("service", "Hearth"); put("protocol", ServiceLimits.PROTOCOL); put("state", "ready") })
            }
            clients.authenticate(exchange.requestHeaders.getFirst("Authorization")) ?: throw ServiceException(ServiceErrorCode.UNAUTHORIZED, "Missing or wrong token")
            val parts = path.split('/').drop(1)
            when {
                path == "/v1/capabilities" -> { requireMethod(method, "GET"); json(exchange, 200, capabilities()) }
                path == "/v1/runtime" -> { requireMethod(method, "GET"); json(exchange, 200, runtime(jobs.runtime())) }
                path == "/v1/runtime/release" -> {
                    requireMethod(method, "POST")
                    val (routes, released) = jobs.release()
                    json(exchange, 200, buildJsonObject {
                        putJsonArray("released") { routes.forEach { add(JsonPrimitive(it)) } }
                        // Video memory is zero for CPU routes; GPU routes report null until Hearth measures it.
                        putJsonObject("freed") { put("ramBytes", released.ramBytes?.let(::JsonPrimitive) ?: JsonNull); put("vramBytes", 0) }
                    })
                }
                path == "/v1/host/stop" -> {
                    requireMethod(method, "POST")
                    json(exchange, 202, buildJsonObject { put("state", "stopping") })
                    stop()
                }
                parts.size == 3 && parts[0] == "v1" && parts[1] == "jobs" -> when (method) {
                    "PUT" -> {
                        val (record, created) = jobs.create(parts[2], request(body(exchange, MAX_REQUEST_BYTES)))
                        json(exchange, if (created) 201 else 200, record(record))
                    }
                    "GET" -> json(exchange, 200, record(jobs.get(parts[2])))
                    "DELETE" -> { jobs.forget(parts[2]); json(exchange, 200, buildJsonObject { put("key", parts[2]); put("state", "forgotten") }) }
                    else -> methodNotAllowed()
                }
                parts.size == 4 && parts[0] == "v1" && parts[1] == "jobs" && parts[3] == "input" -> {
                    requireMethod(method, "PUT")
                    val length = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: -1
                    json(exchange, 200, record(jobs.input(parts[2], exchange.requestHeaders.getFirst("Content-Type"),
                        exchange.requestHeaders.getFirst("X-Content-SHA256"), length, exchange.requestBody)))
                }
                parts.size == 5 && parts[0] == "v1" && parts[1] == "jobs" && parts[3] == "outputs" -> { requireMethod(method, "GET"); output(exchange, parts[2], parts[4]) }
                else -> json(exchange, 404, error("unknown_endpoint", "No such endpoint"))
            }
        } catch (refusal: ServiceException) {
            json(exchange, refusal.code.httpStatus, error(refusal.code.wire, refusal.message ?: refusal.code.wire))
        } catch (refusal: HttpRefusal) {
            json(exchange, refusal.status, error(refusal.code, refusal.message ?: refusal.code))
        } catch (malformed: IllegalArgumentException) {
            json(exchange, 400, error(ServiceErrorCode.INVALID_REQUEST.wire, malformed.message ?: "Malformed request"))
        } catch (failure: java.io.IOException) {
            throw failure
        } catch (failure: Exception) {
            log.event("request-failed", "kind" to failure.javaClass.simpleName)
            json(exchange, 500, error("internal", "Hearth could not complete the request"))
        }
    }

    /** Loopback clients name this host exactly; a browser page (which always sends Origin on such calls) is refused. */
    private fun guard(exchange: HttpExchange) {
        val host = exchange.requestHeaders.getFirst("Host")
        if (host != "127.0.0.1:$port" && host != "localhost:$port") throw HttpRefusal(403, "forbidden", "Use the loopback address Hearth published")
        if (exchange.requestHeaders.containsKey("Origin")) throw HttpRefusal(403, "forbidden", "This API is not available to web pages")
    }

    private fun request(body: ByteArray): ServiceJobRequest {
        val value = runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as JsonObject }.getOrElse { throw IllegalArgumentException("The body is not a JSON object") }
        val unknown = value.keys - FIELDS
        require(unknown.isEmpty()) { "Unknown field: ${unknown.first().take(40)}" }
        fun text(name: String): String? = value[name]?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: throw IllegalArgumentException("$name must be a string") }
        val deadline = value["deadlineSeconds"]?.let { (it as? JsonPrimitive)?.takeIf { number -> !number.isString }?.intOrNull ?: throw IllegalArgumentException("deadlineSeconds must be a whole number") }
        val resident = value["requireResident"]?.let { (it as? JsonPrimitive)?.takeIf { flag -> !flag.isString }?.booleanOrNull ?: throw IllegalArgumentException("requireResident must be true or false") }
        return ServiceJobRequest(text("route") ?: throw IllegalArgumentException("route is required"), text("sourceLanguage"), text("targetLanguage"),
            text("voice"), text("text"), deadline, resident ?: false)
    }

    private fun output(exchange: HttpExchange, key: String, name: String) {
        val (file, output) = jobs.output(key, name)
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            val range = exchange.requestHeaders.getFirst("Range")?.let { parseRange(it, size) ?: throw HttpRefusal(416, "invalid_range", "The requested range is not available") }
            val start = range?.first ?: 0
            val length = range?.let { it.last - it.first + 1 } ?: size
            exchange.responseHeaders.set("Content-Type", output.mime)
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            if (range != null) exchange.responseHeaders.set("Content-Range", "bytes ${range.first}-${range.last}/$size")
            // The server treats 0 as "unknown length"; an empty file is answered with -1, meaning no body.
            exchange.sendResponseHeaders(if (range != null) 206 else 200, if (length == 0L) -1 else length)
            if (length > 0) Channels.newChannel(exchange.responseBody).use { sink ->
                var position = start
                while (position < start + length) position += channel.transferTo(position, start + length - position, sink)
            }
        }
    }

    /** One `bytes=` range: first-last, first-, or -suffix. Anything else is refused rather than guessed. */
    private fun parseRange(header: String, size: Long): LongRange? {
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: return null
        val (first, last) = match.destructured
        if (size == 0L) return null
        return when {
            first.isEmpty() && last.isEmpty() -> null
            first.isEmpty() -> last.toLongOrNull()?.takeIf { it > 0 }?.let { (size - minOf(it, size)) until size }
            else -> {
                val start = first.toLongOrNull() ?: return null
                val end = if (last.isEmpty()) size - 1 else minOf(last.toLongOrNull() ?: return null, size - 1)
                if (start > end) null else start..end
            }
        }
    }

    private fun record(job: JobRecord): JsonObject = buildJsonObject {
        put("key", job.key); put("route", job.route); put("operation", job.operation.wire); put("state", job.state.wire); put("created", job.created)
        job.started?.let { put("started", it) }; job.finished?.let { put("finished", it) }
        job.inputAudioSeconds?.let { put("inputAudioSeconds", it) }; job.estimatedSeconds?.let { put("estimatedSeconds", it) }
        job.summary?.let { put("summary", it) }
        putJsonArray("outputs") { job.outputs.forEach { add(WorkerMessages.output(it)) } }
        job.error?.let { (code, message) -> put("error", buildJsonObject { put("code", code.wire); put("message", message) }) }
    }

    private fun runtime(view: RuntimeView): JsonObject = buildJsonObject {
        put("busy", view.busy?.let(::JsonPrimitive) ?: JsonNull)
        putJsonArray("resident") { view.resident.forEach { add(JsonPrimitive(it)) } }
        // Memory belongs to the one worker process, not to a route; null when no worker is running.
        put("worker", if (!view.workerRunning) JsonNull else buildJsonObject {
            put("ramBytes", view.ramBytes?.let(::JsonPrimitive) ?: JsonNull); put("vramBytes", 0); put("idleSeconds", view.idleSeconds)
        })
        put("idleUnloadSeconds", view.idleUnloadSeconds)
    }

    private fun body(exchange: HttpExchange, limit: Int): ByteArray {
        val bytes = exchange.requestBody.readNBytes(limit + 1)
        if (bytes.size > limit) throw HttpRefusal(413, ServiceErrorCode.INPUT_TOO_LARGE.wire, "The request body is larger than $limit bytes")
        return bytes
    }

    private fun json(exchange: HttpExchange, status: Int, value: JsonElement) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun error(code: String, message: String): JsonObject = buildJsonObject { putJsonObject("error") { put("code", code); put("message", message.take(2_000)) } }
    private fun requireMethod(actual: String, expected: String) { if (actual != expected) methodNotAllowed() }
    private fun methodNotAllowed(): Nothing = throw HttpRefusal(405, "method_not_allowed", "This endpoint does not accept that method")

    private class HttpRefusal(val status: Int, val code: String, message: String) : Exception(message)

    private companion object {
        /** 12,000 characters of text can take six bytes each once escaped, plus the other fields. */
        const val MAX_REQUEST_BYTES = 96 * 1024
        val FIELDS = setOf("route", "sourceLanguage", "targetLanguage", "voice", "text", "deadlineSeconds", "requireResident")
    }
}

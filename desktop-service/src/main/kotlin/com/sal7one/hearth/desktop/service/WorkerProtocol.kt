package com.sal7one.hearth.desktop.service

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.core.service.ServiceErrorCode
import com.sal7one.hearth.core.service.ServiceOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

/**
 * Host and engine worker exchange one JSON object per line over a local socket inside the
 * owner-only service folder. Only control messages cross it: audio and results are files in
 * the job's private folder, which both processes can reach.
 */
internal class WorkerChannel(private val socket: SocketChannel) : Closeable {
    private val writeLock = Any()
    private val buffer = ByteBuffer.allocate(8192).apply { limit(0) }

    fun send(message: JsonObject) {
        val bytes = (message.toString() + "\n").toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            val data = ByteBuffer.wrap(bytes)
            while (data.hasRemaining()) socket.write(data)
        }
    }

    /** The next message, or null when the peer closed the channel. Called from one reader thread. */
    fun receive(): JsonObject? {
        val line = ByteArrayOutputStream()
        while (true) {
            if (!buffer.hasRemaining()) {
                buffer.clear()
                val count = try { socket.read(buffer) } catch (_: IOException) { -1 }
                if (count < 0) return null
                buffer.flip()
                continue
            }
            val byte = buffer.get()
            if (byte == '\n'.code.toByte()) break
            if (line.size() >= MAX_MESSAGE_BYTES) throw IOException("Worker message exceeds $MAX_MESSAGE_BYTES bytes")
            line.write(byte.toInt())
        }
        return Json.parseToJsonElement(line.toString(Charsets.UTF_8)).jsonObject
    }

    override fun close() { runCatching { socket.close() } }

    private companion object { const val MAX_MESSAGE_BYTES = 512 * 1024 }
}

/** What the host hands the worker for one job. Paths are files in the job's private folder. */
internal data class WorkerJob(
    val key: String,
    val operation: ServiceOperation,
    val config: PipelineConfig,
    val audio: String?,
    val text: String?,
    val outputDirectory: String,
)

internal data class JobOutput(val name: String, val mime: String, val bytes: Long, val sha256: String)

internal sealed interface WorkerOutcome {
    data class Completed(val summary: JsonObject, val outputs: List<JobOutput>) : WorkerOutcome
    data class Failed(val code: ServiceErrorCode, val message: String) : WorkerOutcome
    object Cancelled : WorkerOutcome
}

internal object WorkerMessages {
    fun selection(value: EngineSelection): JsonObject = buildJsonObject {
        put("engineId", value.engineId); put("modelId", value.modelId); put("modelPath", value.modelPath)
        putJsonObject("options") { value.options.forEach { (key, option) -> put(key, option) } }
    }

    // Service routes are local engines: an endpoint or credential reference never crosses to the worker.
    fun selection(value: JsonObject): EngineSelection = EngineSelection(
        engineId = value.text("engineId"), modelId = value.text("modelId"), modelPath = value.text("modelPath"),
        options = value.getValue("options").jsonObject.mapValues { it.value.jsonPrimitive.content })

    fun config(value: PipelineConfig): JsonObject = buildJsonObject {
        value.speech?.let { put("speech", selection(it)) }
        value.translation?.let { put("translation", selection(it)) }
        value.voice?.let { put("voice", selection(it)) }
        put("sourceLanguage", value.sourceLanguage); put("targetLanguage", value.targetLanguage)
        put("voiceId", value.voiceId); put("speakTranslatedText", value.speakTranslatedText)
    }

    fun config(value: JsonObject): PipelineConfig = PipelineConfig(
        speech = value["speech"]?.jsonObject?.let(::selection), translation = value["translation"]?.jsonObject?.let(::selection),
        voice = value["voice"]?.jsonObject?.let(::selection), sourceLanguage = value.text("sourceLanguage"),
        targetLanguage = value.text("targetLanguage"), voiceId = value.text("voiceId"),
        speakTranslatedText = value.getValue("speakTranslatedText").jsonPrimitive.boolean)

    fun run(job: WorkerJob): JsonObject = buildJsonObject {
        put("type", "run"); put("key", job.key); put("operation", job.operation.wire); put("config", config(job.config))
        job.audio?.let { put("audio", it) }; job.text?.let { put("text", it) }; put("output", job.outputDirectory)
    }

    fun run(message: JsonObject): WorkerJob = WorkerJob(
        key = message.text("key"), operation = ServiceOperation.entries.single { it.wire == message.text("operation") },
        config = config(message.getValue("config").jsonObject), audio = message["audio"]?.jsonPrimitive?.contentOrNull,
        text = message["text"]?.jsonPrimitive?.contentOrNull, outputDirectory = message.text("output"))

    fun done(key: String, outcome: WorkerOutcome): JsonObject = buildJsonObject {
        put("type", "done"); put("key", key)
        when (outcome) {
            is WorkerOutcome.Completed -> {
                put("status", "completed"); put("summary", outcome.summary)
                putJsonArray("outputs") { outcome.outputs.forEach { add(output(it)) } }
            }
            is WorkerOutcome.Failed -> { put("status", "failed"); put("code", outcome.code.wire); put("message", outcome.message) }
            WorkerOutcome.Cancelled -> put("status", "cancelled")
        }
    }

    fun outcome(message: JsonObject): WorkerOutcome = when (message.text("status")) {
        "completed" -> WorkerOutcome.Completed(message.getValue("summary").jsonObject, message.getValue("outputs").jsonArray.map { output(it.jsonObject) })
        "failed" -> WorkerOutcome.Failed(ServiceErrorCode.entries.single { it.wire == message.text("code") }, message.text("message"))
        "cancelled" -> WorkerOutcome.Cancelled
        else -> throw IOException("Unknown worker job status")
    }

    fun output(value: JobOutput): JsonObject = buildJsonObject { put("name", value.name); put("mime", value.mime); put("bytes", value.bytes); put("sha256", value.sha256) }
    fun output(value: JsonObject): JobOutput = JobOutput(value.text("name"), value.text("mime"), value.getValue("bytes").jsonPrimitive.long, value.text("sha256"))

    fun engines(values: List<EngineDescriptor>): JsonArray = buildJsonArray {
        for (engine in values) add(buildJsonObject {
            put("id", engine.id); put("name", engine.name); put("stage", engine.stage.name); put("kind", engine.kind.name)
            putJsonArray("languages") { engine.languages.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("acceleration") { engine.acceleration.forEach { add(JsonPrimitive(it)) } }
            put("available", engine.available); engine.unavailableReason?.let { put("unavailableReason", it) }
            put("requiresModel", engine.requiresModel)
        })
    }

    fun engines(values: JsonArray): List<EngineDescriptor> = values.map { element ->
        val engine = element.jsonObject
        EngineDescriptor(
            id = engine.text("id"), name = engine.text("name"), stage = PipelineStage.valueOf(engine.text("stage")),
            kind = EngineKind.valueOf(engine.text("kind")), languages = engine.getValue("languages").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            acceleration = engine.getValue("acceleration").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            available = engine.getValue("available").jsonPrimitive.boolean, unavailableReason = engine["unavailableReason"]?.jsonPrimitive?.contentOrNull,
            requiresModel = engine.getValue("requiresModel").jsonPrimitive.boolean)
    }

    fun simple(type: String, key: String? = null): JsonObject = buildJsonObject { put("type", type); key?.let { put("key", it) } }

    fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}

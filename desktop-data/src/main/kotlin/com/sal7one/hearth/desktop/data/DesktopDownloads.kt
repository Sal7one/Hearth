package com.sal7one.hearth.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

enum class DesktopDownloadBackend { HTTP, ARIA2, AUTO }

data class DesktopDownloadOptions(
    val backend: DesktopDownloadBackend = DesktopDownloadBackend.AUTO,
    val simultaneousFiles: Int = 2,
    val connectionsPerFile: Int = 4,
    val bandwidthBytesPerSecond: Long = 0,
    val retries: Int = 3,
    val aria2Executable: String = "",
) {
    init {
        require(simultaneousFiles in 1..8) { "Choose between 1 and 8 simultaneous files" }
        require(connectionsPerFile in 1..16) { "Choose between 1 and 16 connections per file" }
        require(bandwidthBytesPerSecond >= 0 && retries in 0..10)
    }
    companion object {
        val Balanced = DesktopDownloadOptions()
        val Fast = DesktopDownloadOptions(simultaneousFiles = 4, connectionsPerFile = 8)
    }
}

enum class DesktopQueuePhase { WAITING, ACTIVE, PAUSED, FAILED, COMPLETE }
data class DesktopQueueEntry(val id: String, val phase: DesktopQueuePhase, val bytes: Long = 0, val total: Long = 0,
    val issue: String? = null)
data class DesktopTransferMeasurement(val modelId: String, val component: String, val backend: String,
    val bytes: Long, val elapsedMillis: Long, val resumedBytes: Long, val connections: Int, val recordedAtMillis: Long = System.currentTimeMillis()) {
    val bytesPerSecond: Long get() = if (elapsedMillis > 0) (bytes * 1000.0 / elapsedMillis).toLong() else 0
}
data class DesktopDownloadSnapshot(val options: DesktopDownloadOptions = DesktopDownloadOptions(),
    val queue: List<DesktopQueueEntry> = emptyList(), val imports: Map<String, String> = emptyMap(),
    val measurements: List<DesktopTransferMeasurement> = emptyList(), val issue: String? = null)

/** Operational journal. Complete atomic replacements prevent a killed process from truncating the queue. */
class DesktopDownloadStore(private val directory: Path) {
    private val file = directory.resolve("download-queue.json")
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    @Synchronized fun options(options: DesktopDownloadOptions) = change { it.copy(options = options) }
    @Synchronized fun queue(entry: DesktopQueueEntry) = change { old ->
        val found = old.queue.any { it.id == entry.id }
        old.copy(queue = if (found) old.queue.map { if (it.id == entry.id) entry else it } else old.queue + entry)
    }
    @Synchronized fun remove(id: String) = change { it.copy(queue = it.queue.filterNot { entry -> entry.id == id }) }
    @Synchronized fun imported(id: String, path: Path) = change { it.copy(imports = it.imports + (id to path.toAbsolutePath().normalize().toString())) }
    @Synchronized fun forgetImport(id: String) = change { it.copy(imports = it.imports - id) }
    @Synchronized fun measured(value: DesktopTransferMeasurement) = change { it.copy(measurements = (it.measurements + value).takeLast(5000)) }

    /** Human-readable measurements include verified transfers only, never a fabricated benchmark score. */
    @Synchronized fun exportMeasurements(destination: Path) {
        val content = buildString {
            appendLine("model,component,backend,network_bytes,elapsed_ms,resumed_bytes,connections,bytes_per_second,recorded_at_ms")
            state.value.measurements.forEach { m ->
                fun quote(text: String) = "\"${text.replace("\"", "\"\"")}\""
                appendLine(listOf(quote(m.modelId), quote(m.component), quote(m.backend), m.bytes, m.elapsedMillis,
                    m.resumedBytes, m.connections, m.bytesPerSecond, m.recordedAtMillis).joinToString(","))
            }
        }
        atomicWrite(destination, content)
    }

    private fun change(block: (DesktopDownloadSnapshot) -> DesktopDownloadSnapshot) {
        val next = block(mutable.value).copy(issue = null)
        if (mutable.value.issue != null && Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
            Files.copy(file, file.resolveSibling("download-queue.damaged-${System.currentTimeMillis()}.json"))
        }
        atomicWrite(file, encode(next).toString())
        mutable.value = next
    }
    private fun load(): DesktopDownloadSnapshot {
        if (!Files.exists(file)) return DesktopDownloadSnapshot()
        return try {
            require(!Files.isSymbolicLink(file) && Files.size(file) <= 8L * 1024 * 1024) { "Download journal is unsafe or too large" }
            val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
            val o = root["options"]?.jsonObject ?: JsonObject(emptyMap())
            fun int(key: String, default: Int) = o[key]?.jsonPrimitive?.intOrNull ?: default
            val options = DesktopDownloadOptions(
                backend = o["backend"]?.jsonPrimitive?.content?.let(DesktopDownloadBackend::valueOf) ?: DesktopDownloadBackend.AUTO,
                simultaneousFiles = int("files", 2), connectionsPerFile = int("connections", 4),
                bandwidthBytesPerSecond = o["bandwidth"]?.jsonPrimitive?.longOrNull ?: 0,
                retries = int("retries", 3), aria2Executable = o["aria2"]?.jsonPrimitive?.content.orEmpty())
            val queue = root["queue"]?.jsonArray.orEmpty().map { value ->
                val q = value.jsonObject
                val phase = DesktopQueuePhase.valueOf(q.getValue("phase").jsonPrimitive.content)
                DesktopQueueEntry(q.getValue("id").jsonPrimitive.content,
                    if (phase in setOf(DesktopQueuePhase.WAITING, DesktopQueuePhase.ACTIVE)) DesktopQueuePhase.PAUSED else phase,
                    q["bytes"]?.jsonPrimitive?.longOrNull ?: 0, q["total"]?.jsonPrimitive?.longOrNull ?: 0,
                    q["issue"]?.jsonPrimitive?.contentOrNull)
            }
            val imports = root["imports"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            val measurements = root["measurements"]?.jsonArray.orEmpty().map { value ->
                val m = value.jsonObject
                DesktopTransferMeasurement(m.getValue("id").jsonPrimitive.content, m.getValue("component").jsonPrimitive.content,
                    m.getValue("backend").jsonPrimitive.content, m.getValue("bytes").jsonPrimitive.long,
                    m.getValue("elapsed").jsonPrimitive.long, m.getValue("resumed").jsonPrimitive.long,
                    m.getValue("connections").jsonPrimitive.int, m.getValue("at").jsonPrimitive.long)
            }
            DesktopDownloadSnapshot(options, queue, imports, measurements)
        } catch (e: Exception) {
            // Keep the original for recovery; do not silently erase a damaged journal.
            DesktopDownloadSnapshot(issue = "Download journal could not be read: ${e.message}")
        }
    }
    private fun encode(snapshot: DesktopDownloadSnapshot) = buildJsonObject {
        put("version", 1)
        putJsonObject("options") {
            with(snapshot.options) { put("backend", backend.name); put("files", simultaneousFiles); put("connections", connectionsPerFile)
                put("bandwidth", bandwidthBytesPerSecond); put("retries", retries); put("aria2", aria2Executable) }
        }
        putJsonArray("queue") { snapshot.queue.forEach { q -> add(buildJsonObject {
            put("id", q.id); put("phase", q.phase.name); put("bytes", q.bytes); put("total", q.total); q.issue?.let { put("issue", it) }
        }) } }
        putJsonObject("imports") { snapshot.imports.forEach { (id, path) -> put(id, path) } }
        putJsonArray("measurements") { snapshot.measurements.forEach { m -> add(buildJsonObject {
            put("id", m.modelId); put("component", m.component); put("backend", m.backend); put("bytes", m.bytes)
            put("elapsed", m.elapsedMillis); put("resumed", m.resumedBytes); put("connections", m.connections); put("at", m.recordedAtMillis)
        }) } }
    }
}

internal fun atomicWrite(path: Path, content: String) {
    Files.createDirectories(path.toAbsolutePath().parent)
    require(!Files.isSymbolicLink(path)) { "Refusing to replace a symbolic link: ${path.fileName}" }
    val temporary = Files.createTempFile(path.toAbsolutePath().parent, ".journal-", ".tmp")
    try {
        Files.writeString(temporary, content)
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
    } finally { Files.deleteIfExists(temporary) }
}

internal fun encodeDesktopDownloadOptions(options: DesktopDownloadOptions): String = buildJsonObject {
    with(options) { put("backend", backend.name); put("files", simultaneousFiles); put("connections", connectionsPerFile)
        put("bandwidth", bandwidthBytesPerSecond); put("retries", retries); put("aria2", aria2Executable) }
}.toString()
internal fun decodeDesktopDownloadOptions(encoded: String): DesktopDownloadOptions {
    require(encoded.length <= 16384)
    val o = Json.parseToJsonElement(encoded).jsonObject
    return DesktopDownloadOptions(DesktopDownloadBackend.valueOf(o.getValue("backend").jsonPrimitive.content),
        o.getValue("files").jsonPrimitive.int, o.getValue("connections").jsonPrimitive.int,
        o.getValue("bandwidth").jsonPrimitive.long, o.getValue("retries").jsonPrimitive.int,
        o.getValue("aria2").jsonPrimitive.content)
}

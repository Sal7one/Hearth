package com.sal7one.transiber.benchmark

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One atomic record per result: saving model 500 does not rewrite models 1–499. */
internal class BenchmarkResults internal constructor(private val filesDir: File) {
    constructor(context: Context) : this(context.filesDir)

    companion object {
        const val MAX_HISTORY = 10_000
        internal const val MAX_STORAGE_BYTES = 64L * 1024 * 1024
        private const val MAX_RECORD_BYTES = 4L * 1024 * 1024
        private val lock = Any()
    }
    private val legacy = File(filesDir, "benchmark-results.json")
    private val directory = File(filesDir, "benchmark-results-v2")

    fun load(): List<BenchmarkResult> = synchronized(lock) {
        migrate()
        val files = records()
        require(files.sumOf(File::length) <= MAX_STORAGE_BYTES) { "Benchmark results exceed the local storage limit" }
        require(files.size <= MAX_HISTORY) { "Benchmark result history has too many records" }
        files.map { file ->
            require(file.length() in 1..MAX_RECORD_BYTES) { "Invalid benchmark result size: ${file.name}" }
            try { BenchmarkResult.from(JSONObject(file.readText())) }
            catch (e: Exception) { throw IllegalStateException("Cannot read benchmark result ${file.name}: ${e.message}", e) }
        }.sortedByDescending { it.timestamp }
    }

    fun append(result: BenchmarkResult) = synchronized(lock) {
        migrate()
        write(result)
        trim()
    }

    fun clear() = synchronized(lock) {
        records().forEach { check(it.delete()) { "Cannot clear benchmark history" } }
        if (legacy.exists()) check(legacy.delete()) { "Cannot clear previous benchmark history" }
    }

    fun export(): String = JSONArray(load().map { it.json() }).toString(2)

    private fun records() = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }
        .sortedByDescending { it.name }

    private fun write(result: BenchmarkResult) {
        val bytes = result.json().toString().toByteArray()
        require(bytes.size.toLong() <= MAX_RECORD_BYTES) { "One benchmark result exceeds the local storage limit" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create benchmark history directory" }
        val key = benchmarkHash("${result.runId}\u0000${result.identity}\u0000${result.inputHash}\u0000${result.source}\u0000${result.target}\u0000${result.task}".toByteArray())
        val destination = File(directory, "${result.timestamp.toString().padStart(19, '0')}-$key.json")
        val temporary = File(directory, ".${UUID.randomUUID()}.part")
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun migrate() {
        if (!legacy.exists()) return
        require(legacy.length() <= 8L * 1024 * 1024) { "Previous benchmark results exceed the local storage limit" }
        val array = JSONArray(legacy.readText())
        require(array.length() <= MAX_HISTORY) { "Previous benchmark result history has too many records" }
        // A crash during migration simply repeats the same deterministic record writes.
        val rows = (0 until array.length()).map { BenchmarkResult.from(array.getJSONObject(it)) }
        rows.forEach(::write)
        trim()
        check(legacy.delete()) { "Cannot finish benchmark history migration" }
    }

    private fun trim() {
        var bytes = 0L
        records().forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= MAX_HISTORY || bytes > MAX_STORAGE_BYTES)
                check(file.delete()) { "Cannot prune old benchmark history" }
        }
    }
}

package com.sal7one.transiber.benchmark

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal fun benchmarkHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

internal data class BenchmarkCase(
    val id: String, val source: String, val target: String = "", val text: String = "",
    val reference: String? = null, val referenceStatus: String = "unreviewed",
    val audio: String? = null, val sha256: String? = null, val pcmSha256: String? = null,
    val publisherSentenceId: String? = null, val silenceMs: Int = 0,
) {
    val speech: Boolean get() = audio != null || silenceMs > 0
    fun json() = JSONObject().put("id", id).put("source", source).put("target", target).put("text", text)
        .put("reference", reference ?: JSONObject.NULL).put("referenceStatus", referenceStatus)
        .put("audio", audio ?: JSONObject.NULL).put("sha256", sha256 ?: JSONObject.NULL)
        .put("pcmSha256", pcmSha256 ?: JSONObject.NULL).put("publisherSentenceId", publisherSentenceId ?: JSONObject.NULL)
        .put("silenceMs", silenceMs)
    companion object {
        fun parse(j: JSONObject): BenchmarkCase {
            fun optional(key: String) = if (j.has(key) && !j.isNull(key)) j.getString(key) else null
            return BenchmarkCase(j.getString("id"),j.getString("source"),j.optString("target"),j.optString("text"),
                optional("reference"),j.optString("referenceStatus","unreviewed"),optional("audio"),optional("sha256"),
                optional("pcmSha256"),optional("publisherSentenceId"),j.optInt("silenceMs")).also {
                require(it.id.matches(Regex("[A-Za-z0-9_.-]{1,120}"))) { "Invalid benchmark case ID" }
                require(it.source.matches(Regex("[a-z]{2,3}")) && (it.target.isEmpty() || it.target.matches(Regex("[a-z]{2,3}")))) { "Invalid benchmark languages" }
                require(it.referenceStatus in setOf("publisher","human-reviewed","unreviewed","none")) { "Unknown reference review status" }
                require(it.text.length <= 500 && (it.reference?.length ?: 0) <= 8000) { "Benchmark text is too long" }
                require(it.silenceMs == 0 || it.silenceMs in 500..30000) { "Invalid silence duration" }
                if (it.audio != null) {
                    require(safePath(it.audio) && it.audio.endsWith(".wav")) { "Unsafe benchmark audio path" }
                    require(it.sha256?.matches(Regex("[a-f0-9]{64}")) == true) { "Audio requires SHA-256" }
                    require(it.pcmSha256?.matches(Regex("[a-f0-9]{64}")) == true) { "Audio requires normalized PCM SHA-256" }
                }
                require(if (it.speech) it.target.isEmpty() else it.target.isNotEmpty() && it.target != it.source && it.text.isNotBlank()) { "Invalid benchmark task" }
            }
        }
        internal fun safePath(path: String) = path.isNotBlank() && path.length <= 180 && !path.startsWith('/') && '\\' !in path && ':' !in path && path.split('/').all { it.isNotEmpty() && it !in setOf(".","..") }
    }
}
internal data class BenchmarkSuite(
    val id: String, val title: String, val revision: String, val license: String, val sourceUrl: String,
    val fingerprint: String, val cases: List<BenchmarkCase>, val quickIds: List<String>, val fullIds: List<String>,
    val warmupId: String?, val root: File? = null,
) {
    fun selected(speech: Boolean, source: String, target: String, full: Boolean): List<BenchmarkCase> {
        val compatible = cases.filter { it.speech == speech && it.source == source &&
            (speech || it.target == target) && (warmupId == null || it.publisherSentenceId != warmupId) }
        val ids = if (full) fullIds else quickIds
        val ordered = if (ids.isEmpty()) compatible else ids.mapNotNull { id -> compatible.firstOrNull { it.publisherSentenceId == id || it.id == id } }
        val chosen = ordered.take(if (full) 30 else 6) + if (full && speech && fullIds == quickIds) {
            // The compact APK already bundles a distinct 14–17 s publisher clip.
            // Offer it as an optional long-speech check without another download.
            listOfNotNull(cases.firstOrNull { it.speech && it.source == source && it.publisherSentenceId == warmupId })
        } else emptyList()
        return if (speech && chosen.isNotEmpty()) chosen + listOf(1000,2500).map { ms ->
            BenchmarkCase("silence-$ms",source,reference="",referenceStatus="human-reviewed",silenceMs=ms)
        } else chosen
    }
    fun audio(context: Context, case: BenchmarkCase): BenchmarkAudio.Clip {
        if (case.silenceMs > 0) return BenchmarkAudio.decode(silenceWave(case.silenceMs))
        val path = checkNotNull(case.audio)
        val bytes = if (root == null) context.assets.open("benchmark/$path").use { it.readBytes() } else File(root,path).readBytes()
        check(benchmarkHash(bytes) == case.sha256) { "Benchmark recording SHA-256 mismatch: ${case.id}" }
        return BenchmarkAudio.decode(bytes).also { check(it.sha256 == case.pcmSha256) { "Normalized benchmark PCM does not match: ${case.id}" } }
    }
    companion object {
        const val MANIFEST = "benchmark-suite.json"
        fun bundled(context: Context) = parse(context.assets.open("benchmark/$MANIFEST").use { it.readBytes() })
        fun parse(bytes: ByteArray, root: File? = null): BenchmarkSuite {
            require(bytes.size in 1..(1024*1024)) { "Benchmark manifest exceeds 1 MiB" }
            val j=JSONObject(bytes.toString(Charsets.UTF_8))
            require(j.getInt("schemaVersion")==1) { "Unsupported benchmark suite version" }
            val a=j.getJSONArray("cases"); require(a.length() in 1..1000) { "Choose a suite with 1–1000 cases" }
            val cases=(0 until a.length()).map { BenchmarkCase.parse(a.getJSONObject(it)) }
            require(cases.map { it.id }.distinct().size == cases.size) { "Duplicate benchmark case ID" }
            fun strings(key:String)=j.optJSONArray(key)?.let { v -> (0 until v.length()).map(v::getString) }.orEmpty()
            val id=j.getString("id"); require(id.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) { "Invalid benchmark suite ID" }
            return BenchmarkSuite(id,j.getString("title").take(200),j.getString("revision").take(200),j.getString("license").take(200),
                j.getString("sourceUrl").take(1000),benchmarkHash(bytes),cases,strings("quickSentenceIds"),strings("fullSentenceIds"),
                j.optString("warmupSentenceId").takeIf(String::isNotBlank),root)
        }
        private fun silenceWave(ms: Int): ByteArray {
            val samples=ms*16
            val b=java.nio.ByteBuffer.allocate(44+samples*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
            return b.array()
        }
    }
}

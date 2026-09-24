package com.sal7one.transiber.benchmark

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Private, user-owned speech references. No third-party Saudi recordings are redistributed. */
internal class SaudiBenchmarkPack(private val directory: File) {
    companion object {
        const val MAX_CLIPS = 6
        val prompts = listOf(
            "وش رايك نطلع بعد المغرب؟",
            "الطلب وصل متأخر، بس الحمد لله كله تمام.",
            "لا ترسل الملف الحين، خلنا نراجعه بكرة.",
            "السعر مية وخمسة وعشرين ريال، مو مية وخمسين.",
            "أنا من الرياض وبروح جدة الأسبوع الجاي.",
            "شغّل المقطع مرة ثانية، ما سمعت آخر كلمة."
        )
    }

    private val manifest = File(directory, "saudi-speech.json")
    fun hasFiles(): Boolean = manifest.exists() || (1..MAX_CLIPS).any { File(directory, "saudi-$it.wav").exists() }

    fun load(): BenchmarkSuite? {
        if (!manifest.exists()) return null
        val suite = BenchmarkSuite.parse(manifest.readBytes(), directory)
        require(suite.id == "saudi-personal-speech" && suite.cases.size <= MAX_CLIPS &&
            suite.cases.all { it.source == "ar" && it.speech && it.referenceStatus == "human-reviewed" }) {
            "Invalid Saudi benchmark pack"
        }
        suite.cases.forEach(::validateFile)
        return suite
    }

    fun save(clip: BenchmarkAudio.Clip, reference: String): BenchmarkSuite {
        val corrected = reference.trim()
        require(corrected.isNotBlank() && corrected.length <= 500) { "Add the exact words spoken (up to 500 characters)" }
        require(clip.samples.size in 8_000..(BenchmarkAudio.MAX_SECONDS * 16_000)) {
            "Use a recording between 0.5 and 30 seconds" }
        require(BenchmarkAudio.fromPcm16(clip.samples).sha256 == clip.sha256) { "Recording PCM hash changed" }
        val previous = load()
        require((previous?.cases?.size ?: 0) < MAX_CLIPS) { "Six Saudi clips are already saved" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private benchmark folder" }
        val id = "saudi-${(previous?.cases?.size ?: 0) + 1}"
        val name = "$id.wav"
        val bytes = wav(clip.samples)
        val audioFile = File(directory, name)
        val pending = File(directory, "$name.tmp")
        pending.writeBytes(bytes)
        check(pending.renameTo(audioFile)) { "Cannot save Saudi benchmark recording" }
        val cases = previous?.cases.orEmpty() + BenchmarkCase(id, "ar", reference = corrected,
            referenceStatus = "human-reviewed", audio = name, sha256 = benchmarkHash(bytes), pcmSha256 = clip.sha256)
        writeManifest(cases)
        return checkNotNull(load())
    }

    fun clear() {
        if (manifest.exists()) check(manifest.delete()) { "Cannot clear Saudi benchmark manifest" }
        for (index in 1..MAX_CLIPS) {
            val file = File(directory, "saudi-$index.wav")
            if (file.exists()) check(file.delete()) { "Cannot delete Saudi clip $index" }
        }
    }

    private fun writeManifest(cases: List<BenchmarkCase>) {
        val json = JSONObject().put("schemaVersion", 1).put("id", "saudi-personal-speech")
            .put("title", "My Saudi Arabic speech").put("revision", "personal-1")
            .put("license", "User-owned recordings").put("sourceUrl", "")
            .put("quickSentenceIds", JSONArray(cases.map { it.id }))
            .put("fullSentenceIds", JSONArray(cases.map { it.id }))
            .put("cases", JSONArray(cases.map { it.json() }))
        val pending = File(directory, "saudi-speech.json.tmp")
        pending.writeText(json.toString())
        check(pending.renameTo(manifest)) { "Cannot save Saudi benchmark manifest" }
    }

    private fun validateFile(case: BenchmarkCase) {
        val file = File(directory, checkNotNull(case.audio))
        require(file.length() in 44..BenchmarkAudio.MAX_BYTES.toLong()) { "Missing or oversized Saudi recording: ${case.id}" }
        require(benchmarkHash(file.readBytes()) == case.sha256) { "Saudi recording changed: ${case.id}" }
    }

    private fun wav(samples: ShortArray): ByteArray {
        val size = samples.size * 2
        val output = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray()).putInt(36 + size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(size)
        samples.forEach(output::putShort)
        return output.array()
    }
}

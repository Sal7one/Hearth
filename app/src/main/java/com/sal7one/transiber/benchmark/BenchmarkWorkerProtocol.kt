package com.sal7one.transiber.benchmark

import android.content.Context
import com.sal7one.common_jni.speech.SpeechProfile
import com.sal7one.common_jni.translation.JisrDialects
import com.sal7one.common_jni.translation.TranslationCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** Binder carries only this job's identity. Audio and results stay in bounded, app-private files. */
internal object BenchmarkWorkerProtocol {
    const val HELLO = 1
    const val READY = 2
    const val RUN = 3
    const val PROGRESS = 4
    const val FINISHED = 5
    const val FAILED = 6
    const val EXIT = 7
    const val TIMED_OUT = 8
    const val PHASE = 9
    const val RELEASE = 10
    const val RELEASED = 11
    const val PROCESS_SUFFIX = ":benchmark_worker"
    const val MAX_JSON_BYTES = 1024 * 1024
    const val MAX_SAMPLES = 32
    private val jobPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val tokenPattern = Regex("[0-9a-f]{64}")

    data class Request(
        val candidate: BenchmarkCandidate, val clip: BenchmarkAudio.Clip?, val text: String,
        val referenceText: String?, val source: String, val target: String,
        val inputs: List<BenchmarkInput>, val measuredPasses: Int, val jisrDialect: String,
        val voiceApproved: Boolean = false, val attemptTimeoutSeconds: Int = 20,
    )

    fun validIdentity(job: String, token: String) = jobPattern.matches(job) && tokenPattern.matches(token)
    fun root(context: Context) = File(context.noBackupFilesDir, "benchmark-worker")
    fun directory(context: Context, id: String): File {
        require(jobPattern.matches(id)) { "Invalid benchmark worker job" }
        val root = root(context).canonicalFile
        val child = File(root, id).canonicalFile
        require(child.parentFile == root) { "Benchmark job escaped private storage" }
        return child
    }

    fun create(context: Context, request: Request): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        val dir = directory(context, id)
        check(dir.mkdirs()) { "Could not create private benchmark job" }
        try {
            validateRequest(request)
            val candidate = request.candidate
            val candidateJson = JSONObject().put("id", candidate.id).put("label", candidate.label)
                .put("route", candidate.route).put("sourceCodes", JSONArray(candidate.sourceCodes.toList()))
                .put("targetCodes", JSONArray(candidate.targetCodes.toList())).put("file", candidate.file?.let {
                    privateModel(context, it).path
                }).put("profile", candidate.profile?.id).put("translation", candidate.translation?.let { spec ->
                    require(TranslationCatalog.models.any { it == spec }) { "Unrecognized pinned translation model" }
                    spec.id
                }).put("acceleration", candidate.acceleration).put("voiceFamily", candidate.voiceFamily)
                .put("voiceId", candidate.voiceId).put("artifactFingerprint", candidate.artifactFingerprint)
                .put("artifactBytes", candidate.artifactBytes).put("recognitionLanguage", candidate.recognitionLanguage)
            val json = JSONObject().put("version", 1).put("candidate", candidateJson)
                .put("text", request.text).put("referenceText", request.referenceText)
                .put("source", request.source).put("target", request.target).put("measuredPasses", request.measuredPasses)
                .put("jisrDialect", request.jisrDialect).put("voiceApproved", request.voiceApproved)
                .put("attemptTimeoutSeconds", request.attemptTimeoutSeconds)
                .put("clip", request.clip?.let { writeAudio(dir, "clip.pcm", it) })
                .put("inputs", JSONArray(request.inputs.mapIndexed { index, input ->
                    JSONObject().put("id", input.id).put("text", input.text).put("reference", input.reference)
                        .put("silence", input.silence).put("clip", input.clip?.let { writeAudio(dir, "sample-$index.pcm", it) })
                }))
            writeJson(File(dir, "request.json"), json)
            return id to dir
        } catch (failure: Throwable) { dir.deleteRecursively(); throw failure }
    }

    fun read(context: Context, id: String): Request {
        val dir = directory(context, id)
        val json = readJson(File(dir, "request.json"))
        require(json.getInt("version") == 1) { "Unsupported benchmark worker protocol" }
        val j = json.getJSONObject("candidate")
        val candidate = BenchmarkCandidate(j.getString("id"), j.getString("label"), j.getString("route"),
            codes(j.getJSONArray("sourceCodes")), codes(j.getJSONArray("targetCodes")),
            j.nullableString("file")?.let { privateModel(context, File(it)) },
            j.nullableString("profile")?.let(SpeechProfile::fromId),
            j.nullableString("translation")?.let { value ->
                checkNotNull(TranslationCatalog.models.singleOrNull { it.id == value }) { "Unknown pinned translation model" }
            }, j.nullableString("acceleration"), j.nullableString("voiceFamily"), j.nullableString("voiceId"),
            j.nullableString("artifactFingerprint"), if (j.has("artifactBytes")) j.getLong("artifactBytes") else null,
            j.nullableString("recognitionLanguage"))
        val inputs = json.getJSONArray("inputs")
        require(inputs.length() <= MAX_SAMPLES) { "Too many benchmark samples" }
        val request = Request(candidate, json.optJSONObject("clip")?.let { readAudio(dir, "clip.pcm", it) },
            json.getString("text"), json.nullableString("referenceText"), json.getString("source"), json.getString("target"),
            (0 until inputs.length()).map { index ->
                val input = inputs.getJSONObject(index)
                BenchmarkInput(input.getString("id"), input.getString("text"), input.nullableString("reference"),
                    input.optJSONObject("clip")?.let { readAudio(dir, "sample-$index.pcm", it) }, input.getBoolean("silence"))
            }, json.getInt("measuredPasses"), json.getString("jisrDialect"), json.optBoolean("voiceApproved", false),
            json.getInt("attemptTimeoutSeconds"))
        validateRequest(request)
        return request
    }

    fun writeResult(dir: File, result: BenchmarkResult) = writeJson(File(dir, "result.json"), result.json())
    fun readResult(dir: File) = BenchmarkResult.from(readJson(File(dir, "result.json")))

    private fun validateRequest(request: Request) {
        val candidate = request.candidate
        require(candidate.profile!=null || candidate.translation!=null || candidate.voiceFamily!=null ||
            candidate.id==com.sal7one.transiber.translation.TranslationOptions.ML_KIT ||
            com.sal7one.common_jni.translation.Ct2Catalog.find(candidate.id)!=null ||
            com.sal7one.transiber.translation.MarianPackage.find(candidate.id)!=null ||
            com.sal7one.transiber.translation.MarianCascade.find(candidate.id)!=null ||
            (candidate.file!=null && (candidate.route.startsWith("Whisper /") || candidate.route.startsWith("Vosk /")))) {
            "Model registration is missing or has no supported local benchmark route"
        }
        require(candidate.id.length in 1..512 && candidate.label.length in 1..1024 && candidate.route.length in 1..1024)
        require(candidate.sourceCodes.size in 1..256 && candidate.targetCodes.size <= 256)
        require((candidate.sourceCodes + candidate.targetCodes).all { it.length in 1..24 })
        require(candidate.acceleration in setOf("cpu", "vulkan", "opencl")) { "Snapshot the execution backend before dispatch" }
        require(request.text.length <= 500 && (request.referenceText?.length ?: 0) <= 8000)
        require(request.source.length in 1..24 && request.target.length <= 24)
        require(request.measuredPasses in 3..10 && request.inputs.size <= MAX_SAMPLES)
        require(request.attemptTimeoutSeconds in 5..20) { "Benchmark hard limit cannot exceed 20 seconds" }
        require(request.jisrDialect in JisrDialects.tags) { "Invalid benchmark dialect snapshot" }
        require(request.inputs.all { it.id.length in 1..512 && it.text.length <= 500 && (it.reference?.length ?: 0) <= 8000 })
        require((candidate.voiceFamily?.length ?: 0) <= 128 && (candidate.voiceId?.length ?: 0) <= 512)
        require(candidate.task != BenchmarkTask.VOICE || request.voiceApproved) { "Voice benchmark must be approved in the application process" }
        require((candidate.artifactFingerprint?.length ?: 0) <= 65536 && (candidate.recognitionLanguage?.length ?: 0) <= 24)
    }

    private fun privateModel(context: Context, file: File): File {
        val root = context.filesDir.canonicalFile
        val model = file.canonicalFile
        require(model != root && model.path.startsWith(root.path + File.separator)) { "Benchmark models must be imported into app-private storage" }
        return model
    }
    private fun codes(array: JSONArray): Set<String> {
        require(array.length() <= 256)
        return (0 until array.length()).map { array.getString(it).also { code -> require(code.length in 1..24) } }.toSet()
    }
    private fun writeAudio(dir: File, name: String, clip: BenchmarkAudio.Clip): JSONObject {
        val checked = BenchmarkAudio.fromPcm16(clip.samples)
        require(checked.sha256 == clip.sha256) { "Benchmark audio changed before dispatch" }
        DataOutputStream(BufferedOutputStream(File(dir, name).outputStream())).use { stream ->
            clip.samples.forEach { stream.writeShort(it.toInt()) }
        }
        return JSONObject().put("samples", clip.samples.size).put("sha256", clip.sha256)
    }
    private fun readAudio(dir: File, name: String, metadata: JSONObject): BenchmarkAudio.Clip {
        val count = metadata.getInt("samples")
        require(count in 8_000..(BenchmarkAudio.MAX_SECONDS * 16_000)) { "Invalid benchmark PCM length" }
        val file = File(dir, name)
        require(file.canonicalFile.parentFile == dir.canonicalFile && file.length() == count * 2L) { "Missing or changed private benchmark audio" }
        val samples = DataInputStream(BufferedInputStream(file.inputStream())).use { stream -> ShortArray(count) { stream.readShort() } }
        val clip = BenchmarkAudio.fromPcm16(samples)
        require(clip.sha256 == metadata.getString("sha256")) { "Private benchmark audio failed integrity verification" }
        return clip
    }
    private fun writeJson(file: File, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_JSON_BYTES) { "Benchmark job metadata exceeds its bound" }
        val staged = File(file.parentFile, file.name + ".tmp")
        staged.writeBytes(bytes)
        check(staged.renameTo(file)) { "Could not publish benchmark job metadata" }
    }
    private fun readJson(file: File): JSONObject {
        val parent = checkNotNull(file.parentFile) { "Benchmark job metadata has no private parent" }
        require(file.canonicalFile.parentFile == parent.canonicalFile) { "Benchmark job metadata escaped its private directory" }
        require(file.isFile && file.length() in 1..MAX_JSON_BYTES.toLong()) { "Missing or oversized benchmark job metadata" }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_JSON_BYTES) { "Benchmark job metadata exceeds its bound" }
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= MAX_JSON_BYTES) { "Benchmark job metadata exceeds its bound" }
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }
    private fun JSONObject.nullableString(name: String) = if (has(name) && !isNull(name)) getString(name) else null
}

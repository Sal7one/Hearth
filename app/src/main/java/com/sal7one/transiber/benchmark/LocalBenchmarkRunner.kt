package com.sal7one.transiber.benchmark

import android.content.Context
import android.os.Build
import com.sal7one.transiber.BuildConfig
import com.sal7one.common_jni.engine.SttEngine
import com.sal7one.common_jni.engine.vosk.VoskEngine
import com.sal7one.common_jni.engine.whisper.WhisperEngine
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.common_jni.model.LanguageConfig
import com.sal7one.common_jni.model.SttConfig
import com.sal7one.common_jni.speech.*
import com.sal7one.common_jni.translation.*
import com.sal7one.transiber.caption.LocalSpeechModels
import com.sal7one.transiber.caption.label
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.runtime.LocalWorkGate
import com.sal7one.transiber.translation.LocalTranslationModels
import com.sal7one.transiber.translation.PlatformTranslation
import com.sal7one.transiber.translation.TranslationOptions
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class BenchmarkCandidate(
    val id: String, val label: String, val route: String,
    val sourceCodes: Set<String>, val targetCodes: Set<String> = emptySet(),
    val file: File? = null, val profile: SpeechProfile? = null, val translation: TranslationModelSpec? = null,
)
internal data class BenchmarkResult(
    val runId: String, val timestamp: Long, val model: String, val identity: String, val route: String,
    val inputHash: String, val source: String, val target: String, val audioMs: Long,
    val loadMs: Double, val computeMs: List<Double>, val texts: List<String>, val runtime: String,
    val firstTextMs: List<Double>, val device: String, val error: String? = null,
) {
    fun json() = JSONObject().put("runId", runId).put("timestamp", timestamp).put("model", model)
        .put("identity", identity).put("route", route).put("inputHash", inputHash).put("source", source).put("target", target)
        .put("audioMs", audioMs).put("loadMs", loadMs).put("computeMs", JSONArray(computeMs)).put("texts", JSONArray(texts))
        .put("runtime", runtime).put("firstTextMs", JSONArray(firstTextMs)).put("device", device).put("error", error)
    companion object {
        fun from(j: JSONObject) = BenchmarkResult(
            j.getString("runId"), j.getLong("timestamp"), j.getString("model"), j.getString("identity"), j.getString("route"),
            j.getString("inputHash"), j.getString("source"), j.getString("target"), j.getLong("audioMs"), j.getDouble("loadMs"),
            j.getJSONArray("computeMs").let { a -> (0 until a.length()).map(a::getDouble) },
            j.getJSONArray("texts").let { a -> (0 until a.length()).map(a::getString) }, j.getString("runtime"),
            j.getJSONArray("firstTextMs").let { a -> (0 until a.length()).map(a::getDouble) }, j.getString("device"),
            if (j.has("error") && !j.isNull("error")) j.getString("error") else null)
    }
}
internal class BenchmarkResults(context: Context) {
    private val file = File(context.filesDir, "benchmark-results.json")
    fun load(): List<BenchmarkResult> {
        if (!file.exists()) return emptyList()
        require(file.length() <= 8 * 1024 * 1024) { "Benchmark results exceed the local storage limit" }
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { BenchmarkResult.from(array.getJSONObject(it)) }
    }
    fun append(result: BenchmarkResult) {
        val records = (listOf(result) + load()).take(40)
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(JSONArray(records.map { it.json() }).toString(2).toByteArray())
            atomic.finishWrite(stream)
        } catch (e: Throwable) { atomic.failWrite(stream); throw e }
    }
    fun clear() { if (file.exists()) check(file.delete()) { "Cannot clear benchmark history" } }
    fun export(): String = JSONArray(load().map { it.json() }).toString(2)
}

/** Sequential production runtimes. A fresh session is closed before the next model is loaded. */
internal class LocalBenchmarkRunner(private val context: Context) {
    @Volatile private var cancelNative: (() -> Unit)? = null
    fun cancel() { cancelNative?.invoke() }

    suspend fun candidates(): List<BenchmarkCandidate> = withContext(Dispatchers.IO) {
        val speech = LocalSpeechModels(File(context.filesDir, "speech-models")).list().map { model ->
            BenchmarkCandidate(model.id, model.profile.label + " · " + model.id.takeLast(6),
                "Speech / ${model.profile.capabilities.streaming}", model.profile.capabilities.sourceLanguageHints + (if (model.profile.capabilities.sourceLanguageHints.size > 1) setOf("auto") else emptySet()),
                file = model.root, profile = model.profile)
        }
        val legacy = ModelRegistry.getInstance(context).registeredModels.value.filter {
            it.isValid && it.engineType in setOf(ModelEngineType.WHISPER, ModelEngineType.VOSK)
        }.map { model ->
            val whisper = model.engineType == ModelEngineType.WHISPER
            val vocab = if (whisper) File(model.path).inputStream().use { com.sal7one.transiber.caption.whisperVocabulary(it) } else null
            val codes = if (!whisper) setOf("model") else when (vocab) {
                51864 -> setOf("en")
                51865 -> (LanguageCatalog.whisperCodes - "yue") + "auto"
                51866 -> LanguageCatalog.whisperCodes + "auto"
                else -> setOf("auto")
            }
            BenchmarkCandidate(model.id, model.name, if (whisper) "Whisper / batch" else "Vosk / batch", codes)
        }
        val translations = LocalTranslationModels(File(context.filesDir, "translation-models"))
        val gguf = translations.installed().map { spec ->
            BenchmarkCandidate(spec.id, spec.label, "Translation / GGUF", spec.sourceLanguages, spec.targetLanguages,
                file = translations.file(spec), translation = spec)
        }
        val packs = if (PlatformTranslation.available) PlatformTranslation.installed() else emptySet()
        val mlKit = if (packs.size >= 2) listOf(BenchmarkCandidate(TranslationOptions.ML_KIT, "ML Kit · installed packs",
            "Translation / ML Kit", packs, packs)) else emptyList()
        speech + legacy + gguf + mlKit
    }

    suspend fun run(
        selected: List<BenchmarkCandidate>, clip: BenchmarkAudio.Clip?, text: String,
        source: String, target: String, progress: (String) -> Unit, onResult: (BenchmarkResult) -> Unit,
    ) {
        require(selected.isNotEmpty() && selected.size <= 12) { "Choose between 1 and 12 installed models" }
        val translating = selected.first().targetCodes.isNotEmpty()
        require(selected.all { it.targetCodes.isNotEmpty() == translating }) { "Compare speech and translation separately" }
        if (translating) {
            require(text.isNotBlank() && text.length <= 500) { "Use 1–500 characters of corrected source text" }
            require(source != target) { "Choose different source and translation languages" }
        } else require(clip != null) { "Choose a WAV recording first" }
        val lease = LocalWorkGate.acquire("Benchmark")
        try {
            val runId = UUID.randomUUID().toString()
            val hash = clip?.takeUnless { translating }?.sha256 ?: MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            for (candidate in selected) {
                currentCoroutineContext().ensureActive()
                progress("${candidate.label}: loading and verifying")
                var load = 0.0; var runtime = "Bundled app runtime"
                val elapsed = mutableListOf<Double>(); val texts = mutableListOf<String>(); val first = mutableListOf<Double>()
                var speech: SpeechSession? = null; var legacy: SttEngine? = null; var translator: CancellableTextTranslator? = null
                var failure: String? = null
                val loading = System.nanoTime()
                try {
                    require(source in candidate.sourceCodes || candidate.sourceCodes == setOf("model")) { "${candidate.label} cannot use spoken language $source" }
                    when {
                        candidate.profile != null -> {
                            val backend = SpeechRuntime()
                            val availability = backend.availability(candidate.profile.backend)
                            check(availability.available) { availability.error ?: "Speech runtime unavailable" }
                            runtime = availability.revision ?: "Runtime revision unavailable"
                            val manifestHash = withContext(Dispatchers.IO) {
                                val manifest = File(checkNotNull(candidate.file), SpeechModelPackage.MANIFEST)
                                require(manifest.length() in 1..65536) { "Missing or oversized speech manifest" }
                                val bytes = manifest.readBytes()
                                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                            }
                            runtime += "; ${candidate.profile.id}; package manifest SHA-256 $manifestHash; defaults: threads 4 where supported, right context 3, utterance 4000 ms"
                            speech = backend.open(checkNotNull(candidate.file), SpeechOptions(sourceLanguage = source))
                        }
                        candidate.translation != null -> {
                            require(candidate.translation.supports(source, target)) { "${candidate.label} does not support $source → $target" }
                            // Assign inside IO: cancellation at dispatch return must not leak the created handle.
                            withContext(Dispatchers.IO) { translator = LocalTranslationSession.open(checkNotNull(candidate.file), candidate.translation) }
                            cancelNative = { translator?.cancel() }
                            runtime = "Pinned GGUF ${candidate.translation.revision}; SHA-256 ${candidate.translation.sha256}"
                        }
                        candidate.id == TranslationOptions.ML_KIT -> {
                            require(target in candidate.targetCodes) { "Download the $target ML Kit language pack first" }
                            translator = PlatformTranslation.open(); cancelNative = { translator?.cancel() }
                            runtime = "ML Kit SDK; first call includes deferred client/model initialization"
                        }
                        else -> {
                            val registry = ModelRegistry.getInstance(context)
                            val model = checkNotNull(registry.getModel(candidate.id)) { "Model registration is missing" }
                            val engine: SttEngine = if (model.engineType == ModelEngineType.WHISPER) WhisperEngine() else VoskEngine()
                            legacy = engine
                            cancelNative = { when (engine) { is WhisperEngine -> engine.cancel(); is VoskEngine -> engine.cancel() } }
                            val provider = checkNotNull(registry.getProvider(candidate.id))
                            withContext(Dispatchers.IO) {
                                val path = provider.getModelPath()
                                engine.initialize(path, SttConfig.forBatch().copy(
                                    numThreads = if (engine is WhisperEngine) 8 else 2,
                                    language = if (source in setOf("auto", "model")) LanguageConfig.Auto else LanguageConfig.Specific(source),
                                    modelSha256 = checkNotNull(provider.getModelDigest()).hex,
                                )).getOrThrow()
                            }
                            runtime = "Model SHA-256 ${model.digest?.hex}; ${if (engine is WhisperEngine) 8 else 2} threads"
                        }
                    }
                    load = (System.nanoTime() - loading) / 1e6
                    repeat(3) { pass ->
                        currentCoroutineContext().ensureActive()
                        progress("${candidate.label}: ${if (pass == 0) "first inference" else "warm pass $pass of 2"}")
                        val start = System.nanoTime()
                        var firstMs: Double? = null
                        val output = when {
                            speech != null -> {
                                val session = checkNotNull(speech)
                                if (pass > 0) session.reset()
                                val finals = linkedMapOf<Long, String>()
                                fun collect(update: SpeechUpdate) {
                                    update.transcripts.forEach { t ->
                                        if (firstMs == null && t.text.isNotBlank()) firstMs = (System.nanoTime() - start) / 1e6
                                        if (t.isFinal) finals[t.utteranceId] = t.text
                                    }
                                }
                                val samples = checkNotNull(clip).samples
                                var offset = 0
                                // Same 20 ms frames as capture; accelerated replay measures compute, not live latency.
                                while (offset < samples.size) {
                                    currentCoroutineContext().ensureActive()
                                    val end = minOf(offset + 320, samples.size)
                                    collect(session.accept(samples.copyOfRange(offset, end), offset.toLong())); offset = end
                                }
                                collect(session.finish())
                                finals.values.filter { it.isNotBlank() }.joinToString(" ")
                            }
                            translator != null -> checkNotNull(translator).translate(text, TranslationDirection(source, target))
                            else -> {
                                val engine = checkNotNull(legacy)
                                if (pass > 0) engine.reset().getOrThrow()
                                engine.transcribeBatch(checkNotNull(clip).samples, 16000).getOrThrow().fullText
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        val ms = (System.nanoTime() - start) / 1e6
                        elapsed += ms; first += firstMs ?: ms; texts += output.take(8000)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failure = e.message ?: e.toString() }
                catch (e: LinkageError) { failure = e.message ?: e.toString() }
                finally {
                    if (load == 0.0) load = (System.nanoTime() - loading) / 1e6
                    withContext(NonCancellable + Dispatchers.IO) {
                        cancelNative = null
                        speech?.close(); legacy?.release(); translator?.close()
                    }
                }
                val result = BenchmarkResult(runId, System.currentTimeMillis(), candidate.label, candidate.id, candidate.route,
                    hash, source, if (translating) target else "", if (translating) 0 else checkNotNull(clip).durationMs,
                    load, elapsed, texts, runtime, first,
                    "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE}; API ${Build.VERSION.SDK_INT}; app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", failure)
                withContext(Dispatchers.IO) { BenchmarkResults(context).append(result) }
                onResult(result)
            }
        } finally { cancelNative = null; lease.close() }
    }
}

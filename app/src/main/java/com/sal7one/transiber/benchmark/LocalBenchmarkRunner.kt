package com.sal7one.transiber.benchmark

import android.content.Context
import android.os.Build
import com.sal7one.transiber.BuildConfig
import com.sal7one.hearth.tts.ArticleReader
import com.sal7one.transiber.voice.LocalVoiceFamilies
import com.sal7one.transiber.voice.VoiceCatalog
import com.sal7one.transiber.voice.VoiceChoice
import com.sal7one.common_jni.engine.SttEngine
import com.sal7one.common_jni.engine.CancellableSttEngine
import com.sal7one.common_jni.engine.vosk.VoskEngine
import com.sal7one.common_jni.engine.whisper.WhisperEngine
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.common_jni.model.LanguageConfig
import com.sal7one.common_jni.model.SttConfig
import com.sal7one.common_jni.speech.*
import com.sal7one.common_jni.translation.*
import com.sal7one.transiber.caption.SpeechAccelerationStore
import com.sal7one.transiber.caption.captionEngine
import com.sal7one.transiber.caption.CaptionEngineChoice
import com.sal7one.common_jni.engine.whisper.WhisperAcceleration
import com.sal7one.transiber.caption.LocalSpeechModels
import com.sal7one.transiber.caption.label
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.runtime.LocalWorkGate
import com.sal7one.transiber.translation.LocalTranslationModels
import com.sal7one.transiber.translation.PlatformTranslation
import com.sal7one.transiber.translation.TranslationOptions
import com.sal7one.transiber.translation.MarianPackage
import com.sal7one.transiber.translation.MarianCascade
import com.sal7one.transiber.translation.ConversationTranslationSettings
import com.sal7one.common_jni.marian.MarianTranslationSession
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal enum class BenchmarkTask { SPEECH, TRANSLATION, VOICE }

internal data class BenchmarkCandidate(
    val id: String, val label: String, val route: String,
    val sourceCodes: Set<String>, val targetCodes: Set<String> = emptySet(),
    val file: File? = null, val profile: SpeechProfile? = null, val translation: TranslationModelSpec? = null,
    val acceleration: String? = null,
    val voiceFamily: String? = null, val voiceId: String? = null,
    val artifactFingerprint: String? = null, val artifactBytes: Long? = null,
    /** Decoder selection; [sourceCodes] still describes the recording's supported languages. */
    val recognitionLanguage: String? = null,
) {
    val task: BenchmarkTask get() = when {
        voiceFamily != null -> BenchmarkTask.VOICE
        targetCodes.isNotEmpty() -> BenchmarkTask.TRANSLATION
        else -> BenchmarkTask.SPEECH
    }
}
internal data class BenchmarkInput(
    val id: String,
    val text: String = "",
    val reference: String? = null,
    val clip: BenchmarkAudio.Clip? = null,
    val silence: Boolean = false,
)
internal data class BenchmarkSampleResult(
    val id: String, val text: String, val reference: String?, val computeMs: Double,
    val firstTextMs: Double, val audioMs: Long, val silence: Boolean, val sourceText: String? = null,
) {
    fun json() = JSONObject().put("id", id).put("text", text).put("reference", reference ?: JSONObject.NULL)
        .put("computeMs", computeMs).put("firstTextMs", firstTextMs).put("audioMs", audioMs).put("silence", silence)
        .put("sourceText", sourceText ?: JSONObject.NULL)
    companion object {
        fun from(j: JSONObject) = BenchmarkSampleResult(j.getString("id"), j.optString("text"),
            if (j.has("reference") && !j.isNull("reference")) j.getString("reference") else null,
            j.optDouble("computeMs"), j.optDouble("firstTextMs"), j.optLong("audioMs"), j.optBoolean("silence"),
            if (j.has("sourceText") && !j.isNull("sourceText")) j.getString("sourceText") else null)
    }
}
internal data class BenchmarkResult(
    val runId: String, val timestamp: Long, val model: String, val identity: String, val route: String,
    val inputHash: String, val source: String, val target: String, val audioMs: Long,
    val loadMs: Double, val computeMs: List<Double>, val texts: List<String>, val runtime: String,
    val firstTextMs: List<Double>, val device: String, val error: String? = null,
    val referenceText: String? = null, val wordErrorRate: Double? = null, val characterErrorRate: Double? = null,
    val scoringNormalization: String? = null,
    val translationChrf: Double? = null, val samples: List<BenchmarkSampleResult> = emptyList(),
    val protocolVersion: Int = 1,
    val task: String = if (target.isBlank()) "speech" else "translation",
    val requestedAcceleration: String? = null, val actualAcceleration: String? = null,
    val modelBytes: Long? = null, val configurationHash: String? = null,
    val audioMsByPass: List<Long> = emptyList(), val environment: BenchmarkEnvironment? = null,
    /** Recognizer control, independently of [source], which always identifies the sample. */
    val languageControl: String? = null, val recognitionLanguage: String? = null,
    val terminationReason: String? = null,
    val attemptTimeoutSeconds: Int? = null, val attemptElapsedMs: Long? = null,
) {
    fun json() = JSONObject().put("runId", runId).put("timestamp", timestamp).put("model", model)
        .put("identity", identity).put("route", route).put("inputHash", inputHash).put("source", source).put("target", target)
        .put("audioMs", audioMs).put("loadMs", loadMs).put("computeMs", JSONArray(computeMs)).put("texts", JSONArray(texts))
        .put("runtime", runtime).put("firstTextMs", JSONArray(firstTextMs)).put("device", device).put("error", error)
        .put("referenceText", referenceText ?: JSONObject.NULL)
        .put("wordErrorRate", wordErrorRate ?: JSONObject.NULL).put("characterErrorRate", characterErrorRate ?: JSONObject.NULL)
        .put("scoringNormalization", scoringNormalization ?: JSONObject.NULL)
        .put("translationChrf", translationChrf ?: JSONObject.NULL).put("samples", JSONArray(samples.map { it.json() }))
        .put("protocolVersion", protocolVersion).put("task", task)
        .put("requestedAcceleration", requestedAcceleration ?: JSONObject.NULL)
        .put("actualAcceleration", actualAcceleration ?: JSONObject.NULL)
        .put("modelBytes", modelBytes ?: JSONObject.NULL).put("configurationHash", configurationHash ?: JSONObject.NULL)
        .put("audioMsByPass", JSONArray(audioMsByPass)).put("environment", environment?.json() ?: JSONObject.NULL)
        .put("languageControl", languageControl ?: JSONObject.NULL)
        .put("recognitionLanguage", recognitionLanguage ?: JSONObject.NULL)
        .put("terminationReason", terminationReason ?: JSONObject.NULL)
        .put("attemptTimeoutSeconds", attemptTimeoutSeconds ?: JSONObject.NULL)
        .put("attemptElapsedMs", attemptElapsedMs ?: JSONObject.NULL)
    companion object {
        fun from(j: JSONObject) = BenchmarkResult(
            j.getString("runId"), j.getLong("timestamp"), j.getString("model"), j.getString("identity"), j.getString("route"),
            j.getString("inputHash"), j.getString("source"), j.getString("target"), j.getLong("audioMs"), j.getDouble("loadMs"),
            j.getJSONArray("computeMs").let { a -> (0 until a.length()).map(a::getDouble) },
            j.getJSONArray("texts").let { a -> (0 until a.length()).map(a::getString) }, j.getString("runtime"),
            j.getJSONArray("firstTextMs").let { a -> (0 until a.length()).map(a::getDouble) }, j.getString("device"),
            if (j.has("error") && !j.isNull("error")) j.getString("error") else null,
            if (j.has("referenceText") && !j.isNull("referenceText")) j.getString("referenceText") else null,
            j.optDouble("wordErrorRate", Double.NaN).takeIf(Double::isFinite),
            j.optDouble("characterErrorRate", Double.NaN).takeIf(Double::isFinite),
            if (j.has("scoringNormalization") && !j.isNull("scoringNormalization")) j.getString("scoringNormalization") else null,
            j.optDouble("translationChrf", Double.NaN).takeIf(Double::isFinite),
            j.optJSONArray("samples")?.let { a -> (0 until a.length()).map { BenchmarkSampleResult.from(a.getJSONObject(it)) } }.orEmpty(),
            j.optInt("protocolVersion", 1),
            j.optString("task", if (j.getString("target").isBlank()) "speech" else "translation"),
            if (j.has("requestedAcceleration") && !j.isNull("requestedAcceleration")) j.getString("requestedAcceleration") else null,
            if (j.has("actualAcceleration") && !j.isNull("actualAcceleration")) j.getString("actualAcceleration") else null,
            if (j.has("modelBytes") && !j.isNull("modelBytes")) j.getLong("modelBytes") else null,
            if (j.has("configurationHash") && !j.isNull("configurationHash")) j.getString("configurationHash") else null,
            j.optJSONArray("audioMsByPass")?.let { a -> (0 until a.length()).map(a::getLong) }.orEmpty(),
            j.optJSONObject("environment")?.let(BenchmarkEnvironment::from),
            if (j.has("languageControl") && !j.isNull("languageControl")) j.getString("languageControl") else null,
            if (j.has("recognitionLanguage") && !j.isNull("recognitionLanguage")) j.getString("recognitionLanguage") else null,
            if (j.has("terminationReason") && !j.isNull("terminationReason")) j.getString("terminationReason") else null,
            if(j.has("attemptTimeoutSeconds") && !j.isNull("attemptTimeoutSeconds"))j.getInt("attemptTimeoutSeconds") else null,
            if(j.has("attemptElapsedMs") && !j.isNull("attemptElapsedMs"))j.getLong("attemptElapsedMs") else null)
    }
}
/** Sequential production runtimes. A fresh session is closed before the next model is loaded. */
internal class LocalBenchmarkRunner(private val context: Context) {
    private val mutableDiscoveryIssues = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val discoveryIssues: kotlinx.coroutines.flow.StateFlow<List<String>> = mutableDiscoveryIssues
    @Volatile private var cancelNative: (() -> Unit)? = null
    fun cancel() { cancelNative?.invoke() }

    suspend fun candidates(): List<BenchmarkCandidate> = withContext(Dispatchers.IO) {
        if (!com.sal7one.transiber.runtime.LocalModelPolicy.allowed(context)) return@withContext emptyList()
        val discovery = BenchmarkDiscovery()
        val packages = LocalSpeechModels(File(context.filesDir, "speech-models")).inspect()
        packages.issues.forEach(discovery::report)
        val speech = packages.models.mapNotNull { model -> discovery.inspect(model.profile.label) {
            BenchmarkCandidate(model.id, model.profile.label + " · " + model.id.takeLast(6),
                "Speech / ${model.profile.capabilities.streaming}", model.profile.capabilities.sourceLanguages + (if (model.profile.capabilities.automaticSourceLanguage) setOf("auto") else emptySet()),
                file = model.root, profile = model.profile,
                artifactFingerprint = benchmarkHash(File(model.root, SpeechModelPackage.MANIFEST).readBytes()))
        } }
        val legacy = ModelRegistry.getInstance(context).registeredModels.value.filter {
            it.isValid && it.engineType in setOf(ModelEngineType.WHISPER, ModelEngineType.VOSK)
        }.mapNotNull { model -> discovery.inspect(model.name) {
            val whisper = model.engineType == ModelEngineType.WHISPER
            val vocab = if (whisper) File(model.path).inputStream().use { com.sal7one.transiber.caption.whisperVocabulary(it) } else null
            val codes = if (!whisper) BenchmarkLanguagePlan.voskLanguages(model.digest?.hex) else when (vocab) {
                51864 -> setOf("en")
                51865 -> (LanguageCatalog.whisperCodes - "yue") + "auto"
                51866 -> LanguageCatalog.whisperCodes + "auto"
                else -> setOf("auto")
            }
            BenchmarkCandidate(model.id, model.name, if (whisper) "Whisper / batch" else "Vosk / batch", codes, file = File(model.path),
                artifactFingerprint = model.digest?.hex)
        } }
        val translations = LocalTranslationModels(File(context.filesDir, "translation-models"))
        val gguf = translations.installed().map { spec ->
            BenchmarkCandidate(spec.id, spec.label, "Translation / GGUF", spec.sourceLanguages, spec.targetLanguages,
                file = translations.file(spec), translation = spec, artifactFingerprint = spec.sha256)
        }
        val packs = if (PlatformTranslation.available) PlatformTranslation.installed() else emptySet()
        val mlKit = if (packs.size >= 2) listOf(BenchmarkCandidate(TranslationOptions.ML_KIT, "ML Kit · installed packs",
            "Translation / ML Kit", packs, packs)) else emptyList()
        val marian = MarianPackage.pairs.mapNotNull { pair -> MarianPackage.installed(context, pair)?.let { model ->
            BenchmarkCandidate(pair.id, TranslationOptions.label(pair.id), "Translation / ONNX",
                setOf(pair.source), setOf(pair.target), file = File(model.path), artifactFingerprint = pair.treeSha256)
        } }
        val cascades = MarianCascade.routes.filter { MarianCascade.installed(context, it) }.map { route ->
            BenchmarkCandidate(route.id, TranslationOptions.label(route.id), "Translation / ONNX via English",
                setOf(route.source), setOf(route.target),
                artifactFingerprint = route.first.treeSha256 + route.second.treeSha256)
        }
        val ct2=com.sal7one.common_jni.translation.Ct2Catalog.models.filter { com.sal7one.transiber.translation.Ct2Package.installed(context,it) }.map {
            BenchmarkCandidate(it.id,it.selectionLabel,"Translation / CTranslate2 CPU",it.sources,it.targets,file=com.sal7one.transiber.translation.Ct2Package.directory(context,it),
                artifactFingerprint=it.files.joinToString(":") { file -> file.sha256 })
        }
        val voices = LocalVoiceFamilies.providers.flatMap { family -> discovery.inspect(family.label) {
            family.voices.filter { voice -> LocalVoiceFamilies.ready(context, VoiceChoice(voice = voice, family = family.id)) }
                .map { voice -> BenchmarkCandidate("voice:${family.id}:$voice", "${family.label} · $voice", "Voice / local CPU",
                    family.languages, file = LocalVoiceFamilies.directory(context, family.id), voiceFamily = family.id, voiceId = voice,
                    artifactFingerprint = VoiceCatalog.familyAssets(family.id).joinToString(":") { it.installedSha256 },
                    artifactBytes = if (family.id == "supertonic") (VoiceCatalog.core + VoiceCatalog.file("$voice.json")).sumOf { asset ->
                        File(context.filesDir, "voice-models/${asset.filename}").length()
                    } else null) }
        }.orEmpty() }
        mutableDiscoveryIssues.value = discovery.issues
        speech + legacy + gguf + mlKit + marian + cascades + ct2 + voices
    }

    suspend fun run(
        selected: List<BenchmarkCandidate>, clip: BenchmarkAudio.Clip?, text: String, referenceText: String?,
        source: String, target: String, benchmarkInputs: List<BenchmarkInput> = emptyList(),
        compareAcceleration: Boolean = false, resumeCompleted: Boolean = false, measuredPasses: Int = 3,
        persistResults: Boolean = true, workerJisrDialect: String? = null,
        workerVoiceApproved: Boolean = false, attemptTimeoutSeconds: Int = 20,
        onPhase: (BenchmarkWorkerPhase) -> Unit = {},
        progress: (String) -> Unit, onResult: (BenchmarkResult) -> Unit,
    ) = withContext(Dispatchers.Default) {
        com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "model benchmarking")
        suspend fun reportProgress(message: String) = withContext(Dispatchers.Main.immediate) { progress(message) }
        require(selected.isNotEmpty()) { "Choose at least one installed model or voice" }
        require(measuredPasses in 3..10) { "Choose 3–10 passes including the first pass" }
        val task = selected.first().task
        val translating = task == BenchmarkTask.TRANSLATION
        val synthesizing = task == BenchmarkTask.VOICE
        require(selected.all { it.task == task }) { "Compare speech, translation and voices separately" }
        val planned = BenchmarkAccelerationPlan.resolve(context,selected,compareAcceleration)
        if (benchmarkInputs.isNotEmpty()) {
            require(benchmarkInputs.size <= 32) { "Choose a benchmark set with no more than 32 samples" }
            require(benchmarkInputs.all { if (translating || synthesizing) it.text.isNotBlank() && it.text.length <= 500 && it.clip == null else it.clip != null }) {
                "Benchmark samples do not match the selected task"
            }
        } else if (translating || synthesizing) {
            require(text.isNotBlank() && text.length <= 500) { "Use 1–500 characters of source text" }
            if (translating) require(source != target) { "Choose different source and translation languages" }
        } else require(clip != null) { "Choose a WAV recording first" }
        require(referenceText == null || referenceText.length <= 8000) { "Reference text must be 8,000 characters or fewer" }
        val jisrDialect = workerJisrDialect ?: ConversationTranslationSettings.jisrDialect(context)
        val lease = LocalWorkGate.acquire("Benchmark")
        try {
            val runId = UUID.randomUUID().toString()
            val hash = BenchmarkAttemptRecord.inputHash(benchmarkInputs,clip,text,referenceText,source,target,task)
            val device = BenchmarkAttemptRecord.device()
            val history = if (resumeCompleted) withContext(Dispatchers.IO) { BenchmarkResults(context).load() } else emptyList()
            val resultTarget = if (translating) target else ""
            var completed = 0
            for ((modelIndex, candidate) in planned.withIndex()) {
                currentCoroutineContext().ensureActive()
                val configurationHash = BenchmarkAttemptRecord.configuration(context,candidate,measuredPasses,jisrDialect,attemptTimeoutSeconds)
                if (history.any { BenchmarkExecutionMetadata.canResume(it, candidate, hash, configurationHash, source, resultTarget, device, measuredPasses) }) {
                    completed++
                    reportProgress("$completed/${planned.size} · ${candidate.label}: kept completed result")
                    continue
                }
                suspend fun step(message: String) = reportProgress("${modelIndex + 1}/${planned.size} · ${candidate.label}: $message")
                step("loading and verifying")
                var load = 0.0; var runtime = "Bundled app runtime"
                val elapsed = mutableListOf<Double>(); val texts = mutableListOf<String>(); val first = mutableListOf<Double>()
                val audioDurations = mutableListOf<Long>()
                val environmentBefore = BenchmarkEnvironmentSnapshot.capture(context)
                var environmentAfter = environmentBefore
                var speech: SpeechSession? = null; var legacy: SttEngine? = null; var translator: CancellableTextTranslator? = null
                var voiceReader: ArticleReader? = null
                var actualAcceleration: String? = null
                var recognition: BenchmarkRecognitionSetting? = null
                var failure: String? = null
                var terminationReason: String? = null
                var executedAcceleration: String? = candidate.acceleration
                val samples = mutableListOf<BenchmarkSampleResult>()
                val sampleBudget = BenchmarkSampleBudget(attemptTimeoutSeconds)
                val rawInputs = benchmarkInputs.ifEmpty {
                    listOf(BenchmarkInput("custom", text, referenceText, clip = clip.takeUnless { translating || synthesizing }))
                }
                // FLEURS English includes valid but unusually low-level PCM. Normalize every
                // comparison route identically (also used by the macOS host harness), leaving
                // digital silence untouched so the false-positive checks remain meaningful.
                val inputs = if (translating || synthesizing) rawInputs else rawInputs.map { input ->
                    input.copy(clip = input.clip?.let { audio ->
                        audio.copy(samples = BenchmarkAudio.normalizeForComparison(audio.samples))
                    })
                }
                var totalAudioMs = if (translating || synthesizing) 0L else inputs.sumOf { it.clip?.durationMs ?: 0L }
                val allReferences = inputs.filterNot { it.silence }.mapNotNull { it.reference }
                val combinedReference = allReferences.takeIf { it.isNotEmpty() }?.joinToString(" ")
                val loading = System.nanoTime()
                try {
                    onPhase(BenchmarkWorkerPhase.LOAD)
                    require(source in candidate.sourceCodes || candidate.sourceCodes == setOf("model")) { "${candidate.label} cannot use spoken language $source" }
                    recognition = BenchmarkLanguagePlan.recognition(candidate, source)
                    val recognitionLanguage = candidate.recognitionLanguage ?: source
                    when {
                        synthesizing -> {
                            val family = checkNotNull(candidate.voiceFamily)
                            val choice = VoiceChoice(voice = checkNotNull(candidate.voiceId), family = family)
                            val coroutineContext = currentCoroutineContext()
                            withContext(Dispatchers.IO) {
                                if(workerVoiceApproved) {
                                    // The parent checked the experimental opt-in before IPC.
                                    // Verify the same pinned package without opening a second
                                    // process-local DataStore writer in the native worker.
                                    com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context,"model benchmarking")
                                    val provider=checkNotNull(LocalVoiceFamilies.find(family))
                                    check(provider.runtimeAvailable()) {"${provider.label} runtime is unavailable"}
                                    require(choice.voice in provider.voices) {"Unsupported voice: ${choice.voice}"}
                                    provider.verifyPackage(LocalVoiceFamilies.directory(context,family),choice.voice) {coroutineContext.ensureActive()}
                                } else LocalVoiceFamilies.verify(context, choice) { coroutineContext.ensureActive() }
                                voiceReader = ArticleReader(LocalVoiceFamilies.engine(context, family))
                            }
                            cancelNative = { voiceReader?.stop() }
                            runtime = "CPU · Hearth local TTS ${LocalVoiceFamilies.label(family)}; pinned components verified; voice ${candidate.voiceId}; rate 1; quality steps 5; bounded PCM consumed without playback; first pass includes deferred native initialization"
                            actualAcceleration = "cpu"
                        }
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
                            check(candidate.artifactFingerprint==null || manifestHash.equals(candidate.artifactFingerprint,true)) {
                                "The installed speech package changed while queued. Refresh models before benchmarking it."
                            }
                            runtime += "; ${candidate.profile.id}; package manifest SHA-256 $manifestHash; defaults: threads 4 where supported, right context 3, utterance 4000 ms"
                            val acceleration = candidate.acceleration ?: SpeechAccelerationStore.current(context, candidate.profile.captionEngine)
                            executedAcceleration = acceleration
                            runtime += "; requested $acceleration; initialization did not complete"
                            speech = backend.open(checkNotNull(candidate.file), SpeechOptions(sourceLanguage = recognitionLanguage,
                                acceleration = SpeechAcceleration.entries.first { it.id == acceleration },
                                gpuCacheDirectory = File(context.cacheDir, "speech-opencl")))
                            runtime = runtime.removeSuffix("; initialization did not complete") + "; ${speech?.executionInfo}"
                            actualAcceleration = BenchmarkExecutionMetadata.actualAcceleration(speech?.executionInfo)
                        }
                        candidate.translation != null -> {
                            require(candidate.translation.supports(source, target)) { "${candidate.label} does not support $source → $target" }
                            // Assign inside IO: cancellation at dispatch return must not leak the created handle.
                            val acceleration = candidate.acceleration?.let(TranslationAcceleration::fromId)
                                ?: ConversationTranslationSettings.acceleration(context)
                            executedAcceleration = acceleration.id
                            runtime = "Pinned GGUF ${candidate.translation.revision}; SHA-256 ${candidate.translation.sha256}; requested ${acceleration.id}; initialization did not complete"
                            withContext(Dispatchers.IO) { translator = LocalTranslationSession.open(checkNotNull(candidate.file), candidate.translation, acceleration, File(context.cacheDir, "translation-opencl")) }
                            cancelNative = { translator?.cancel() }
                            runtime = "Pinned GGUF ${candidate.translation.revision}; SHA-256 ${candidate.translation.sha256}; requested ${acceleration.id}; ${translator?.executionInfo}"
                            actualAcceleration = BenchmarkExecutionMetadata.actualAcceleration(translator?.executionInfo)
                        }
                        com.sal7one.common_jni.translation.Ct2Catalog.find(candidate.id)!=null -> {
                            val spec=checkNotNull(com.sal7one.common_jni.translation.Ct2Catalog.find(candidate.id))
                            require(spec.supports(source,target)) { "Unsupported CT2 direction" }
                            withContext(Dispatchers.IO) { translator=com.sal7one.common_jni.translation.Ct2TranslationSession.open(com.sal7one.transiber.translation.Ct2Package.directory(context,spec),spec,jisrDialect) }
                            cancelNative={translator?.cancel()};executedAcceleration="cpu";actualAcceleration="cpu";runtime=checkNotNull(translator?.executionInfo)+"; revision ${spec.files.first().revision}; weights SHA-256 ${spec.files.first { it.name=="model.bin" }.sha256}"
                        }
                        MarianPackage.find(candidate.id) != null -> {
                            val pair = checkNotNull(MarianPackage.find(candidate.id))
                            withContext(Dispatchers.IO) { translator = MarianTranslationSession.open(
                                MarianPackage.modelDirectory(context, pair),
                                com.sal7one.common_jni.speech.TranslationDirection(pair.source, pair.target),
                                TranslationOptions.label(pair.id)) }
                            cancelNative = { translator?.cancel() }
                            actualAcceleration = "cpu"
                            runtime = "CPU · Pinned Marian ONNX ${pair.revision}; tree SHA-256 ${pair.treeSha256}"
                        }
                        MarianCascade.find(candidate.id) != null -> {
                            val route = checkNotNull(MarianCascade.find(candidate.id))
                            withContext(Dispatchers.IO) { translator = MarianCascade.open(context, route) }
                            cancelNative = { translator?.cancel() }
                            actualAcceleration = "cpu"
                            runtime = "CPU · Two pinned Marian ONNX models via English; ${route.first.source}→en tree SHA-256 ${route.first.treeSha256}; en→${route.target} tree SHA-256 ${route.second.treeSha256}"
                        }
                        candidate.id == TranslationOptions.ML_KIT -> {
                            require(target in candidate.targetCodes) { "Download the $target ML Kit language pack first" }
                            translator = PlatformTranslation.open(); cancelNative = { translator?.cancel() }
                            runtime = "ML Kit SDK; first call includes deferred client/model initialization"
                        }
                        else -> {
                            // Candidate path and pin were snapshotted by the parent. Native
                            // initialization still verifies the complete digest. Rediscovery
                            // here would initialize preference/audit writers in the worker.
                            val whisper=candidate.route.startsWith("Whisper /")
                            require(whisper || candidate.route.startsWith("Vosk /")) {"Unknown local speech route: ${candidate.route}"}
                            val modelFile=checkNotNull(candidate.file) {"Model file is missing"}
                            val modelPin=checkNotNull(candidate.artifactFingerprint) {"Model digest is missing"}
                            val engine: SttEngine = if (whisper) WhisperEngine() else VoskEngine()
                            legacy = engine
                            cancelNative = { (engine as? CancellableSttEngine)?.cancel() }
                            val acceleration = if (engine is WhisperEngine) candidate.acceleration
                                ?: SpeechAccelerationStore.current(context, CaptionEngineChoice.WHISPER) else "cpu"
                            executedAcceleration = acceleration
                            runtime = "Requested $acceleration; initialization did not complete"
                            withContext(Dispatchers.IO) {
                                val path = modelFile.absolutePath
                                engine.initialize(path, SttConfig.forBatch().copy(
                                    numThreads = if (engine is WhisperEngine) 8 else 2,
                                    language = if (recognitionLanguage in setOf("auto", "model")) LanguageConfig.Auto else LanguageConfig.Specific(recognitionLanguage),
                                    modelSha256 = modelPin,
                                    whisperAcceleration = if (engine is WhisperEngine &&
                                        acceleration == "vulkan")
                                        WhisperAcceleration.VULKAN else WhisperAcceleration.CPU,
                                )).getOrThrow()
                            }
                            val executionInfo = (engine as? WhisperEngine)?.runtimeDescription ?: "CPU · Vosk"
                            actualAcceleration = BenchmarkExecutionMetadata.actualAcceleration(executionInfo)
                            runtime = "Model SHA-256 $modelPin; ${if (engine is WhisperEngine) 8 else 2} threads; $executionInfo" +
                                if (engine is WhisperEngine && acceleration == "vulkan" && actualAcceleration == null)
                                    "; GPU device probe succeeded; actual model placement is not reported by this native API" else ""
                        }
                    }
                    load = (System.nanoTime() - loading) / 1e6
                repeat(measuredPasses) { pass ->
                    currentCoroutineContext().ensureActive()
                    step(if (pass == 0) "first pass" else "warm pass $pass of ${measuredPasses - 1}")
                    val passTexts = mutableListOf<String>()
                    val passFirstText = mutableListOf<Double>()
                    var passComputeMs = 0.0
                    var generatedAudioMs = 0.0
                    inputs.forEachIndexed { inputIndex, input ->
                        currentCoroutineContext().ensureActive()
                        // Reset is a separate operation in the live overlay. Do not charge it to
                        // every replay clip, and feed the same 800-sample (50 ms) capture frames.
                        if ((speech != null || legacy != null) && (pass > 0 || inputIndex > 0)) {
                            onPhase(BenchmarkWorkerPhase.RESET)
                            speech?.reset()
                            legacy?.reset()?.getOrThrow()
                        }
                        step("pass ${pass + 1}/$measuredPasses · sample ${inputIndex + 1}/${inputs.size}")
                        var firstMs: Double? = null
                        var sampleAudioMs = input.clip?.durationMs ?: 0L
                        // One deadline covers the complete sample, including every streamed
                        // capture frame and finish(). Ordinary progress never extends it.
                        onPhase(BenchmarkWorkerPhase.INFERENCE)
                        val measured = sampleBudget.measure { start ->
                            when {
                                voiceReader != null -> {
                                    val measured = BenchmarkVoiceProbe.measure(checkNotNull(voiceReader), input.text, source, candidate.voiceId, start)
                                    firstMs = measured.firstAudioMs
                                    sampleAudioMs = measured.audioMs.toLong()
                                    generatedAudioMs += measured.audioMs
                                    input.text
                                }
                                speech != null -> {
                                    val session = checkNotNull(speech)
                                    val finals = linkedMapOf<Long, String>()
                                    fun collect(update: SpeechUpdate) {
                                        update.transcripts.forEach { t ->
                                            if (firstMs == null && t.text.isNotBlank()) firstMs = (System.nanoTime() - start) / 1e6
                                            if (t.isFinal) finals[t.utteranceId] = t.text
                                        }
                                    }
                                    val samples = checkNotNull(input.clip).samples
                                    var offset = 0
                                    // Match CaptionCaptureService's 50 ms capture cadence.
                                    while (offset < samples.size) {
                                        currentCoroutineContext().ensureActive()
                                        val end = minOf(offset + 800, samples.size)
                                        collect(session.accept(samples.copyOfRange(offset, end), offset.toLong())); offset = end
                                    }
                                    collect(session.finish())
                                    finals.values.filter { it.isNotBlank() }.joinToString(" ")
                                }
                                translator != null -> checkNotNull(translator).translate(input.text, TranslationDirection(source, target))
                                else -> {
                                    val engine = checkNotNull(legacy)
                                    engine.transcribeBatch(checkNotNull(input.clip).samples, 16000).getOrThrow().fullText
                                }
                            }
                        }
                        val output = measured.value
                        currentCoroutineContext().ensureActive()
                        val sampleMs = measured.elapsedMs
                        val sampleFirstMs = if (input.silence) 0.0 else firstMs ?: sampleMs
                        passComputeMs += sampleMs
                        if (!input.silence) passFirstText += sampleFirstMs
                        passTexts += output.take(8000)
                        if (pass == measuredPasses - 1) samples += BenchmarkSampleResult(input.id, output.take(2000), input.reference,
                            sampleMs, sampleFirstMs, sampleAudioMs, input.silence,
                            input.text.takeIf { translating })
                    }
                    if (synthesizing) totalAudioMs = generatedAudioMs.toLong()
                    audioDurations += totalAudioMs
                    elapsed += passComputeMs
                    first += if (passFirstText.isEmpty()) 0.0 else passFirstText.average()
                    texts += passTexts.filterIndexed { index, _ -> !inputs[index].silence }.joinToString(" ").take(8000)
                }
                } catch (e: CancellationException) { throw e }
                catch (e: BenchmarkSampleTimeoutException) { failure = e.message; terminationReason = "deadline" }
                catch (e: Exception) { failure = e.message ?: e.toString() }
                catch (e: LinkageError) { failure = e.message ?: e.toString() }
                finally {
                    onPhase(BenchmarkWorkerPhase.CLEANUP)
                    if (load == 0.0) load = (System.nanoTime() - loading) / 1e6
                    // Sample while the loaded model still exists, outside every inference timer.
                    environmentAfter = BenchmarkEnvironmentSnapshot.capture(context)
                    withContext(NonCancellable + Dispatchers.IO) {
                        cancelNative = null
                        val cleanup = listOf<suspend () -> Unit>(
                            { speech?.close() }, { legacy?.release() }, { translator?.close() }, { voiceReader?.closeAndJoin() })
                        cleanup.forEach { close ->
                            try { close() }
                            catch (e: Exception) {
                                failure = listOfNotNull(failure, "Model cleanup failed: ${e.message ?: e}").joinToString("; ")
                                terminationReason = "cleanup_error"
                            }
                            catch (e: LinkageError) {
                                failure = listOfNotNull(failure, "Model cleanup failed: ${e.message ?: e}").joinToString("; ")
                                terminationReason = "cleanup_error"
                            }
                        }
                    }
                }
                onPhase(BenchmarkWorkerPhase.RESULT)
                if (!translating && !synthesizing) runtime += "; benchmark audio: ${BenchmarkAudio.NORMALIZATION}"
                val actualCandidate = candidate.copy(acceleration = executedAcceleration)
                val backendLabel = executedAcceleration?.uppercase(java.util.Locale.ROOT)
                val resultLabel = candidate.label + if (backendLabel != null && !candidate.label.endsWith("· $backendLabel")) " · $backendLabel" else ""
                val result = BenchmarkResult(runId, System.currentTimeMillis(), resultLabel, BenchmarkAccelerationPlan.identity(actualCandidate), candidate.route,
                    hash, source, resultTarget, totalAudioMs,
                    load, elapsed, texts, runtime, first,
                    device, failure,
                    combinedReference, combinedReference?.takeIf { failure == null && !translating && !synthesizing && source != "zh" }?.let { BenchmarkScoring.wordErrors(it, texts.lastOrNull().orEmpty()).rate },
                    combinedReference?.takeIf { failure == null && !translating && !synthesizing }?.let { BenchmarkScoring.characterErrors(it, texts.lastOrNull().orEmpty()).rate },
                    combinedReference?.takeIf { !synthesizing }?.let {
                        if (translating) BenchmarkScoring.CHRF_PARAMETERS else BenchmarkScoring.NORMALIZATION
                    },
                    if (translating && failure == null) samples.mapNotNull { sample ->
                        sample.reference?.let { expected -> BenchmarkScoring.chrf(expected, sample.text) }
                    }.takeIf { it.isNotEmpty() }?.average()?.div(100.0) else null,
                    samples, protocolVersion = BenchmarkAttemptPolicy.PROTOCOL_VERSION, task = task.name.lowercase(java.util.Locale.ROOT),
                    requestedAcceleration = executedAcceleration, actualAcceleration = actualAcceleration,
                    modelBytes = candidate.artifactBytes ?: candidate.file?.takeIf(File::exists)?.let { file ->
                        if (file.isFile) file.length() else file.walkTopDown().filter(File::isFile).sumOf(File::length)
                    }, configurationHash = configurationHash, audioMsByPass = audioDurations,
                    environment = BenchmarkEnvironment(environmentBefore, environmentAfter),
                    languageControl = recognition?.control, recognitionLanguage = recognition?.language,
                    terminationReason = terminationReason)
                if(persistResults)withContext(Dispatchers.IO) { BenchmarkResults(context).append(result) }
                withContext(Dispatchers.Main.immediate) { onResult(result) }
                completed++
            }
            reportProgress(if(persistResults)"$completed/${planned.size} completed · results saved"
                else "$completed/${planned.size} attempt complete · releasing model worker")
        } finally { cancelNative = null; lease.close() }
    }
}

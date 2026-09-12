package com.sal7one.transiber.caption

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.speech.SpeechRuntime
import kotlinx.coroutines.*
import java.io.File

/** Local-only package setup; imported files are copied and verified before becoming selectable. */
@Composable
internal fun LocalSpeechSetup(
    config: CaptionOverlayConfig,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    includeTranslation: Boolean = true,
    onModelsChanged: (List<LocalSpeechModel>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { LocalSpeechModels(File(context.filesDir, "speech-models")) }
    var models by remember { mutableStateOf<List<LocalSpeechModel>>(emptyList()) }
    var details by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var runtimeAvailable by remember(config.effectiveEngine) { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val backend = config.effectiveEngine.speechBackend ?: return
    LaunchedEffect(backend) {
        try {
            val result = withContext(Dispatchers.IO) { store.list() to SpeechRuntime().availability(backend) }
            models = result.first
            onModelsChanged(models)
            runtimeAvailable = result.second.available
            error = result.second.error.takeIf { !result.second.available }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true; error = null; notice = "Copying and verifying model files… Keep this screen open."
            try {
                val imported = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        store.importZip(input) { job.ensureActive() }
                    } ?: error("Cannot open selected model ZIP")
                }
                models = withContext(Dispatchers.IO) { store.list() }
                onModelsChanged(models)
                update { it.copy(engine = imported.profile.captionEngine, modelId = imported.id,
                    mode = CaptionMode.CAPTIONS, streamLanguage = "auto") }
                notice = "Imported ${imported.profile.label}. Original-language captions selected."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                notice = null
                error = generateSequence<Throwable>(e) { it.cause }.map { it.message ?: it.toString() }.joinToString("\n")
            } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${config.effectiveEngine.label} model setup", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { details = !details }) { Text(if (details) "Hide model details" else "Model details & language options") }
        if (details) {
        if (runtimeAvailable) Text("Native runtime available", style = MaterialTheme.typography.labelMedium)
        Text("The runtime is bundled; model weights are a separate download. Import a Hearth speech ZIP with " +
            "hearth-speech.json at its root. Import copies and verifies the files, then selects original-language CC. " +
            "Raw Hugging Face checkpoints, arbitrary ONNX files and bare GGUF files are not complete packages.",
            style = MaterialTheme.typography.bodySmall)
        Text("Moonshine Tiny English: roughly 45 MB installed. Qwen: roughly 1 GB for 0.6B. Nemotron: roughly 742 MB. " +
            "Keep at least twice the package size free for the download and installation. ASR speed depends on your phone.",
            style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(enabled = !busy, onClick = { importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
            Text(if (busy) "Importing…" else "Import model ZIP")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        models.filter { it.profile.backend == backend }.forEach { model ->
            FilterChip(selected = config.modelId == model.id || (config.modelId.isBlank() && model == models.firstOrNull { it.profile.backend == backend }),
                enabled = !busy,
                onClick = { update { it.copy(modelId = model.id) } },
                label = { Text(model.profile.label + " · " + model.id.takeLast(6)) })
        }
        var expandedLanguages by remember { mutableStateOf(false) }
        val profile = if (backend == com.sal7one.common_jni.speech.SpeechBackend.MOONSHINE) com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_TINY_EN else if (backend == com.sal7one.common_jni.speech.SpeechBackend.QWEN3_ASR)
            com.sal7one.common_jni.speech.SpeechProfile.QWEN3_ASR_0_6B else com.sal7one.common_jni.speech.SpeechProfile.NEMOTRON_3_5_ASR_0_6B
        val ccLanguages = profile.capabilities.sourceLanguages
        TextButton(onClick = { expandedLanguages = !expandedLanguages }) { Text("CC language support · ${ccLanguages.size} languages") }
        if (expandedLanguages) {
            Text(ccLanguages.sortedBy(com.sal7one.common_jni.translation.TranslationLanguages::label).joinToString(", ") {
                com.sal7one.common_jni.translation.TranslationLanguages.label(it)
            })
            Text(if (backend == com.sal7one.common_jni.speech.SpeechBackend.MOONSHINE) "English only; short utterance decoding. Translation requires a separate translator." else if (backend == com.sal7one.common_jni.speech.SpeechBackend.QWEN3_ASR)
                "Publisher coverage: 30 languages plus Chinese dialects; Auto or an explicit spoken language. Recognition, not translation."
            else "Publisher coverage: 28 languages / 32 locales usable without fine-tuning. Mandarin and 12 other languages are broad-coverage tier; quality varies. Eight adaptation-only locales are excluded.")
        }
        if (details) {
        CaptionLanguageFields(config, update, enabled = !busy, showTarget = false)
        Text("Model sources: Qwen / sherpa-onnx (Apache 2.0); NVIDIA Nemotron (OpenMDW 1.1). " +
            "The provided ZIPs include model cards, source details and license notices.", style = MaterialTheme.typography.bodySmall)
        Text("During capture, compute/audio below 1× means inference is faster than the audio duration. " +
            "The audio queue is capped at 3 seconds; overload reports an error instead of accumulating delay.",
            style = MaterialTheme.typography.bodySmall)
        }
        if (includeTranslation) com.sal7one.transiber.translation.LocalTranslationSetup(config, update)
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

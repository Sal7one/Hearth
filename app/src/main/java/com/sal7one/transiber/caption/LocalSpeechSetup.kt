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
            while (isActive) {
                delay(1500)
                models = withContext(Dispatchers.IO) { store.list() }
                onModelsChanged(models)
            }
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
                        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else ""
                        }.orEmpty()
                        val stream = java.io.BufferedInputStream(input)
                        stream.mark(4)
                        val magic = ByteArray(4); stream.read(magic); stream.reset()
                        if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) store.importZip(stream) { job.ensureActive() }
                        else {
                            val artifact = com.sal7one.transiber.models.SpeechDownloads.all.firstOrNull { it.fileName == name }
                                ?: com.sal7one.transiber.models.SpeechDownloads.all.filter { it.profile.backend == backend }.singleOrNull()
                                ?: error("Choose the original downloaded model file, with its original filename.")
                            store.installPublisher(stream, artifact, -System.nanoTime()) { job.ensureActive() }
                        }
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
        Text("Installed models", style = MaterialTheme.typography.titleSmall)
        if (models.none { it.profile.backend == backend }) Text("None installed yet. Download and install below, or import an existing model.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { details = !details }) { Text(if (details) "Hide model details" else "Model details & language options") }
        if (details) {
        if (runtimeAvailable) Text("Native runtime available", style = MaterialTheme.typography.labelMedium)
        Text("Downloads install automatically. Keep enough free space for both the original download and its installed model. Speed and memory use depend on the model and your phone.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }) {
            Text(if (busy) "Importing…" else "Import existing model")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        models.filter { it.profile.backend == backend }.forEach { model ->
            FilterChip(selected = config.modelId == model.id || (config.modelId.isBlank() && model == models.firstOrNull { it.profile.backend == backend }),
                enabled = !busy,
                onClick = { update { it.copy(modelId = model.id) } },
                label = { Text(model.profile.label + " · " + model.id.takeLast(6)) })
        }
        com.sal7one.transiber.models.ModelSourcePanel(com.sal7one.transiber.models.ModelSources.speech(config.effectiveEngine))
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
        Text("Model licenses and publisher files are linked above. Keep their notices when preparing a package.", style = MaterialTheme.typography.bodySmall)
        Text("During capture, compute/audio below 1× means inference is faster than the audio duration. " +
            "The audio queue is capped at 3 seconds; overload reports an error instead of accumulating delay.",
            style = MaterialTheme.typography.bodySmall)
        }
        if (includeTranslation) com.sal7one.transiber.settings.SettingsTranslateLocalUi(config, update)
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

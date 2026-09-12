package com.sal7one.transiber.translation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.translation.*
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.downloads.*
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun LocalTranslationSetup(config: CaptionOverlayConfig, update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val downloads = remember { FileDownloads(context.applicationContext) }
    var records by remember { mutableStateOf<List<FileDownload>>(emptyList()) }
    val store = remember { LocalTranslationModels(File(context.filesDir, "translation-models")) }
    var installed by remember { mutableStateOf<List<TranslationModelSpec>>(emptyList()) }
    var selected by remember { mutableStateOf(config.localTranslationModelId.takeIf { id -> TranslationCatalog.models.any { it.id == id } } ?: "hy-mt15-q4") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showCoverage by remember { mutableStateOf(false) }
    val spec = TranslationCatalog.find(selected)
    LaunchedEffect(Unit) { installed = withContext(Dispatchers.IO) { store.installed() } }
    LaunchedEffect(Unit) {
        if (ByokPolicy.FEATURE_BYOK) while (isActive) {
            try { records = withContext(Dispatchers.IO) { downloads.list() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: e.toString() }
            delay(1500)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val importing = spec
            busy = true; message = "Copying and verifying ${importing.label}…"
            try {
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    context.contentResolver.openInputStream(uri)?.use { store.import(it, importing) { job.ensureActive() } }
                        ?: error("Cannot open selected translation GGUF")
                }
                installed = withContext(Dispatchers.IO) { store.installed() }
                update { it.copy(localTranslationModelId = importing.id) }
                message = "${importing.label} imported and selected. Enable the bridge when ready."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = generateSequence<Throwable>(e) { it.cause }.joinToString("\n") { it.message ?: it.toString() } }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Local translation bridge", style = MaterialTheme.typography.titleMedium)
        Text("Speech model → original CC → translation model. Both stages stay on your phone. Captions keep updating while translation catches up.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = config.localTranslationEnabled, onCheckedChange = { enabled ->
                update { it.copy(localTranslationEnabled = enabled, mode = if (enabled) CaptionMode.TRANSLATE else CaptionMode.CAPTIONS) }
            })
            Text(if (config.localTranslationEnabled) "Enabled" else "Disabled · CC only")
        }
        Text("Choose a translation model", style = MaterialTheme.typography.labelLarge)
        TranslationCatalog.models.forEach { model ->
            val isInstalled = installed.any { it.id == model.id }
            FilterChip(selected = selected == model.id, enabled = !busy,
                onClick = {
                    selected = model.id
                    if (isInstalled) update { it.copy(localTranslationModelId = model.id) }
                }, label = { Text("${model.label} · ${model.bytes / 1_048_576} MiB${if (isInstalled) " · Installed" else ""}") })
        }
        Text("Active bridge model: ${installed.firstOrNull { it.id == config.localTranslationModelId }?.label ?: "None — import and select one"}")
        Text("Q4 uses the least memory here. Q6/Q8 are larger alternatives; speed and translation quality vary. Download size is not total RAM use. Only the selected model loads.")
        OutlinedButton(enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import ${spec.label} GGUF") }
        if (ByokPolicy.FEATURE_BYOK) {
            val completed = records.firstOrNull { it.complete && it.title == spec.fileName }
            val pending = records.firstOrNull { !it.complete && !it.failed && it.title == spec.fileName }
            if (completed != null) Button(enabled = !busy, onClick = { scope.launch {
                val importing = spec
                busy = true; message = "Verifying and installing ${importing.label}…"
                try {
                    downloads.installTranslation(completed.id, importing)
                    installed = withContext(Dispatchers.IO) { store.installed() }
                    update { it.copy(localTranslationModelId = importing.id) }
                    message = "${importing.label} installed and selected. Enable the bridge when ready."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: e.toString() }
                finally { busy = false }
            } }) { Text("Install downloaded model") }
            OutlinedButton(enabled = !busy && pending == null, onClick = { scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) { downloads.enqueue(DownloadSpec.parse(spec.url, spec.fileName), modelPackage = true) }
                    records = withContext(Dispatchers.IO) { downloads.list() }
                    message = "Downloading to ${downloads.locationLabel}/models. When complete, tap Install downloaded model here or in Downloads."
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: e.toString() }
                finally { busy = false }
            } }) { Text(if (pending != null) "Downloading · ${pending.bytes / 1_048_576} MiB" else "Download ${spec.bytes / 1_048_576} MiB") }
            Text("Saved in ${downloads.locationLabel}/models. No export needed for installation.")
            TextButton(onClick = { uriHandler.openUri(spec.modelCard) }) { Text("Publisher model card and license") }
        }
        Text("Tencent Hunyuan community license; weights are downloaded separately. Imports must match the selected publisher GGUF exactly.")
        TextButton(onClick = { showCoverage = !showCoverage }) { Text(if (showCoverage) "Hide language coverage" else "Show supported source → target languages") }
        if (showCoverage) Text("Any of these source languages → any other listed target:\n" + spec.sourceLanguages.sortedBy(TranslationLanguages::label).joinToString(", ") { TranslationLanguages.label(it) })
        Text("Translate into")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TranslationTarget.entries.forEach { target -> FilterChip(selected = config.target == target,
                onClick = { update { it.copy(target = target) } }, label = { Text(target.label) }) }
        }
        val active = installed.firstOrNull { it.id == config.localTranslationModelId }
        Text(when {
            !config.localTranslationEnabled -> "Original-language CC; translation model stays unloaded."
            active == null -> "Import a translation model to use the bridge. CC can run now."
            config.streamLanguage == "auto" -> "Automatic routing uses the language reported by ASR. Unknown or mixed language stays CC-only with a notice."
            active.supports(config.streamLanguage, config.target.languageTag) -> "Supported: ${TranslationLanguages.label(config.streamLanguage)} → ${config.target.label}"
            else -> "Unsupported pair: ${config.streamLanguage} → ${config.target.languageTag}. CC continues."
        })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it) }
    }
}

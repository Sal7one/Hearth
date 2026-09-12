package com.sal7one.transiber.translation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
internal fun LocalTranslationSetup(
    config: CaptionOverlayConfig,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    includeLegacy: Boolean = false,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val downloads = remember { FileDownloads(context.applicationContext) }
    var records by remember { mutableStateOf<List<FileDownload>>(emptyList()) }
    val store = remember { LocalTranslationModels(File(context.filesDir, "translation-models")) }
    var installed by remember { mutableStateOf<List<TranslationModelSpec>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf(config.localTranslationModelId.takeIf { id -> TranslationCatalog.models.any { it.id == id } } ?: "hy-mt15-q4") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showGguf by rememberSaveable { mutableStateOf(config.localTranslationModelId != TranslationOptions.ML_KIT) }
    var showLegacy by rememberSaveable { mutableStateOf(false) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = config.localTranslationEnabled, onCheckedChange = { enabled ->
                update { it.copy(localTranslationEnabled = enabled, mode = if (enabled) CaptionMode.TRANSLATE else CaptionMode.CAPTIONS) }
            })
            Text(if (config.localTranslationEnabled) "Translation enabled" else "Disabled · CC only")
        }
        Text("Active translator: ${TranslationOptions.label(config.localTranslationModelId)}", style = MaterialTheme.typography.labelLarge)
        Text("Browse translators", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (PlatformTranslation.available) FilterChip(
                selected = !showLegacy && !showGguf, enabled = !busy,
                onClick = { showLegacy = false; showGguf = false }, label = { Text("ML Kit") })
            listOf("hy-mt1.5" to "HY-MT1.5", "hy-mt2" to "Hy-MT2", "translategemma" to "TranslateGemma").forEach { (family, label) ->
                FilterChip(selected = !showLegacy && showGguf && spec.family == family, enabled = !busy,
                    onClick = {
                        if (spec.family != family) {
                            selected = TranslationCatalog.models.firstOrNull { it.family == family && it.id == config.localTranslationModelId }?.id
                                ?: TranslationCatalog.models.first { it.family == family && it.quantization == "Q4_K_M" }.id
                        }
                        showLegacy = false; showGguf = true
                    }, label = { Text(label) })
            }
            if (includeLegacy) FilterChip(selected = showLegacy, enabled = !busy,
                onClick = { showLegacy = true }, label = { Text("Marian · legacy") })
        }
        if (showLegacy && includeLegacy) {
            Text("Legacy English → Arabic model files", style = MaterialTheme.typography.titleSmall)
            com.sal7one.transiber.models.LegacyModelSetup(com.sal7one.transiber.models.ModelEngineType.TRANSLATE, config, update)
            return@Column
        }
        if (!showGguf && PlatformTranslation.available) MlKitSetup(config, update = update)
        if (showGguf) {
        Text("Size / precision", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TranslationCatalog.models.filter { it.family == spec.family }.forEach { model ->
                FilterChip(selected = selected == model.id, enabled = !busy,
                    onClick = { selected = model.id }, label = { Text("${model.quantization} · ${model.bytes / 1_048_576} MiB") })
            }
        }
        Text("${spec.label} · ${if (installed.any { it.id == spec.id }) "Installed" else "Not installed"}")
        if (installed.any { it.id == spec.id }) Button(enabled = !busy, onClick = { update { it.copy(localTranslationModelId = spec.id, localTranslationEnabled = true, mode = CaptionMode.TRANSLATE) } }) {
            Text(if (config.localTranslationModelId == spec.id) "Selected translator" else "Use ${spec.label}")
        }
        Text("Q4 uses less storage and memory; Q6/Q8 are larger. Only the active translator loads.", style = MaterialTheme.typography.bodySmall)
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
            TextButton(onClick = { uriHandler.openUri(spec.modelCard) }) { Text("Source, files & license") }
            if (spec.family == "translategemma") TextButton(onClick = { uriHandler.openUri("https://huggingface.co/google/translategemma-4b-it") }) { Text("Original Google model card") }
        }
        Text("${spec.license}. Imported files must match the selected artifact.")
        if (spec.family == "translategemma") Text("Larger quality alternative · 2.49 GB download. Community GGUF conversion of Google TranslateGemma. Phone speed depends on your device.")
        TextButton(onClick = { showCoverage = !showCoverage }) { Text(if (showCoverage) "Hide language coverage" else "Show supported source → target languages") }
        if (showCoverage) Text("Any of these source languages → any other listed target:\n" + spec.sourceLanguages.sortedBy(TranslationLanguages::label).joinToString(", ") { TranslationLanguages.label(it) })
        }
        com.sal7one.transiber.caption.CaptionLanguageFields(config, update, showSource = false,
            targetChoices = com.sal7one.transiber.caption.CaptionLanguageChoices(TranslationOptions.languages(config.localTranslationModelId).ifEmpty { spec.targetLanguages }, "Output languages supported by the active translator."))
        val active = installed.firstOrNull { it.id == config.localTranslationModelId }
        Text(when {
            !config.localTranslationEnabled -> "Original-language CC; translation model stays unloaded."
            config.localTranslationModelId == TranslationOptions.ML_KIT -> "ML Kit selected · download spoken and target packs above. Translation works offline after download."
            active == null -> "Import a translation model to use the bridge. CC can run now."
            config.streamLanguage == "auto" -> "Automatic routing uses the language reported by ASR. Unknown or mixed language stays CC-only with a notice."
            active.supports(config.streamLanguage, config.target.languageTag) -> "Supported: ${TranslationLanguages.label(config.streamLanguage)} → ${config.target.label}"
            else -> "Unsupported pair: ${config.streamLanguage} → ${config.target.languageTag}. CC continues."
        })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it) }
    }
}

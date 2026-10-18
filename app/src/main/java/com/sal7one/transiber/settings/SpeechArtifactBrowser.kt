package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.downloads.*
import com.sal7one.transiber.i18n.languageName
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.models.*
import com.sal7one.transiber.ui.components.*
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale

/** One card per checkpoint. Precision and provenance do not compete with the model choice. */
@Composable
internal fun SpeechArtifactBrowser(config: CaptionOverlayConfig) {
    val uiText = rememberUiText()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val downloads = remember { FileDownloads(context.applicationContext) }
    val speechStore = remember { LocalSpeechModels(File(context.filesDir, "speech-models")) }
    val registry = remember { ModelRegistry.getInstance(context) }
    var imported by remember { mutableStateOf(emptyList<ModelRegistry.RegisteredModel>()) }
    var localModels by remember { mutableStateOf(emptyList<LocalSpeechModel>()) }
    var records by remember { mutableStateOf(emptyList<FileDownload>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var family by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var installedOnly by rememberSaveable { mutableStateOf(false) }
    val catalog = SpeechArtifactCatalog.all
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                val refreshed = withContext(Dispatchers.IO) { Triple(downloads.list(), speechStore.list(),
                    registry.registeredModels.value.filter { it.isValid && File(it.path).exists() }) }
                records = refreshed.first; localModels = refreshed.second; imported = refreshed.third
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            delay(1500)
        }
    }
    val registryMatches = catalog.mapNotNull { item ->
        val digest = if (item.kind == SpeechArtifactKind.VOSK) VoskDownloads.find(item.id)?.treeSha256 else item.sha256
        imported.firstOrNull { it.isValid && it.digest?.hex.equals(digest, ignoreCase = true) }?.let { item.id to it }
    }.toMap()
    val localMatches = catalog.mapNotNull { item -> item.publisher?.let { source ->
        val candidates = localModels.filter { it.profile == source.profile }
        (candidates.firstOrNull { it.id == config.modelId } ?: candidates.firstOrNull())?.let { item.id to it }
    } }.toMap()
    val installedIds = registryMatches.keys + localMatches.keys
    val currentArtifact = catalog.firstOrNull { item -> item.engine == config.engine &&
        (registryMatches[item.id]?.id == config.modelId || localMatches[item.id]?.id == config.modelId) }
    val groups = speechCheckpointGroups(catalog, family, language, query, installedOnly, installedIds)
    val families = listOf("" to uiText(UiR.string.model_browser_all_engines)) + catalog.map { it.family }.distinct()
        .map { it to speechFamilyTitle(it) }.sortedBy { it.second }
    val languages = listOf("" to uiText(UiR.string.model_browser_all_languages)) + catalog.flatMap { it.languages }.distinct()
        .map { it to uiText.languageName(it) }.sortedBy { it.second }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiText(UiR.string.speech_artifacts_heading), style = MaterialTheme.typography.titleMedium)
        Text(uiText(UiR.string.model_browser_speech_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        currentArtifact?.let { ModelStatusLabel(uiText(UiR.string.model_browser_current, speechCheckpointTitle(it)), selected = true) }
        OutlinedTextField(query, { query = it }, label = { Text(uiText(UiR.string.model_browser_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelFilterPicker(uiText(UiR.string.model_browser_language), language, languages, { language = it }, Modifier.weight(1f))
            ModelFilterPicker(uiText(UiR.string.model_browser_engine), family, families, { family = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !installedOnly, onClick = { installedOnly = false }, label = { Text(uiText(UiR.string.model_browser_all)) })
            FilterChip(selected = installedOnly, onClick = { installedOnly = true }, label = { Text(uiText(UiR.string.model_browser_installed)) })
        }
        Text(uiText(UiR.string.model_browser_results, groups.size), style = MaterialTheme.typography.labelMedium)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (groups.isEmpty()) Text(uiText(UiR.string.model_browser_no_results))
        groups.forEach { variants -> key(variants.first().family, variants.first().checkpoint) {
            var selectedVariant by rememberSaveable { mutableStateOf("") }
            var details by rememberSaveable { mutableStateOf(false) }
            val item = variants.firstOrNull { it.id == selectedVariant }
                ?: preferredSpeechVariant(variants, installedIds, currentArtifact?.id.orEmpty())
            val record = records.firstOrNull { it.modelId == item.id }
            val registered = registryMatches[item.id]
            val local = localMatches[item.id]
            val installed = item.id in installedIds
            val selected = installed && item.id == currentArtifact?.id
            val stage = if (record?.installed == true && !installed) DownloadStage.READY else record?.displayStage()
            val active = record?.active == true
            val budget = DownloadBudget.estimate(item.bytes, item.installedBytes, record?.bytes?.coerceIn(0, item.bytes) ?: 0)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(speechCheckpointTitle(item), style = MaterialTheme.typography.titleMedium)
                    val languageLabel = if (item.languages.size == 1) uiText.languageName(item.languages.single()) else uiText(UiR.string.model_browser_language_count, item.languages.size)
                    Text("$languageLabel · ${item.bytes.toMiBString(uiText.locale)} MiB", style = MaterialTheme.typography.bodyMedium)
                    Text(uiText(when (speechDecodeMode(item)) {
                        SpeechDecodeMode.STREAMING_PARTIALS -> UiR.string.model_speech_streaming_partials
                        SpeechDecodeMode.WINDOWED_PARTIALS -> UiR.string.model_speech_windowed_partials
                        SpeechDecodeMode.UTTERANCE_FINALS -> UiR.string.model_speech_utterance_finals
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!installed) Text(uiText(if (item.installedBytes == null) UiR.string.model_browser_disk_estimate else UiR.string.model_browser_disk,
                        budget.sameVolumeRequired.toMiBString(uiText.locale)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selected || installed || stage != null) ModelStatusLabel(when {
                        selected -> uiText(UiR.string.model_browser_selected)
                        installed -> uiText(UiR.string.model_download_installed)
                        else -> uiText.downloadStage(checkNotNull(stage))
                    }, selected)
                    if (active) {
                        val progress = record?.transferFraction()
                        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (stage in setOf(DownloadStage.VERIFYING, DownloadStage.INSTALLING)) Text(uiText(UiR.string.model_download_processing_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    record?.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (record?.installed == true && !installed) Text(uiText(UiR.string.model_download_missing_install), color = MaterialTheme.colorScheme.error)
                    if (installed || ByokPolicy.FEATURE_BYOK) Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && !active && !selected, onClick = {
                        scope.launch {
                            busy = true; error = null; message = null
                            try {
                                when {
                                    registered != null -> {
                                        withContext(Dispatchers.IO) { checkNotNull(registry.getProvider(registered.id)).getModelPath() }
                                        CaptionConfigStore.update(context) { it.copy(engine = item.engine, modelId = registered.id,
                                            streamLanguage = if (item.kind == SpeechArtifactKind.VOSK) item.languages.single() else "auto") }
                                    }
                                    local != null -> {
                                        withContext(Dispatchers.IO) { com.sal7one.common_jni.speech.SpeechModelPackage.verify(local.root) }
                                        CaptionConfigStore.update(context) { it.copy(engine = item.engine, modelId = local.id, streamLanguage = "auto") }
                                    }
                                    record != null && record.id < 0 && (record.paused || record.failed) -> withContext(Dispatchers.IO) { downloads.retry(record.id) }
                                    record?.complete == true -> {
                                        if (record.id < 0) withContext(Dispatchers.IO) { downloads.reinstall(record.id) }
                                        else {
                                            val inspection = downloads.inspect(record.id)
                                            check(inspection.original == DownloadHealth.VERIFIED) { uiText(UiR.string.model_download_invalid_original) }
                                            downloads.installModel(record.id, item.id)
                                        }
                                    }
                                    else -> withContext(Dispatchers.IO) { downloads.enqueue(DownloadSpec.parse(item.url, item.fileName), modelPackage = true, installModelId = item.id) }
                                }
                                records = withContext(Dispatchers.IO) { downloads.list() }
                                if (installed) message = uiText(UiR.string.speech_artifact_selected)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: e.toString() }
                            finally { busy = false }
                        }
                    }) { Text(when {
                        selected -> uiText(UiR.string.model_browser_selected)
                        active -> uiText.downloadStage(checkNotNull(stage))
                        installed -> uiText(UiR.string.speech_artifact_select)
                        stage == DownloadStage.PAUSED -> uiText(UiR.string.ui_resume_download)
                        stage == DownloadStage.FAILED -> uiText(UiR.string.ui_retry_download_install_e5444)
                        stage == DownloadStage.READY -> uiText(UiR.string.ui_install_downloaded_model_d458c)
                        else -> uiText(UiR.string.model_browser_download_install)
                    }) }
                    else Text(uiText(UiR.string.speech_artifact_import_play_only), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(uiText(if (details) UiR.string.model_browser_hide_details else UiR.string.model_browser_details)) }
                    if (details) {
                        if (variants.size > 1) ModelFilterPicker(uiText(UiR.string.model_browser_precision), item.id,
                            variants.map { it.id to "${it.quant} · ${it.bytes.toMiBString(uiText.locale)} MiB" }, { selectedVariant = it })
                        else Text(uiText(UiR.string.model_browser_format, item.quant), style = MaterialTheme.typography.bodySmall)
                        if (!installed) {
                            Text(uiText(UiR.string.model_browser_disk_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(uiText(UiR.string.speech_artifact_languages, item.languages.sorted().joinToString(", ") { uiText.languageName(it) }), style = MaterialTheme.typography.bodySmall)
                        item.modelReleaseDate?.let { Text(uiText(UiR.string.model_browser_release, it), style = MaterialTheme.typography.bodySmall) }
                        item.artifactDate?.let { Text(uiText(UiR.string.model_browser_conversion, it), style = MaterialTheme.typography.bodySmall) }
                        Text(uiText(UiR.string.speech_artifact_license, item.license), style = MaterialTheme.typography.bodySmall)
                        Text(uiText(UiR.string.model_browser_revision, item.sourceRevision), style = MaterialTheme.typography.bodySmall)
                        Text(uiText(UiR.string.model_browser_integrity, item.sha256), style = MaterialTheme.typography.bodySmall)
                        if (ByokPolicy.FEATURE_BYOK) TextButton(onClick = {
                            try { uriHandler.openUri(item.publisherUrl) } catch (e: Exception) { error = e.message ?: e.toString() }
                        }) { Text(uiText(UiR.string.ui_source_license_397e9)) }
                    }
                }
            }
        } }
    }
}

private fun Long.toMiBString(locale: Locale): String = String.format(locale, "%,.1f", this / 1_048_576.0)

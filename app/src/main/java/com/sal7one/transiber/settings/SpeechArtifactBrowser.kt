package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
internal fun SpeechArtifactBrowser(config: CaptionOverlayConfig, onManage: (CaptionEngineChoice?) -> Unit = {}) {
    val uiText = rememberUiText()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads = remember { FileDownloads(context.applicationContext) }
    val speechStore = remember { LocalSpeechModels(File(context.filesDir, "speech-models")) }
    val registry = remember { ModelRegistry.getInstance(context) }
    val installationScanner = remember { SpeechCatalogInstallations(File(context.filesDir, "speech-models")) }
    var installations by remember { mutableStateOf(SpeechCatalogInstallationSnapshot(emptyMap(), emptyMap(), emptyList())) }
    var verificationIssues by remember { mutableStateOf(emptyList<String>()) }
    var installationsLoaded by remember { mutableStateOf(false) }
    val selectedModelId by rememberUpdatedState(config.modelId)
    var records by remember { mutableStateOf(emptyList<FileDownload>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyArtifactId by remember { mutableStateOf<String?>(null) }
    var family by rememberModelBrowseString("speech-family", "")
    var language by rememberModelBrowseString("speech-language", "")
    var query by rememberModelBrowseString("speech-query", "")
    var installedOnly by rememberModelBrowseBoolean("speech-installed")
    var hardwareId by rememberModelBrowseString("speech-hardware", "all")
    val hardware = ModelHardwareFilter.fromKey(hardwareId)
    val catalog = SpeechArtifactCatalog.all
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                val refreshed = withContext(Dispatchers.IO) {
                    val inspected = speechStore.inspect()
                    Triple(downloads.list(), installationScanner.scan(catalog, registry.registeredModels.value,
                        inspected.models, selectedModelId), inspected.issues)
                }
                records = refreshed.first; installations = refreshed.second
                verificationIssues = (refreshed.second.issues + refreshed.third).distinct()
                installationsLoaded = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            delay(1500)
        }
    }
    val registryMatches = installations.registered
    val localMatches = installations.publisher
    val installedIds = installations.installedIds
    val currentArtifact = catalog.firstOrNull { item -> item.engine == config.engine &&
        (registryMatches[item.id]?.id == config.modelId || localMatches[item.id]?.id == config.modelId) }
    val groups = speechCheckpointGroups(catalog, family, language, query, installedOnly, installedIds, hardware)
    val families = listOf("" to uiText(UiR.string.model_browser_all_engines)) + catalog.map { it.family }.distinct()
        .map { it to speechFamilyTitle(it) }.sortedBy { it.second }
    val languages = listOf("" to uiText(UiR.string.model_browser_all_languages)) + catalog.flatMap { it.languages }.distinct()
        .map { it to uiText.languageName(it) }.sortedBy { it.second }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsLayout.cardGap)) {
        currentArtifact?.let { ModelStatusLabel(uiText(UiR.string.model_browser_current, speechCheckpointTitle(it)), selected = true) }
        ModelSearchField(query, { query = it })
        SettingsFieldPair(
            first = { ModelFilterPicker(uiText(UiR.string.model_browser_language), language, languages, { language = it }, it) },
            second = { ModelFilterPicker(uiText(UiR.string.model_browser_engine), family, families, { family = it }, it) },
        )
        ModelHardwarePicker(hardware, { hardwareId = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !installedOnly, onClick = { installedOnly = false }, label = { Text(uiText(UiR.string.model_browser_all)) })
            FilterChip(selected = installedOnly, onClick = { installedOnly = true }, label = { Text(uiText(UiR.string.model_browser_installed)) })
        }
        FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(uiText(UiR.string.model_browser_results, groups.size), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onManage(config.engine) }) { Text(uiText(UiR.string.library_import)) }
            if (query.isNotBlank() || family.isNotBlank() || language.isNotBlank() || installedOnly || hardware != ModelHardwareFilter.ALL) {
                TextButton(onClick = { query = ""; family = ""; language = ""; installedOnly = false; hardwareId = "all" }) {
                    Text(uiText(UiR.string.model_explorer_reset_filters))
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!installationsLoaded) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (verificationIssues.isNotEmpty()) {
            var showIssues by rememberSaveable { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(SettingsLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(SettingsLayout.contentGap)) {
                    TextButton(onClick = { showIssues = !showIssues }) {
                        Text(uiText(UiR.string.model_explorer_verification_issue), color = MaterialTheme.colorScheme.error)
                    }
                    if (showIssues) verificationIssues.forEach { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (groups.isEmpty()) ModelBrowserEmptyState(onReset = { query = ""; family = ""; language = ""; installedOnly = false; hardwareId = "all" })
        groups.forEach { variants -> key(variants.first().family, variants.first().checkpoint) {
            var selectedVariant by rememberSaveable { mutableStateOf("") }
            var details by rememberSaveable { mutableStateOf(false) }
            val item = variants.firstOrNull { it.id == selectedVariant && speechArtifactMatches(it, query) }
                ?: preferredSpeechVariant(variants, installedIds, currentArtifact?.id.orEmpty(), query)
            val componentState = item.components?.let { source -> downloadQueueGroups(records).firstOrNull { it.modelId == source.id } }
            val record = componentState?.installedRecord ?: componentState?.failure ?: componentState?.representatives?.firstOrNull { it.active || it.paused } ?: componentState?.representatives?.firstOrNull() ?: records.firstOrNull { it.modelId == item.id }
            val registered = registryMatches[item.id]
            val local = localMatches[item.id]
            val compatibleAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull() in item.supportedAbis &&
                ("arm64-v8a" !in item.supportedAbis || item.supportedAbis.size > 1 || android.os.Process.is64Bit())
            val installed = item.id in installedIds
            val selected = installed && item.id == currentArtifact?.id
            val stage = if (record?.installed == true && !installed) DownloadStage.READY else componentState?.stage ?: record?.displayStage()
            val active = componentState?.active ?: (record?.active == true)
            val budget = DownloadBudget.estimate(item.bytes, item.installedBytes, componentState?.transferred ?: record?.bytes?.coerceIn(0, item.bytes) ?: 0)
            OutlinedCard(Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.16f)
                    else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(SettingsLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(SettingsLayout.contentGap)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Text(speechCheckpointTitle(item), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (selected || installed || stage != null) ModelStatusLabel(when {
                            selected -> uiText(UiR.string.model_browser_selected)
                            installed -> uiText(UiR.string.model_download_installed)
                            else -> uiText.downloadStage(checkNotNull(stage))
                        }, selected)
                    }
                    val languageLabel = if (item.languages.size == 1) uiText.languageName(item.languages.single()) else uiText(UiR.string.model_browser_language_count, item.languages.size)
                    Text("$languageLabel · ${item.bytes.toMiBString(uiText.locale)} MiB · ${item.quant}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(uiText(when (speechDecodeMode(item)) {
                        SpeechDecodeMode.STREAMING_PARTIALS -> UiR.string.model_speech_streaming_partials
                        SpeechDecodeMode.WINDOWED_PARTIALS -> UiR.string.model_speech_windowed_partials
                        SpeechDecodeMode.UTTERANCE_FINALS -> UiR.string.model_speech_utterance_finals
                    }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ModelGpuPaths(speechGpuPaths(item))
                    if (!compatibleAbi) Text(uiText(UiR.string.speech_expansion_arm64), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (!installed) Text(uiText(if (item.installedBytes == null) UiR.string.model_browser_disk_estimate else UiR.string.model_browser_disk,
                        budget.sameVolumeRequired.toMiBString(uiText.locale)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (busyArtifactId == item.id) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (active) {
                        val progress = componentState?.fraction ?: record?.transferFraction()
                        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (stage in setOf(DownloadStage.VERIFYING, DownloadStage.INSTALLING)) Text(uiText(UiR.string.model_download_processing_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    (componentState?.failure ?: record)?.error?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (installationsLoaded && record?.installed == true && !installed) Text(uiText(UiR.string.model_download_missing_install), color = MaterialTheme.colorScheme.error)
                    if (!selected && (installed || ByokPolicy.FEATURE_BYOK)) Button(modifier = Modifier.fillMaxWidth(), enabled = compatibleAbi && installationsLoaded && busyArtifactId == null && !active && !selected, onClick = {
                        scope.launch {
                            busyArtifactId = item.id; error = null; message = null
                            try {
                                when {
                                    registered != null -> {
                                        withContext(Dispatchers.IO) { checkNotNull(registry.getProvider(registered.id)).getModelPath() }
                                        CaptionConfigStore.update(context) { it.copy(engine = item.engine, modelId = registered.id,
                                            streamLanguage = if (item.kind == SpeechArtifactKind.VOSK) item.languages.single() else "auto") }
                                    }
                                    local != null -> {
                                        withContext(Dispatchers.IO) { com.sal7one.common_jni.speech.SpeechModelPackage.verify(local.root) }
                                        CaptionConfigStore.update(context) { it.copy(engine = item.engine, modelId = local.id, streamLanguage = if (local.profile.capabilities.automaticSourceLanguage) "auto" else "ar") }
                                    }
                                    item.components != null -> withContext(Dispatchers.IO) {
                                        val group = checkNotNull(item.components)
                                        if (stage == DownloadStage.READY && record != null) downloads.reinstall(record.id)
                                        else SpeechComponents.enqueue(context, downloads, group)
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
                            finally { busyArtifactId = null }
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
                    else if (!selected && !installed) OutlinedButton(onClick = { onManage(item.engine) }, enabled = compatibleAbi && busyArtifactId == null,
                        modifier = Modifier.fillMaxWidth()) { Text(uiText(UiR.string.library_import)) }
                    ModelLibraryDisclosure(uiText(UiR.string.library_details), details, { details = !details }) {
                        ModelSourceLinks(item.publisherUrl, item.filesUrl, item.licenseUrl, onError = { error = it })
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
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(uiText(UiR.string.model_browser_revision, item.sourceRevision), style = MaterialTheme.typography.bodySmall)
                                Text(uiText(if (item.components == null) UiR.string.model_browser_integrity else UiR.string.speech_component_set_digest, item.sha256), style = MaterialTheme.typography.bodySmall)
                                item.components?.parts?.forEach { part ->
                                    Text("${part.role} · ${part.fileName} · ${part.bytes.toMiBString(uiText.locale)} MiB\nSHA-256: ${part.sha256}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        } }
    }
}

private fun Long.toMiBString(locale: Locale): String = String.format(locale, "%,.1f", this / 1_048_576.0)

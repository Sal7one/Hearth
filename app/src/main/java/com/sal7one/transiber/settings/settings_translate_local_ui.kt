package com.sal7one.transiber.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.translation.*
import com.sal7one.common_jni.model.ModelIntegrity
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.byok.CloudConfigStore
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.preferences.*
import com.sal7one.transiber.setup.*
import com.sal7one.transiber.downloads.*
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.models.LegacyModelSetup
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import com.sal7one.transiber.translation.*
import com.sal7one.transiber.ui.components.*
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun SettingsTranslateLocalUi(
    config: CaptionOverlayConfig,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    includeLegacy: Boolean = false,
    showCaptionControls: Boolean = true,
    repairRevision: Int = 0, repairModelId: String? = null,
) {
    val uiText = rememberUiText()
    if (!com.sal7one.transiber.runtime.LocalModelPolicy.allowed(LocalContext.current)) {
        LocalModelPolicyNotice()
        return
    }
    if (rememberSetupMode() == SetupMode.BASIC) {
        Text(uiText(UiR.string.setup_basic_selected_translation, TranslationOptions.label(config.localTranslationModelId)),
            style = MaterialTheme.typography.labelLarge)
        LocalBundleUi(config, translationOnly = true)
        AdvancedSetupLink()
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloads = remember { FileDownloads(context.applicationContext) }
    val store = remember { LocalTranslationModels(File(context.filesDir, "translation-models")) }
    val registry = remember { ModelRegistry.getInstance(context) }
    val registryModels by registry.registeredModels.collectAsState()
    val catalog = remember { translationBrowserItems() }
    val languageModel = rememberCaptionLanguageModel(config)
    val spoken = CaptionLanguages.effectiveSource(config, CloudConfigStore.sttMode(context), languageModel)
    var source by rememberSaveable { mutableStateOf(spoken.takeIf { it in catalog.flatMap { model -> model.sourceLanguages } } ?: "any") }
    var target by rememberSaveable { mutableStateOf(config.target.languageTag) }
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("any") }
    var installedOnly by rememberSaveable { mutableStateOf(false) }
    var showPivots by rememberSaveable { mutableStateOf(MarianCascade.find(config.localTranslationModelId) != null) }
    var platformDetails by rememberSaveable { mutableStateOf(config.localTranslationModelId == TranslationOptions.ML_KIT) }
    var records by remember { mutableStateOf<List<FileDownload>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var invalidInstalls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var repairingInstalls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var importingId by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        val latestRecords = withContext(Dispatchers.IO) { if (ByokPolicy.FEATURE_BYOK) downloads.list() else emptyList() }
        val repairCandidates = repairingInstalls
        val repaired = withContext(Dispatchers.IO) { repairCandidates.filterTo(mutableSetOf()) { id ->
            val related = latestRecords.filter { it.modelId == id || MarianPackage.part(it.modelId)?.pairId == id }
            if (related.none { it.installed } || related.any { it.active }) false
            else try {
                val spec = TranslationCatalog.models.firstOrNull { it.id == id }
                if (spec != null) ModelIntegrity.inspect(store.file(spec), spec.sha256)
                else MarianPackage.modelDirectory(context, checkNotNull(MarianPackage.find(id)))
                true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        } }
        invalidInstalls = invalidInstalls - repaired
        repairingInstalls = repairingInstalls - repaired
        val snapshot = withContext(Dispatchers.IO) {
            val gguf = store.installed().map { it.id }.toSet() - invalidInstalls
            val pairs = MarianPackage.pairs.filter { pair -> pair.id !in invalidInstalls &&
                MarianPackage.installed(context, pair)?.let { it.isValid && translationFilesPresent(pair, File(it.path)) } == true
            }.map { it.id }.toSet()
            val routes = MarianCascade.routes.filter { it.first.id in pairs && it.second.id in pairs }.map { it.id }.toSet()
            (gguf + pairs + routes) to latestRecords
        }
        installed = snapshot.first
        records = snapshot.second
    }
    LaunchedEffect(registryModels) {
        do {
            try { refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            if (!ByokPolicy.FEATURE_BYOK) break
            delay(1500)
        } while (isActive)
    }
    LaunchedEffect(repairRevision) {
        val browse = UserPreferences.store(context, "translation-browser")
        (repairModelId.takeIf { repairRevision > 0 } ?: browse.getString("model", null))?.let { id ->
            TranslationCatalog.models.firstOrNull { it.id == id }?.let { query = it.label.substringBefore(" · "); kind = "text" }
            val pair = MarianPackage.find(id)
            val route = MarianCascade.find(id)
            if (pair != null || route != null) {
                source = pair?.source ?: checkNotNull(route).source
                target = pair?.target ?: checkNotNull(route).target
                kind = "compact"; showPivots = route != null
            }
            installedOnly = false
            browse.edit().remove("model").apply()
        }
    }
    fun select(id: String, fixedTarget: String? = null) {
        update { current ->
            val requestedTarget = fixedTarget ?: target.takeIf { it != "any" && it in TranslationOptions.targetLanguages(id, source) }
            val next = current.copy(localTranslationModelId = id, textTranslationProviderId = "local",
                target = requestedTarget?.let(TranslationTarget::of) ?: current.target)
            if (showCaptionControls) next.copy(localTranslationEnabled = true, mode = CaptionMode.TRANSLATE) else next
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val spec = TranslationCatalog.models.firstOrNull { it.id == importingId }
        importingId = ""
        if (uri != null && spec != null) scope.launch {
            busy = true; error = null
            message = uiText(UiR.string.ui_copying_and_verifying_1_s_6575d, spec.label)
            try {
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val imported = store.import(input, spec) { job.ensureActive() }
                        ModelSourceBindings.recordImported(context, spec.id, uri, listOf(imported))
                    }
                        ?: error(uiText(UiR.string.ui_cannot_open_selected_translation_gguf_cae88))
                }
                invalidInstalls = invalidInstalls - spec.id
                refresh(); select(spec.id)
                message = uiText(UiR.string.ui_1_s_imported_and_selected_as_the_local_model_849c7, spec.label)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = generateSequence<Throwable>(e) { it.cause }.joinToString("\n") { it.message ?: it.toString() } }
            finally { busy = false }
        }
    }
    val languages = remember(catalog) { catalog.flatMap { it.sourceLanguages + it.targetLanguages }.distinct().sortedBy(TranslationLanguages::label) }
    val options = listOf("any" to uiText(UiR.string.model_browser_all_languages)) + languages.map { it to TranslationLanguages.label(it) }
    val shown = catalog.filter { item ->
        item.matches(query, source, target, kind) && (!installedOnly ||
            if (item.compact) item.installed(installed) else item.matchingVariants(query, installed).isNotEmpty()) &&
            (!item.throughEnglish || showPivots)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showCaptionControls) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(config.localTranslationEnabled, { enabled -> update { it.copy(localTranslationEnabled = enabled,
                mode = if (enabled) CaptionMode.TRANSLATE else CaptionMode.CAPTIONS) } })
            Text(uiText(if (config.localTranslationEnabled) UiR.string.ui_translation_enabled_8b961 else UiR.string.ui_disabled_cc_only_9fa78))
        }
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(uiText(UiR.string.model_browser_selected), style = MaterialTheme.typography.labelMedium)
                Text(TranslationOptions.label(config.localTranslationModelId), style = MaterialTheme.typography.titleSmall)
            }
        }
        var engineOptions by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { engineOptions = !engineOptions }) { Text(uiText(UiR.string.explorer_translation_engine_options)) }
        if (engineOptions) TranslationAccelerationSetup()
        Text(uiText(UiR.string.translation_browser_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TabRow(if (installedOnly) 1 else 0) {
            Tab(!installedOnly, onClick = { installedOnly = false }, text = { Text(uiText(UiR.string.model_browser_browse)) })
            Tab(installedOnly, onClick = { installedOnly = true }, text = { Text(uiText(UiR.string.model_browser_manage)) })
        }
        ModelSearchField(query, { query = it })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelFilterPicker(uiText(UiR.string.model_translation_source), source, options, { source = it }, Modifier.weight(1f))
            ModelFilterPicker(uiText(UiR.string.translation_browser_target), target, options, { target = it }, Modifier.weight(1f))
        }
        ModelFilterPicker(uiText(UiR.string.translation_browser_type), kind, listOf(
            "any" to uiText(UiR.string.translation_browser_type_all),
            "compact" to uiText(UiR.string.translation_browser_type_compact),
            "text" to uiText(UiR.string.translation_browser_type_text)), { kind = it })
        if (kind != "text") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(showPivots, { showPivots = it })
            Text(uiText(UiR.string.translation_browser_pivots), style = MaterialTheme.typography.bodyMedium)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(uiText(UiR.string.model_browser_results, shown.size), style = MaterialTheme.typography.labelMedium)
            if (source != "any" || target != "any" || kind != "any" || showPivots) TextButton(onClick = {
                source = "any"; target = "any"; kind = "any"; showPivots = false
            }) { Text(uiText(UiR.string.explorer_clear_filters)) }
        }
        if (shown.isEmpty()) ModelBrowserEmptyState(onReset = {
            query = ""; source = "any"; target = "any"; kind = "any"; installedOnly = false; showPivots = false
        })
        shown.forEach { item -> key(item.id) {
            var variantId by rememberSaveable { mutableStateOf("") }
            var details by rememberSaveable { mutableStateOf(false) }
            val variants = item.matchingVariants(query, installed.takeIf { installedOnly })
            val spec = variants.firstOrNull { it.id == variantId } ?: variants.takeIf { it.isNotEmpty() }
                ?.let { preferredTranslationVariant(it, installed, config.localTranslationModelId) }
            val id = spec?.id ?: item.id
            val ready = id in installed
            val selected = ready && id == config.localTranslationModelId
            val pair = item.pair
            val route = item.route
            val parts = pair?.parts ?: route?.let { it.first.parts + it.second.parts }.orEmpty()
            val progress = translationDownloadState(if (spec != null) setOf(id) else parts.map { it.id }.toSet(), records,
                parts.filter { it.pairId in installed }.map { it.id }.toSet())
            val active = !ready && progress.active
            val record = progress.record
            val failed = progress.failure
            val stage = progress.stage
            val bytes = spec?.bytes ?: pair?.downloadBytes ?: checkNotNull(route).downloadBytes
            val license = spec?.license ?: pair?.license ?: checkNotNull(route).let { listOf(it.first.license, it.second.license).distinct().joinToString("; ") }
            val fixedSource = pair?.source ?: route?.source
            val fixedTarget = pair?.target ?: route?.target
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(uiText(if (item.compact) {
                        if (item.throughEnglish) UiR.string.explorer_translation_pivot else UiR.string.explorer_translation_direct
                    } else UiR.string.explorer_translation_multilingual), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(if (spec != null) uiText(UiR.string.translation_browser_multilingual_size, spec.sourceLanguages.size, bytes / 1_048_576)
                        else uiText(UiR.string.translation_browser_pair_size, TranslationLanguages.label(checkNotNull(fixedSource)),
                            TranslationLanguages.label(checkNotNull(fixedTarget)), bytes / 1_048_576), style = MaterialTheme.typography.bodyMedium)
                    if (route != null) Text(uiText(UiR.string.model_marian_via_english_warning), style = MaterialTheme.typography.bodySmall)
                    if (spec?.family == "milmmt-46" || spec?.family == "index-translate" || spec?.family == "eurollm" || spec?.family == "riva-translate" || spec?.quantization == "Q2_K")
                        Text(uiText(UiR.string.translation_browser_experimental), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (selected || ready || stage != null || progress.completed > 0) ModelStatusLabel(when {
                        selected -> uiText(UiR.string.model_browser_selected)
                        ready -> uiText(UiR.string.model_download_installed)
                        stage != null -> uiText.downloadStage(stage)
                        else -> uiText(UiR.string.translation_browser_partial)
                    }, selected)
                    if (!ready && parts.isNotEmpty() && progress.completed > 0)
                        Text(uiText(UiR.string.model_files_downloaded, progress.completed, progress.total), style = MaterialTheme.typography.bodySmall)
                    if (active) {
                        val fraction = if (spec != null) record?.transferFraction() else null
                        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (stage in setOf(DownloadStage.VERIFYING, DownloadStage.INSTALLING)) Text(uiText(UiR.string.model_download_processing_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    failed?.let { Text(it.error.ifBlank { uiText(UiR.string.ui_download_failed_reason_1_s_8bb99, it.reason) }, color = MaterialTheme.colorScheme.error) }
                    Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && !active && !selected, onClick = {
                        if (!ready && !ByokPolicy.FEATURE_BYOK) {
                            if (spec != null) { importingId = spec.id; importer.launch(arrayOf("*/*")) }
                            else { installedOnly = true; message = uiText(UiR.string.translation_browser_import_pair_hint) }
                        } else if (ready) scope.launch {
                            busy = true; error = null
                            try {
                                withContext(Dispatchers.IO) {
                                    if (spec != null) ModelIntegrity.inspect(store.file(spec), spec.sha256)
                                    else (listOfNotNull(pair) + listOfNotNull(route?.first, route?.second)).forEach { component ->
                                        try { MarianPackage.modelDirectory(context, component) }
                                        catch (e: Exception) { withContext(Dispatchers.Main) { invalidInstalls = invalidInstalls + component.id }; throw e }
                                    }
                                }
                                select(id, fixedTarget)
                                refresh()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                if (spec != null) invalidInstalls = invalidInstalls + spec.id
                                installed = installed - invalidInstalls - id
                                error = e.message ?: e.toString()
                            }
                            finally { busy = false }
                        }
                        else scope.launch {
                            busy = true; error = null; message = null
                            try {
                                withContext(Dispatchers.IO) {
                                    if (spec != null) downloads.enqueue(DownloadSpec.parse(spec.url, spec.fileName), true, spec.id)
                                    else if (pair != null) MarianPackage.enqueue(context, downloads, pair)
                                    else MarianCascade.enqueue(context, downloads, checkNotNull(route))
                                }
                                repairingInstalls = repairingInstalls + (setOf(id) + parts.map { it.pairId }).intersect(invalidInstalls)
                                refresh()
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: e.toString() }
                            finally { busy = false }
                        }
                    }) { Text(when {
                        selected -> uiText(UiR.string.model_browser_selected)
                        active -> uiText.downloadStage(checkNotNull(stage))
                        ready -> uiText(UiR.string.translation_browser_use)
                        !ByokPolicy.FEATURE_BYOK -> uiText(UiR.string.translation_browser_import)
                        stage == DownloadStage.PAUSED -> uiText(UiR.string.ui_resume_download)
                        failed != null -> uiText(UiR.string.ui_retry_download_install_e5444)
                        stage == DownloadStage.READY -> uiText(UiR.string.ui_install_downloaded_model_d458c)
                        progress.completed > 0 -> uiText(UiR.string.translation_browser_download_remaining)
                        else -> uiText(UiR.string.model_browser_download_install)
                    }) }
                    if (spec != null) ModelSourceLinks(spec.originalModelCard, spec.filesUrl, spec.licenseUrl, onError = { error = it })
                    else (listOfNotNull(pair) + listOfNotNull(route?.first, route?.second)).distinctBy { it.id }.forEach { component ->
                        if (route != null) Text(
                            "${TranslationLanguages.label(component.source)} → ${TranslationLanguages.label(component.target)}",
                            style = MaterialTheme.typography.labelMedium)
                        ModelSourceLinks(component.originalModelCard, component.filesUrl, component.licenseUrl, onError = { error = it })
                    }
                    TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) {
                        Text(uiText(if (details) UiR.string.model_browser_hide_details else UiR.string.model_browser_details))
                    }
                    if (details) {
                        if (variants.size > 1) ModelFilterPicker(uiText(UiR.string.model_browser_precision), id,
                            variants.map { it.id to "${it.quantization} · ${it.bytes / 1_048_576} MiB" }, { variantId = it })
                        else if (spec != null) Text(uiText(UiR.string.model_browser_format, spec.quantization), style = MaterialTheme.typography.bodySmall)
                        Text(uiText(UiR.string.ui_1_s_imported_files_must_match_the_selected_artifact_8b0a8, license), style = MaterialTheme.typography.bodySmall)
                        if (spec != null) {
                            if (spec.family in setOf("eurollm", "riva-translate")) Text(spec.evaluationNote, style = MaterialTheme.typography.bodySmall)
                            if (spec.family == "index-translate") Text(uiText(UiR.string.translation_browser_index_new), style = MaterialTheme.typography.bodySmall)
                            val budget = DownloadBudget.estimate(spec.bytes, spec.bytes, record?.bytes?.coerceIn(0, spec.bytes) ?: 0)
                            if (!ready) Text(uiText(UiR.string.model_browser_disk, budget.sameVolumeRequired / 1_048_576), style = MaterialTheme.typography.bodySmall)
                            Text(uiText(UiR.string.translation_browser_memory_note), style = MaterialTheme.typography.bodySmall)
                            Text(uiText(UiR.string.model_browser_revision, spec.revision), style = MaterialTheme.typography.bodySmall)
                            Text(uiText(UiR.string.model_browser_integrity, spec.sha256), style = MaterialTheme.typography.bodySmall)
                            Text(spec.sourceLanguages.sortedBy(TranslationLanguages::label).joinToString(", ") { TranslationLanguages.label(it) }, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(enabled = !busy, onClick = { importingId = spec.id; importer.launch(arrayOf("*/*")) }) { Text(uiText(UiR.string.translation_browser_import)) }
                        } else {
                            Text(uiText(UiR.string.translation_browser_file_count, parts.size), style = MaterialTheme.typography.bodySmall)
                            if (!ready) {
                                val remaining = parts.sumOf { part -> part.bytes - (records.firstOrNull { it.modelId == part.id }?.bytes ?: 0).coerceIn(0, part.bytes) }
                                val budget = DownloadBudget(remaining, translationInstallationBytes(parts))
                                Text(uiText(UiR.string.model_browser_disk, budget.sameVolumeRequired / 1_048_576), style = MaterialTheme.typography.bodySmall)
                                Text(uiText(UiR.string.model_browser_disk_hint), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(uiText(UiR.string.model_browser_revision, pair?.revision ?: checkNotNull(route).let { it.first.revision + " / " + it.second.revision }), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } }
        if (installedOnly && includeLegacy) {
            HorizontalDivider()
            Text(uiText(UiR.string.translation_browser_folder_import), style = MaterialTheme.typography.titleSmall)
            Text(uiText(UiR.string.translation_browser_import_pair_hint), style = MaterialTheme.typography.bodySmall)
            LegacyModelSetup(ModelEngineType.TRANSLATE, config, update, showDownloadSources = false)
            ModelFilterPicker(uiText(UiR.string.translation_browser_gguf_import), importingId.ifBlank { "choose" },
                listOf("choose" to uiText(UiR.string.translation_browser_choose_artifact)) + TranslationCatalog.models.map { it.id to it.label }, { id ->
                    TranslationCatalog.models.firstOrNull { it.id == id }?.let { importingId = it.id; importer.launch(arrayOf("*/*")) }
                })
        }
        if (PlatformTranslation.available) {
            HorizontalDivider()
            TextButton(onClick = { platformDetails = !platformDetails }) { Text(uiText(UiR.string.translation_browser_platform)) }
            if (platformDetails) MlKitSetup(config, update)
        }
        if (showCaptionControls) {
            HorizontalDivider()
            CaptionLanguageFields(config, update, showSource = false,
                targetChoices = captionTranslationChoices(config, CloudConfigStore.sttMode(context)))
            Text(when {
                !config.localTranslationEnabled -> uiText(UiR.string.ui_original_language_cc_translation_model_stays_unloaded_4bd7f)
                spoken == "auto" -> uiText(UiR.string.ui_automatic_routing_uses_the_language_reported_by_asr_unknown_or_mi_6098f)
                TranslationOptions.supports(config.localTranslationModelId, spoken, config.target.languageTag) -> uiText(UiR.string.ui_supported_1_s_2_s_4844b, TranslationLanguages.label(spoken), config.target.label)
                else -> uiText(UiR.string.ui_unsupported_pair_1_s_2_s_cc_continues_b821c, spoken, config.target.languageTag)
            }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

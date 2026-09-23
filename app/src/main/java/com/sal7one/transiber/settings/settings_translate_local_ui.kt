package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import com.sal7one.transiber.translation.*

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
import com.sal7one.transiber.models.ModelRegistry
import kotlinx.coroutines.*
import java.io.File

@Composable
internal fun SettingsTranslateLocalUi(
    config: CaptionOverlayConfig,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    includeLegacy: Boolean = false,
    showCaptionControls: Boolean = true,
) {
    val uiText = rememberUiText()

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
    var showLegacy by rememberSaveable { mutableStateOf(MarianPackage.find(config.localTranslationModelId) != null) }
    var selectedMarianId by rememberSaveable { mutableStateOf(
        config.localTranslationModelId.takeIf { MarianPackage.find(it) != null } ?: TranslationOptions.MARIAN_EN_AR) }
    val registryModels by remember(context) { ModelRegistry.getInstance(context).registeredModels }.collectAsState()
    var showCoverage by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val browse = context.getSharedPreferences("translation-browser",0)
        browse.getString("model",null)?.let { id ->
            if (TranslationCatalog.models.any { it.id == id }) { selected = id; showGguf = true; showLegacy = false }
            else if (MarianPackage.find(id) != null) { selectedMarianId = id; showGguf = false; showLegacy = true }
            browse.edit().remove("model").apply()
        }
    }
    val spec = TranslationCatalog.find(selected)
    LaunchedEffect(Unit) { installed = withContext(Dispatchers.IO) { store.installed() } }
    LaunchedEffect(Unit) {
        if (ByokPolicy.FEATURE_BYOK) while (isActive) {
            try {
                records = withContext(Dispatchers.IO) { downloads.list() }
                installed = withContext(Dispatchers.IO) { store.installed() }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: e.toString() }
            delay(1500)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val importing = spec
            busy = true; message = uiText(UiR.string.ui_copying_and_verifying_1_s_6575d, importing.label)
            try {
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    context.contentResolver.openInputStream(uri)?.use { store.import(it, importing) { job.ensureActive() } }
                        ?: error(uiText(UiR.string.ui_cannot_open_selected_translation_gguf_cae88))
                }
                installed = withContext(Dispatchers.IO) { store.installed() }
                update { it.copy(localTranslationModelId = importing.id) }
                message = uiText(UiR.string.ui_1_s_imported_and_selected_as_the_local_model_849c7, importing.label)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = generateSequence<Throwable>(e) { it.cause }.joinToString("\n") { it.message ?: it.toString() } }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showCaptionControls) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = config.localTranslationEnabled, onCheckedChange = { enabled ->
                update { it.copy(localTranslationEnabled = enabled, mode = if (enabled) CaptionMode.TRANSLATE else CaptionMode.CAPTIONS) }
            })
            Text(if (config.localTranslationEnabled) uiText(UiR.string.ui_translation_enabled_8b961) else uiText(UiR.string.ui_disabled_cc_only_9fa78))
        }
        Text(uiText(UiR.string.ui_active_translator_1_s_dfd1a, TranslationOptions.label(config.localTranslationModelId)), style = MaterialTheme.typography.labelLarge)
        Text(uiText(UiR.string.ui_browse_translators_f9d97), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (PlatformTranslation.available) FilterChip(
                selected = !showLegacy && !showGguf, enabled = !busy,
                onClick = { showLegacy = false; showGguf = false }, label = { Text("ML Kit") })
            FilterChip(selected = showLegacy, enabled = !busy,
                onClick = { showLegacy = true; showGguf = false }, label = { Text(uiText(UiR.string.model_marian_title)) })
            listOf("milmmt-46" to "MiLMMT-46", "hy-mt1.5" to "HY-MT1.5", "hy-mt2" to "Hy-MT2", "translategemma" to "TranslateGemma").forEach { (family, label) ->
                FilterChip(selected = !showLegacy && showGguf && spec.family == family, enabled = !busy,
                    onClick = {
                        if (spec.family != family) {
                            selected = TranslationCatalog.models.firstOrNull { it.family == family && it.id == config.localTranslationModelId }?.id
                                ?: TranslationCatalog.models.first { it.family == family && it.quantization == "Q4_K_M" }.id
                        }
                        showLegacy = false; showGguf = true
                    }, label = { Text(label) })
            }
        }
        if (showLegacy) {
            val pair = checkNotNull(MarianPackage.find(selectedMarianId))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarianPackage.pairs.forEach { option ->
                    FilterChip(selected = selectedMarianId == option.id, enabled = !busy,
                        onClick = { selectedMarianId = option.id },
                        label = { Text("${TranslationLanguages.label(option.source)} → ${TranslationLanguages.label(option.target)}") })
                }
            }
            val model = registryModels.firstOrNull {
                it.engineType == com.sal7one.transiber.models.ModelEngineType.TRANSLATE &&
                    it.isValid && it.isDirectory && it.digest?.hex == pair.treeSha256
            }
            val parts = pair.parts.map { part -> records.firstOrNull { it.modelId == part.id } }
            val completed = parts.count { it?.complete == true }
            Text(uiText(UiR.string.model_marian_description, pair.downloadBytes / 1_048_576, MarianPackage.license),
                style = MaterialTheme.typography.bodySmall)
            Text(if (model != null) uiText(UiR.string.model_installed_verified)
                else uiText(UiR.string.model_files_downloaded, completed, parts.size),
                style = MaterialTheme.typography.bodyMedium)
            if (model != null) Button(enabled = !busy, onClick = { update {
                if (showCaptionControls) it.copy(localTranslationModelId = pair.id,
                    textTranslationProviderId = "local", localTranslationEnabled = true,
                    mode = CaptionMode.TRANSLATE, target = TranslationTarget.of(pair.target))
                else it.copy(localTranslationModelId = pair.id, textTranslationProviderId = "local")
            } }) { Text(if (config.localTranslationModelId == pair.id) uiText(UiR.string.ui_selected_translator_ee2cb)
                else uiText(UiR.string.model_use_marian, TranslationLanguages.label(pair.source),
                    TranslationLanguages.label(pair.target))) }
            if (ByokPolicy.FEATURE_BYOK && model == null) OutlinedButton(enabled = !busy, onClick = { scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) { MarianPackage.enqueue(context, downloads, pair) }
                    records = withContext(Dispatchers.IO) { downloads.list() }
                    message = uiText(UiR.string.model_marian_download_notice, downloads.locationLabel)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: e.toString() }
                finally { busy = false }
            } }) { Text(if (completed > 0) uiText(UiR.string.model_continue_marian)
                else uiText(UiR.string.model_download_marian, TranslationLanguages.label(pair.source),
                    TranslationLanguages.label(pair.target),
                    pair.downloadBytes / 1_048_576)) }
            parts.filterNotNull().firstOrNull { it.failed }?.let { Text(it.error, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { uriHandler.openUri(pair.modelCard) }) { Text(uiText(UiR.string.model_marian_card)) }
            if (includeLegacy && pair.id == TranslationOptions.MARIAN_EN_AR) com.sal7one.transiber.models.LegacyModelSetup(
                com.sal7one.transiber.models.ModelEngineType.TRANSLATE, config, update)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it) }
            return@Column
        }
        if (!showGguf && PlatformTranslation.available) MlKitSetup(config, update = update)
        if (showGguf) {
        Text(uiText(UiR.string.ui_size_precision_49488), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TranslationCatalog.models.filter { it.family == spec.family }.sortedBy { it.bytes }.forEach { model ->
                FilterChip(selected = selected == model.id, enabled = !busy,
                    onClick = { selected = model.id }, label = { Text("${model.quantization} · ${model.bytes / 1_048_576} MiB") })
            }
        }
        Text("${spec.label} · ${if (installed.any { it.id == spec.id }) uiText(UiR.string.model_installed) else uiText(UiR.string.model_not_installed)}")
        if (installed.any { it.id == spec.id }) Button(enabled = !busy, onClick = { update { if (showCaptionControls) it.copy(localTranslationModelId = spec.id, localTranslationEnabled = true, mode = CaptionMode.TRANSLATE) else it.copy(localTranslationModelId = spec.id) } }) {
            Text(if (config.localTranslationModelId == spec.id) uiText(UiR.string.ui_selected_translator_ee2cb) else uiText(UiR.string.ui_use_1_s_5cc45, spec.label))
        }
        Text(if (spec.family == "milmmt-46") uiText(UiR.string.model_milmmt_description, spec.bytes / 1_048_576)
            else uiText(UiR.string.ui_q4_uses_less_storage_and_memory_q6_q8_are_larger_only_the_active_56fac), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }) { Text(uiText(UiR.string.ui_import_1_s_gguf_f7da3, spec.label)) }
        if (ByokPolicy.FEATURE_BYOK) {
            val record = records.firstOrNull { it.modelId == spec.id }
            OutlinedButton(enabled = !busy && record?.active != true, onClick = { scope.launch {
                busy = true
                try {
                    if (record?.failed == true && record.id < 0) downloads.retry(record.id)
                    else withContext(Dispatchers.IO) { downloads.enqueue(DownloadSpec.parse(spec.url, spec.fileName), modelPackage = true, installModelId = spec.id) }
                    records = withContext(Dispatchers.IO) { downloads.list() }
                    message = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { message = e.message ?: e.toString() }
                finally { busy = false }
            } }) { Text(when {
                record?.installing == true -> uiText(UiR.string.ui_installing_8d278)
                record?.active == true -> uiText(UiR.string.ui_downloading_1_s_mib_e010b, record.bytes / 1_048_576)
                record?.failed == true -> uiText(UiR.string.ui_retry_download_install_e5444)
                else -> uiText(UiR.string.ui_download_install_1_s_mib_0e3f4, spec.bytes / 1_048_576)
            }) }
            if (record?.failed == true) Text(record.error.ifBlank { uiText(UiR.string.ui_download_failed_reason_1_s_8bb99, record.reason) }, color = MaterialTheme.colorScheme.error)
            Text(uiText(UiR.string.ui_download_folder_1_s_models_the_original_file_stays_here_after_ins_5b003, downloads.locationLabel), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uriHandler.openUri(spec.modelCard) }) { Text(uiText(UiR.string.ui_source_files_license_50a30)) }
            if (spec.family == "milmmt-46") TextButton(onClick = { uriHandler.openUri("https://huggingface.co/xiaomi-research/MiLMMT-46-1B-v1.0") }) { Text(uiText(UiR.string.model_milmmt_original_card)) }
            if (spec.family == "translategemma") TextButton(onClick = { uriHandler.openUri("https://huggingface.co/google/translategemma-4b-it") }) { Text(uiText(UiR.string.ui_original_google_model_card_251ef)) }
        }
        Text(uiText(UiR.string.ui_1_s_imported_files_must_match_the_selected_artifact_8b0a8, spec.license))
        if (spec.family == "translategemma") Text(uiText(UiR.string.ui_larger_quality_alternative_2_49_gb_download_community_gguf_conver_0c942))
        TextButton(onClick = { showCoverage = !showCoverage }) { Text(if (showCoverage) uiText(UiR.string.ui_hide_language_coverage_88f4d) else uiText(UiR.string.ui_show_supported_source_target_languages_87984)) }
        if (showCoverage) Text(uiText(UiR.string.ui_any_of_these_source_languages_any_other_listed_target_70952) + spec.sourceLanguages.sortedBy(TranslationLanguages::label).joinToString(", ") { TranslationLanguages.label(it) })
        }
        if (showCaptionControls) {
        com.sal7one.transiber.caption.CaptionLanguageFields(config, update, showSource = false,
            targetChoices = com.sal7one.transiber.caption.CaptionLanguageChoices(TranslationOptions.targetLanguages(config.localTranslationModelId, config.streamLanguage), uiText(UiR.string.ui_output_languages_supported_by_the_active_translator_a2090)))
        val active = installed.firstOrNull { it.id == config.localTranslationModelId }
        Text(when {
            !config.localTranslationEnabled -> uiText(UiR.string.ui_original_language_cc_translation_model_stays_unloaded_4bd7f)
            config.localTranslationModelId == TranslationOptions.ML_KIT -> uiText(UiR.string.ui_ml_kit_selected_download_spoken_and_target_packs_above_translatio_7b045)
            active == null -> uiText(UiR.string.ui_import_a_translation_model_to_use_the_bridge_cc_can_run_now_5f78e)
            config.streamLanguage == "auto" -> uiText(UiR.string.ui_automatic_routing_uses_the_language_reported_by_asr_unknown_or_mi_6098f)
            active.supports(config.streamLanguage, config.target.languageTag) -> uiText(UiR.string.ui_supported_1_s_2_s_4844b, TranslationLanguages.label(config.streamLanguage), config.target.label)
            else -> uiText(UiR.string.ui_unsupported_pair_1_s_2_s_cc_continues_b821c, config.streamLanguage, config.target.languageTag)
        })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it) }
    }
}

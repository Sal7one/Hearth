package com.sal7one.transiber.caption

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

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
    val uiText = rememberUiText()

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
            busy = true; error = null; notice = uiText(UiR.string.ui_copying_and_verifying_model_files_keep_this_screen_open_9b0f8)
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
                                ?: error(uiText(UiR.string.ui_choose_the_original_downloaded_model_file_with_its_original_filen_c2fff))
                            store.installPublisher(stream, artifact, -System.nanoTime()) { job.ensureActive() }
                        }
                    } ?: error(uiText(UiR.string.ui_cannot_open_selected_model_zip_ea4d3))
                }
                models = withContext(Dispatchers.IO) { store.list() }
                onModelsChanged(models)
                update { it.copy(engine = imported.profile.captionEngine, modelId = imported.id,
                    mode = CaptionMode.CAPTIONS, streamLanguage = "auto") }
                notice = uiText(UiR.string.ui_imported_1_s_original_language_captions_selected_f9ef8, imported.profile.label)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                notice = null
                error = generateSequence<Throwable>(e) { it.cause }.map { it.message ?: it.toString() }.joinToString("\n")
            } finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(uiText(UiR.string.ui_installed_models_c45cb), style = MaterialTheme.typography.titleSmall)
        if (models.none { it.profile.backend == backend }) Text(uiText(UiR.string.ui_none_installed_yet_download_and_install_below_or_import_an_existi_69b24), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { details = !details }) { Text(if (details) uiText(UiR.string.ui_hide_model_details_41f25) else uiText(UiR.string.ui_model_details_language_options_055d1)) }
        if (details) {
        if (runtimeAvailable) Text(uiText(UiR.string.ui_native_runtime_available_f25c7), style = MaterialTheme.typography.labelMedium)
        Text(uiText(UiR.string.ui_downloads_install_automatically_keep_enough_free_space_for_both_t_5f4db), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }) {
            Text(if (busy) uiText(UiR.string.ui_importing_82059) else uiText(UiR.string.ui_import_existing_model_344f9))
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
        TextButton(onClick = { expandedLanguages = !expandedLanguages }) { Text(uiText(UiR.string.ui_cc_language_support_1_s_languages_26530, ccLanguages.size)) }
        if (expandedLanguages) {
            Text(ccLanguages.sortedBy(com.sal7one.common_jni.translation.TranslationLanguages::label).joinToString(", ") {
                com.sal7one.common_jni.translation.TranslationLanguages.label(it)
            })
            Text(if (backend == com.sal7one.common_jni.speech.SpeechBackend.MOONSHINE) uiText(UiR.string.ui_english_only_short_utterance_decoding_translation_requires_a_sepa_35325) else if (backend == com.sal7one.common_jni.speech.SpeechBackend.QWEN3_ASR)
                uiText(UiR.string.ui_publisher_coverage_30_languages_plus_chinese_dialects_auto_or_an_529f3)
            else uiText(UiR.string.ui_publisher_coverage_28_languages_32_locales_usable_without_fine_tu_a58d7))
        }
        if (details) {
        CaptionLanguageFields(config, update, enabled = !busy, showTarget = false)
        Text(uiText(UiR.string.ui_model_licenses_and_publisher_files_are_linked_above_keep_their_no_f91ec), style = MaterialTheme.typography.bodySmall)
        Text(uiText(UiR.string.ui_during_capture_compute_audio_below_1_means_inference_is_faster_th_8bc2e) +
            uiText(UiR.string.ui_the_audio_queue_is_capped_at_3_seconds_overload_reports_an_error_f66c1),
            style = MaterialTheme.typography.bodySmall)
        }
        if (includeTranslation) com.sal7one.transiber.settings.SettingsTranslateLocalUi(config, update)
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

package com.sal7one.transiber.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.*
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.ocr.*
import com.sal7one.transiber.preferences.ModelSourceBindings
import com.sal7one.transiber.ui.components.*
import kotlinx.coroutines.*
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsOcrLocalUi(onDownloads: () -> Unit = {}, selected: String? = null, onSelect: ((String) -> Unit)? = null) {
    val uiText = rememberUiText()
    val context = LocalContext.current
    if (!com.sal7one.transiber.runtime.LocalModelPolicy.allowed(context)) {
        com.sal7one.transiber.ui.components.LocalModelPolicyNotice()
        return
    }
    val scope = rememberCoroutineScope()
    // The library is also a model chooser. The same persisted reader is consumed
    // by Camera and by the screen-reading service when its session starts.
    val (savedSelection, selectionStore) = rememberOcrPreferences()
    val selectedProfile = selected ?: savedSelection.profileId
    val selectProfile: (String) -> Unit = onSelect ?: { id -> selectionStore.update { it.withProfile(id) } }
    val models = remember { OcrModels(File(context.filesDir, "ocr-models")) }
    val downloads = remember { FileDownloads(context.applicationContext) }
    var revision by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var queuing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var family by com.sal7one.transiber.models.rememberModelBrowseString("ocr-family", OcrCatalog.profiles.firstOrNull { it.id == selected }?.family ?: "any")
    var query by com.sal7one.transiber.models.rememberModelBrowseString("ocr-query", "")
    var installedOnly by com.sal7one.transiber.models.rememberModelBrowseBoolean("ocr-installed")
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var records by remember { mutableStateOf<List<FileDownload>>(emptyList()) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importing = true; error = null
            try {
                withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    uris.forEach { uri ->
                        val asset = context.contentResolver.openInputStream(uri)?.use { models.import(it) { job.ensureActive() } }
                            ?: error(uiText(UiR.string.ui_cannot_open_ocr_model_764c9))
                        ModelSourceBindings.recordImported(context, asset.id, uri, listOf(models.file(asset)))
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { importing = false; revision++ }
        }
    }
    LaunchedEffect(revision) {
        do {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    OcrCatalog.profiles.filter(models::ready).map { it.id }.toSet() to
                        if (ByokPolicy.FEATURE_BYOK) downloads.list() else emptyList()
                }
                installed = snapshot.first; records = snapshot.second
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            delay(1500)
        } while (isActive)
    }
    val profiles = OcrCatalog.profiles
    val recommended = recommendedOcrReader(savedSelection.source, selectedProfile, profiles)
    val shown = profiles.filter { it.matchesBrowser(query, family) && (!installedOnly || it.id in installed) }
        .sortedBy { if (it.id == recommended?.id) 0 else if (it.id == selectedProfile) 1 else 2 }
    val hiddenRelevant = hiddenRelevantOcrReaders(savedSelection.source, selectedProfile, shown, profiles)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsLayout.cardGap)) {
        SettingsHeading(uiText(UiR.string.ui_ocr_camera_reading_12439),
            if (ByokPolicy.FEATURE_BYOK) uiText(UiR.string.ui_paddle_for_general_text_manga_for_japanese_bubbles_meiki_for_japa_76763)
            else uiText(UiR.string.ui_import_the_files_for_your_selected_ocr_engine_below_this_offline_7e9b3))
        Text(uiText(UiR.string.ocr_browser_pack_coverage, profiles.size, profiles.flatMap { it.languages }.toSet().size),
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("ocr-pack-coverage"))
        Text(uiText(UiR.string.ocr_browser_input_output,
            uiText.languageName(savedSelection.source), uiText.languageName(savedSelection.target)),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ocr-input-output"))
        Text(uiText(UiR.string.ocr_browser_input_explainer), style = MaterialTheme.typography.bodySmall)
        recommended?.let { reader ->
            Text(uiText(UiR.string.ocr_browser_recommended_input, uiText.languageName(savedSelection.source), uiText.label(reader)),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ocr-recommended-reader"))
        }
        Text(uiText(UiR.string.ocr_reader_saved_for_both, uiText.label(OcrCatalog.profile(selectedProfile))),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ocr-selected-reader"))
        ModelSearchField(query, { query = it })
        ModelFilterPicker(uiText(UiR.string.model_browser_engine), family, listOf(
            "any" to uiText(UiR.string.model_browser_all_engines), "paddle" to "PaddleOCR", "manga" to "Manga OCR", "meiki" to "Meiki"), { family = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!installedOnly, { installedOnly = false }, label = { Text(uiText(UiR.string.model_browser_all)) }, modifier = Modifier.heightIn(min = SettingsLayout.touchTarget))
            FilterChip(installedOnly, { installedOnly = true }, label = { Text(uiText(UiR.string.model_browser_installed)) }, modifier = Modifier.heightIn(min = SettingsLayout.touchTarget))
        }
        if (hiddenRelevant > 0) {
            Text(uiText(UiR.string.ocr_browser_hidden_readers, hiddenRelevant), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { query = ""; family = "any"; installedOnly = false },
                modifier = Modifier.heightIn(min = SettingsLayout.touchTarget).testTag("ocr-show-all-readers")) {
                Text(uiText(UiR.string.ocr_browser_show_all_readers))
            }
        }
        Text(uiText(UiR.string.model_browser_results, shown.size), style = MaterialTheme.typography.labelMedium)
        if (shown.isEmpty()) ModelBrowserEmptyState(onReset = { query = ""; family = "any"; installedOnly = false })
        shown.forEach { profile -> key(profile.id) {
            var showFiles by rememberSaveable { mutableStateOf(false) }
            val ready = profile.id in installed
            val profileRecords = records.filter { row -> profile.assets.any { it.id == row.modelId || it.filename == row.title } }
            val pending = profileRecords.filter { it.active }
            val failed = profileRecords.firstOrNull { it.failed && profile.assets.any { asset ->
                (asset.id == it.modelId || asset.filename == it.title) && profileRecords.none { other ->
                    other.modelId == it.modelId && (other.active || other.complete)
                }
            } }
            val isSelected = selectedProfile == profile.id
            OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(SettingsLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(SettingsLayout.contentGap)) {
                    Text(uiText.label(profile), style = MaterialTheme.typography.titleMedium)
                    Text(uiText.description(profile), style = MaterialTheme.typography.bodyMedium)
                    Text(uiText(UiR.string.ui_1_s_mib_total_2_s_cee22,
                        "%.1f".format(profile.assets.sumOf { it.bytes } / 1048576.0),
                        if (ready) uiText(UiR.string.model_installed) else uiText(UiR.string.model_needs_files, profile.assets.size)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isSelected || ready) ModelStatusLabel(uiText(if (isSelected) UiR.string.model_browser_selected else UiR.string.model_download_installed), isSelected)
                    if (pending.isNotEmpty()) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        pending.forEach { Text("${it.title}: ${uiText.downloadStage(it.displayStage())}", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (!ready) failed?.let { Text(it.error.ifBlank {
                        uiText(UiR.string.ui_download_failed_reason_1_s_8bb99, it.reason)
                    }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(isSelected, { selectProfile(profile.id) }, label = {
                            Text(uiText(if (isSelected) UiR.string.ui_selected_9a976 else UiR.string.ui_choose_78b7c))
                        }, modifier = Modifier.heightIn(min = SettingsLayout.touchTarget).testTag("ocr-profile-choose-${profile.id}"))
                        if (pending.isNotEmpty()) OutlinedButton(onClick = onDownloads) { Text(uiText(UiR.string.ui_view_download_progress_fb5a1)) }
                        if (ByokPolicy.FEATURE_BYOK && !ready && pending.isEmpty()) Button(enabled = !importing && !queuing, onClick = {
                            scope.launch {
                                queuing = true; error = null
                                try {
                                    withContext(Dispatchers.IO) {
                                        models.missing(profile).forEach { asset ->
                                            downloads.enqueue(DownloadSpec(asset.url, asset.filename), modelPackage = true, installModelId = asset.id)
                                        }
                                    }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message ?: e.toString() }
                                finally { queuing = false; revision++ }
                            }
                        }) { Text(uiText(if (failed != null) UiR.string.ui_retry_download_install_e5444 else UiR.string.ui_download_install_7db0b)) }
                    }
                    TextButton(onClick = { showFiles = !showFiles }) {
                        Text(uiText(if (showFiles) UiR.string.ui_hide_file_links_216d2 else UiR.string.ui_files_import_links_72993))
                    }
                    if (showFiles) profile.assets.forEach { asset ->
                        HorizontalDivider()
                        Text("${asset.remoteFile.substringAfterLast('/')} · ${"%.1f".format(asset.bytes / 1048576.0)} MiB", style = MaterialTheme.typography.labelMedium)
                        Text(asset.license, style = MaterialTheme.typography.bodySmall)
                        Text(uiText(UiR.string.model_browser_revision, asset.revision), style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text(uiText(UiR.string.model_browser_integrity, asset.sha256), style = MaterialTheme.typography.bodySmall) }
                        ModelSourceLinks(asset.modelCard, asset.filesUrl, asset.licenseUrl, onError = { error = it })
                        TextButton(onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                                android.content.ClipData.newPlainText(asset.filename, asset.url))
                        }) { Text(uiText(UiR.string.ui_copy_exact_file_link_9e1fe)) }
                    }
                }
            }
        } }
        HorizontalDivider()
        OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !importing && !queuing, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsLayout.touchTarget)) {
            Text(uiText(if (importing) UiR.string.ui_verifying_model_fa771 else UiR.string.ui_import_downloaded_onnx_files_1f3bd))
        }
        Text(uiText(UiR.string.ui_select_all_onnx_files_for_the_selected_engine_together_file_names_c1ec6), style = MaterialTheme.typography.bodySmall)
        if (ByokPolicy.FEATURE_BYOK) TextButton(onClick = onDownloads) { Text(uiText(UiR.string.ui_downloads_folder_settings_668bd)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

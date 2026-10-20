package com.sal7one.transiber.models

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.*
import com.sal7one.transiber.ui.components.ModelFilterPicker
import com.sal7one.transiber.ui.components.ModelSourceLinks
import kotlinx.coroutines.*

/** Used by both the model library and advanced setup. Network delegation stays play-only. */
@Composable
internal fun ModelSourcePanel(sources: List<ModelSource>) {
    val uiText = rememberUiText()

    if (sources.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable(sources) { mutableIntStateOf(0) }
    var message by remember(sources) { mutableStateOf<String?>(null) }
    var error by remember(sources) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf<List<FileDownload>>(emptyList()) }
    val downloads = remember { FileDownloads(context.applicationContext) }
    LaunchedEffect(Unit) {
        if (ByokPolicy.FEATURE_BYOK) while (isActive) {
            try { records = withContext(Dispatchers.IO) { downloads.list() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            delay(1500)
        }
    }
    val source = sources[selected]
    val artifact = SpeechArtifactCatalog.find(source.id)
    val group = artifact?.components?.let { source -> downloadQueueGroups(records).firstOrNull { it.modelId == source.id } }
    val record = group?.installedRecord ?: group?.failure ?: group?.representatives?.firstOrNull { it.active || it.paused } ?: group?.representatives?.firstOrNull() ?: records.firstOrNull { artifact != null && it.modelId == artifact.id }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText(UiR.string.ui_get_model_e95d4), style = MaterialTheme.typography.titleSmall)
            if (sources.size > 1) ModelFilterPicker(uiText(UiR.string.model_explorer_choose_model), selected.toString(),
                sources.mapIndexed { index, item -> index.toString() to item.label },
                { selected = it.toInt(); message = null; error = null })
            else Text(source.label, style = MaterialTheme.typography.bodyMedium)
            Text(uiText.installation(source), style = MaterialTheme.typography.bodySmall)
            if (ByokPolicy.FEATURE_BYOK) {
                artifact?.let { model ->
                    OutlinedButton(enabled = !busy && record?.active != true && group?.active != true, onClick = { scope.launch {
                        busy = true
                        error = null; message = null
                        try {
                            records = withContext(Dispatchers.IO) {
                                if (model.components != null) {
                                    if (group?.installedRecord != null) downloads.selectInstalled(checkNotNull(group.installedRecord))
                                    else SpeechComponents.enqueue(context, downloads, checkNotNull(model.components))
                                }
                                else if (record?.failed == true && record.id < 0) downloads.retry(record.id)
                                else if (record?.installed == true) downloads.selectInstalled(record)
                                else downloads.enqueue(DownloadSpec.parse(model.url, model.fileName), modelPackage = true, installModelId = model.id)
                                downloads.list()
                            }
                            message = if (record?.installed == true) uiText(UiR.string.ui_selected_1_s_7c44d, source.label) else null
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: e.toString() }
                        finally { busy = false }
                    } }) { Text(when {
                        record?.installed == true -> uiText(UiR.string.ui_use_1_s_5cc45, source.label)
                        record?.failed == true -> uiText(UiR.string.ui_retry_download_install_e5444)
                        record?.installing == true -> uiText(UiR.string.ui_installing_8d278)
                        record?.active == true -> uiText(UiR.string.ui_downloading_1_s_mib_e010b, record.bytes / 1_048_576)
                        else -> uiText(UiR.string.ui_download_install_1_s_mib_0e3f4, model.bytes / 1_048_576)
                    }) }
                    if (record?.failed == true) Text(record.error.ifBlank { uiText(UiR.string.ui_download_failed_reason_1_s_8bb99, record.reason) }, color = MaterialTheme.colorScheme.error)
                    if (record?.active == true || group?.active == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(uiText(UiR.string.ui_download_folder_1_s_models_850ef, downloads.locationLabel), style = MaterialTheme.typography.bodySmall)
                }

            }
            if (ByokPolicy.FEATURE_BYOK) ModelSourceLinks(source.publisher, source.files, onError = { error = it })
            else Text(uiText(UiR.string.ui_offline_build_obtain_files_on_another_device_then_import_locally_cd62f, source.publisher), style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

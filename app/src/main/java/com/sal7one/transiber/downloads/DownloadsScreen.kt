package com.sal7one.transiber.downloads

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.Alignment
import com.sal7one.transiber.models.rememberModelBrowseString
import com.sal7one.transiber.caption.CaptionConfigStore
import com.sal7one.transiber.caption.TranslationTarget
import com.sal7one.transiber.translation.Ct2Package
import com.sal7one.transiber.ui.components.ModelFilterPicker
import com.sal7one.transiber.ui.components.ModelStatusLabel
import com.sal7one.transiber.ui.components.downloadStage
import com.sal7one.transiber.ui.components.FeatureAction
import com.sal7one.transiber.ui.components.FeatureOptionsSheet
import android.app.DownloadManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.models.SpeechDownloads
import com.sal7one.transiber.translation.MarianPackage
import kotlinx.coroutines.*

private data class DownloadChoice(val spec: DownloadSpec, val modelId: String, val modelPackage: Boolean,
    val existingId: Long, val inspection: DownloadInspection, val location: String, val fromLink: Boolean)

@Composable
private fun healthLabel(health: DownloadHealth): String {
    val text = rememberUiText()
    return text(when (health) {
        DownloadHealth.VERIFIED -> UiR.string.ui_download_health_verified
        DownloadHealth.INCOMPLETE -> UiR.string.ui_download_health_incomplete
        DownloadHealth.UNVERIFIED -> UiR.string.ui_download_health_unverified
        DownloadHealth.CHANGED -> UiR.string.ui_download_health_changed
        DownloadHealth.MISSING -> UiR.string.ui_download_health_missing
        DownloadHealth.NOT_INSTALLED -> UiR.string.ui_download_health_not_installed
    })
}

@Composable
fun DownloadsScreen(onBrowseModels: () -> Unit = {}, onVoices: () -> Unit = {}) {
    val uiText = rememberUiText()

 val context = LocalContext.current
 val scope = rememberCoroutineScope()
 val downloads = remember { FileDownloads(context.applicationContext) }
 var folderSettings by rememberSaveable { mutableStateOf(false) }
 val folderScroll = rememberScrollState()
 val directScroll = rememberScrollState()
 var url by rememberSaveable { mutableStateOf("") }
 var directDownload by rememberSaveable { mutableStateOf(false) }
 var queueFilter by rememberModelBrowseString("queue-filter", "all")
 var options by remember { mutableStateOf(false) }
 var filename by rememberSaveable { mutableStateOf("") }
 var records by remember { mutableStateOf(emptyList<FileDownload>()) }
 var voiceReadiness by remember { mutableStateOf(emptyMap<String, Boolean>()) }
 var error by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
 var message by remember { mutableStateOf<String?>(null) }
 var folderLabel by remember { mutableStateOf(downloads.locationLabel) }
 var removing by remember { mutableStateOf<FileDownload?>(null) }
 var choice by remember { mutableStateOf<DownloadChoice?>(null) }
 fun consider(spec: DownloadSpec, modelId: String = "", modelPackage: Boolean = false, fromLink: Boolean = false) {
  scope.launch {
   busy = true; error = null
   try {
    val existing = withContext(Dispatchers.IO) { downloads.existingId(spec, modelId) }
    if (existing == null) {
     withContext(Dispatchers.IO) { downloads.enqueue(spec, modelPackage, modelId) }
     if (fromLink) { url = ""; filename = ""; directDownload = false }
    } else {
     val inspection = downloads.inspect(existing)
     choice = DownloadChoice(spec, modelId, modelPackage, existing, inspection,
      withContext(Dispatchers.IO) { downloads.list().firstOrNull { it.id == existing }?.location.orEmpty() }, fromLink)
     if (fromLink) directDownload = false
    }
    records = withContext(Dispatchers.IO) { downloads.list() }
   } catch (e: CancellationException) { throw e }
   catch (e: Exception) { error = e.message ?: e.toString() }
   finally { busy = false }
  }
 }
 val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
  if (uri != null) try { downloads.chooseFolder(uri); folderLabel = downloads.locationLabel; error = null }
  catch (e: Exception) { error = e.message ?: e.toString() }
 }
 LaunchedEffect(Unit) {
  if (ByokPolicy.FEATURE_BYOK) while(isActive) {
   try {
    val refreshed = withContext(Dispatchers.IO) {
     val rows = downloads.list()
     rows to downloadQueueGroups(rows).filter { it.voiceFamily != null }.associate { it.key to voiceQueueReady(context, it) }
    }
    records = refreshed.first; voiceReadiness = refreshed.second
   }
   catch(e: CancellationException) { throw e }
   catch(e: Exception) { error = e.message ?: e.toString() }
   delay(1000)
  }
 }
 if (!ByokPolicy.FEATURE_BYOK) {
  Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   Text(uiText(UiR.string.ui_import_models_already_on_your_device_35a79))
   FeatureAction(uiText(UiR.string.ui_import_models_721f0), Icons.Default.ViewModule, onBrowseModels)
  }
  return
 }
 val groups = remember(records) { downloadQueueGroups(records) }
 val visible = remember(groups, queueFilter) { groups.filter { when (queueFilter) { "active" -> it.priority == 0; "attention" -> it.priority == 1; "finished" -> it.priority >= 2; else -> true } }
   .sortedWith(compareBy<DownloadQueueGroup> { it.priority }.thenByDescending { it.rows.maxOf { row -> row.id } }) }
 LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
  item(key = "queue-controls") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
   Text(uiText(UiR.string.model_download_queue), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
   TextButton(onClick = onBrowseModels) { Text(uiText(UiR.string.library_browse_models)) }
   Box {
    IconButton(onClick = { options = true }) { Icon(Icons.Default.MoreVert, uiText(UiR.string.library_download_options)) }
    DropdownMenu(options, onDismissRequest = { options = false }) {
     DropdownMenuItem(text = { Text(uiText(UiR.string.ui_folder_30baa)) }, onClick = { options = false; folderSettings = true })
     DropdownMenuItem(text = { Text(uiText(UiR.string.ui_from_link_b9ad3)) }, onClick = { options = false; directDownload = true })
    }
   }
  }
  Text(uiText(UiR.string.library_queue_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  message?.let { Text(it) }
  Text(uiText(UiR.string.model_download_summary, groups.count { it.priority == 0 }, groups.count { it.priority == 1 }), style = MaterialTheme.typography.bodySmall)
  ModelFilterPicker(uiText(UiR.string.model_download_filter), queueFilter, listOf(
   "all" to uiText(UiR.string.model_download_all), "active" to uiText(UiR.string.model_download_active),
   "attention" to uiText(UiR.string.model_download_attention), "finished" to uiText(UiR.string.model_download_finished)), { queueFilter = it })
  DownloadQueueToolbar(downloads, records, onChanged = { records = it }, onError = { error = it })
  if (records.isEmpty()) Text(uiText(UiR.string.ui_your_downloads_will_appear_here_1bc2a), Modifier.padding(vertical = 24.dp))
  else if (visible.isEmpty()) Text(uiText(UiR.string.model_download_no_filter_results), Modifier.padding(vertical = 24.dp))
  } }
  items(visible, key = { it.key }) { group -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
   var expanded by rememberSaveable { mutableStateOf(false) }
   if (group.multipart) GroupedDownloadCard(group, busy, expanded, { expanded = !expanded }, onAction = { scope.launch {
    busy = true; error = null; message = null
    try {
     when {
      group.active -> withContext(Dispatchers.IO) { group.rows.filter { it.active && it.id < 0 }.forEach { downloads.pause(it.id) } }
      group.voiceFamily != null && (voiceReadiness[group.key] == true || group.installedRecord != null) -> onVoices()
      group.installedRecord != null -> {
       com.sal7one.transiber.runtime.LocalModelPolicy.requireAllowed(context, "model selection")
       val installedRow = checkNotNull(group.installedRecord)
       val inspection = downloads.inspect(installedRow.id, checkOriginal = false)
       check(inspection.installed == DownloadHealth.VERIFIED) { inspection.installedError ?: uiText(UiR.string.model_download_missing_install) }
       val pair = group.modelId?.let(MarianPackage::find)
       val ct2 = com.sal7one.common_jni.translation.Ct2Catalog.find(group.modelId.orEmpty())
       if (ct2 != null) Ct2Package.requireRuntime(context)
       if (com.sal7one.transiber.models.SpeechComponents.find(group.modelId.orEmpty()) != null) downloads.selectInstalled(installedRow)
       else CaptionConfigStore.update(context) { current -> current.copy(localTranslationModelId = checkNotNull(group.modelId),
        textTranslationProviderId = "local", target = (pair?.target ?: ct2?.targets?.singleOrNull())?.let(TranslationTarget::of) ?: current.target) }
       message = uiText(UiR.string.ui_model_selected_8d93b)
      }
      group.stage == DownloadStage.READY -> withContext(Dispatchers.IO) {
       if (group.voiceFamily != null) group.representatives.filter { it.complete && !it.installed }.forEach { downloads.reinstall(it.id) }
       else downloads.reinstall(group.representatives.last { it.complete }.id)
      }
      else -> withContext(Dispatchers.IO) {
       group.representatives.filter { (it.failed || it.paused) && it.id < 0 }.forEach { downloads.retry(it.id) }
       if (group.voiceFamily != null) group.required.keys.filter { id -> group.rows.none { it.modelId == id } }.forEach { id ->
        val asset = checkNotNull(DownloadAssets.find(id)) { "Unknown voice component: $id" }
        val filename = if (id == DownloadAssets.MAGPIE_TOKENIZER) "magpie-tokenizer.ranges"
         else checkNotNull(com.sal7one.transiber.voice.VoiceCatalog.find(id)) { "Unknown voice component: $id" }.filename
        downloads.enqueue(DownloadSpec.parse(asset.url, filename), true, id)
       }
       group.modelId?.let(com.sal7one.transiber.models.SpeechComponents::find)?.let { com.sal7one.transiber.models.SpeechComponents.enqueue(context, downloads, it) }
       group.modelId?.let(MarianPackage::find)?.let { MarianPackage.enqueue(context, downloads, it) }
       com.sal7one.common_jni.translation.Ct2Catalog.find(group.modelId.orEmpty())?.let { Ct2Package.enqueue(context, downloads, it) }
      }
     }
     records = withContext(Dispatchers.IO) { downloads.list() }
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { error = e.message ?: e.toString() }
    finally { busy = false }
   } }, voiceReady = voiceReadiness[group.key])
   if (!group.multipart || expanded) group.rows.forEach { item -> key(item.id) {
   var details by rememberSaveable { mutableStateOf(group.multipart) }
   val stage = item.displayStage()
   val title = com.sal7one.transiber.models.SpeechArtifactCatalog.find(item.modelId)?.label
    ?: downloads.translationModel(item)?.label ?: item.title
   val installableModel = item.modelId.takeIf { it.isNotBlank() }
    ?: if (item.id > 0) SpeechDownloads.all.firstOrNull { it.fileName == item.title }?.profile?.id
      ?: downloads.translationModel(item)?.id else null
   OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    ModelStatusLabel(when {
     item.preparingQueued && stage == DownloadStage.VERIFYING -> uiText(UiR.string.download_queue_queued_verification)
     item.preparationAction == "export" && stage == DownloadStage.VERIFYING -> uiText(UiR.string.download_queue_saving_verified)
     else -> uiText.downloadStage(stage)
    })
    if (stage in setOf(DownloadStage.WAITING, DownloadStage.TRANSFERRING, DownloadStage.PAUSED, DownloadStage.FAILED))
     Text("${formatDownloadMiB(item.bytes, uiText.locale)} MiB" + if (item.total > 0) " / ${formatDownloadMiB(item.total, uiText.locale)} MiB" else "", style = MaterialTheme.typography.bodyMedium)
    if (item.active) {
     val progress = item.transferFraction() ?: item.preparationFraction()
     if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
     if (stage == DownloadStage.VERIFYING && !item.preparingQueued && item.verificationTotal > 0)
      Text("${formatDownloadMiB(item.verificationBytes, uiText.locale)} / ${formatDownloadMiB(item.verificationTotal, uiText.locale)} MiB", style = MaterialTheme.typography.bodySmall)
     if (stage in setOf(DownloadStage.VERIFYING, DownloadStage.INSTALLING)) Text(uiText(UiR.string.model_download_processing_hint), style = MaterialTheme.typography.bodySmall)
    }
    if (item.networkWait) Text(uiText(UiR.string.download_queue_waiting_network), style = MaterialTheme.typography.bodySmall)
    if (item.backend.isNotBlank() && item.active) Text("${item.backend} · ${item.connections}× · ${formatDownloadMiB(item.bytesPerSecond, uiText.locale)} MiB/s", style = MaterialTheme.typography.bodySmall)
    if (item.backendNotice.isNotBlank()) Text(item.backendNotice, style = MaterialTheme.typography.bodySmall,
     maxLines = if (details) Int.MAX_VALUE else 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    if (item.error.isNotBlank()) Text(item.error, color = MaterialTheme.colorScheme.error)
    else if (item.reason != 0 && stage in setOf(DownloadStage.FAILED, DownloadStage.PAUSED)) Text(
     uiText(if (stage == DownloadStage.FAILED) UiR.string.ui_downloadmanager_failed_reason_1_s_54785 else UiR.string.ui_paused_downloadmanager_reason_1_s_ea493, item.reason), color = MaterialTheme.colorScheme.error)
    if (!group.multipart) when {
     item.installed -> Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { scope.launch {
      busy = true; error = null; message = null
      try {
       val inspection = downloads.inspect(item.id)
       check(inspection.installed == DownloadHealth.VERIFIED) { "Installed model failed integrity validation. Reinstall it from the downloaded file." }
       downloads.selectInstalled(item); message = uiText(UiR.string.ui_model_selected_8d93b)
      } catch (e: CancellationException) { throw e }
      catch (e: Exception) { error = e.message ?: e.toString() }
      finally { busy = false }
     } }) { Text(uiText(UiR.string.ui_use_model_8d558)) }
     item.complete && installableModel != null && MarianPackage.part(item.modelId) == null -> Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { scope.launch {
      busy = true; error = null; message = null
      try {
       if (item.id < 0 && item.modelId.isNotBlank()) withContext(Dispatchers.IO) { downloads.reinstall(item.id) }
       else {
        val inspection = downloads.inspect(item.id)
        check(inspection.original == DownloadHealth.VERIFIED) { uiText(UiR.string.model_download_invalid_original) }
        downloads.installModel(item.id, installableModel); message = uiText(UiR.string.ui_installed_select_the_model_in_models_06092)
       }
       records = withContext(Dispatchers.IO) { downloads.list() }
      } catch (e: CancellationException) { throw e }
      catch (e: Exception) { error = e.message ?: e.toString() }
      finally { busy = false }
     } }) { Text(uiText(UiR.string.ui_install_downloaded_model_d458c)) }
     item.id < 0 && (item.paused || item.failed) -> Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { scope.launch {
      try { withContext(Dispatchers.IO) { downloads.retry(item.id) }; error = null; records = withContext(Dispatchers.IO) { downloads.list() } }
      catch (e: CancellationException) { throw e }
      catch (e: Exception) { error = e.message ?: e.toString() }
     } }) { Text(uiText(if (item.paused) UiR.string.ui_resume_download else UiR.string.ui_retry_download_install_e5444)) }
     item.id < 0 && item.active -> OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { scope.launch {
      try { withContext(Dispatchers.IO) { downloads.pause(item.id) }; records = withContext(Dispatchers.IO) { downloads.list() } }
      catch (e: CancellationException) { throw e }
      catch (e: Exception) { error = e.message ?: e.toString() }
     } }) { Text(uiText(UiR.string.ui_pause_download)) }
     item.complete -> OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
      try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(downloads.uri(item.id), "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
      catch (e: Exception) { error = e.message ?: e.toString() }
     }) { Text(uiText(UiR.string.ui_open_file_f11b8)) }
    }
    TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) {
     Text(uiText(if (details) UiR.string.model_download_hide_options else UiR.string.model_download_options))
    }
    if (details) {
     if (title != item.title) Text(item.title, style = MaterialTheme.typography.bodySmall)
     if (item.location.isNotBlank()) Text(item.location, style = MaterialTheme.typography.bodySmall)
     Text("${formatDownloadMiB(item.bytes, uiText.locale)} MiB" + if (item.total > 0) " / ${formatDownloadMiB(item.total, uiText.locale)} MiB" else "", style = MaterialTheme.typography.bodySmall)
     FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (item.id < 0 && !item.active) TextButton(enabled = !busy, onClick = {
       try {
        val saved = downloads.record(item.id) ?: error("Download record no longer exists")
        consider(DownloadSpec.parse(saved.getString("url"), saved.getString("title")), item.modelId, saved.optBoolean("modelsFolder"))
       } catch (e: Exception) { error = e.message ?: e.toString() }
      }) { Text(uiText(UiR.string.ui_check_download)) }
      if (item.complete && (item.installed || installableModel != null)) TextButton(onClick = {
       try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(downloads.uri(item.id), "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
       catch (e: Exception) { error = e.message ?: e.toString() }
      }) { Text(uiText(UiR.string.ui_open_file_f11b8)) }
      TextButton(enabled = !busy, onClick = { removing = item }) { Text(uiText(if (item.active) UiR.string.ui_cancel_77dfd else UiR.string.ui_delete_download_d41b5)) }
     }
    }
   } }
  } }
  } }
  item(key = "queue-footer") { Text(uiText(UiR.string.ui_downloads_may_use_mobile_data_installed_models_keep_a_verified_ap_da870), style = MaterialTheme.typography.bodySmall) }
 }
 if(folderSettings && ByokPolicy.FEATURE_BYOK) FeatureOptionsSheet(uiText(UiR.string.ui_download_folder_59317),{folderSettings=false},folderScroll) {
  OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Text(uiText(UiR.string.ui_download_folder_59317), style = MaterialTheme.typography.titleSmall)
   Text(folderLabel)
   Text(uiText(UiR.string.ui_models_go_in_models_other_files_go_in_files_model_downloads_insta_2a3ae), style = MaterialTheme.typography.bodySmall)
   FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    TextButton(onClick = { folderPicker.launch(downloads.folderUri) }) { Text(uiText(UiR.string.ui_choose_folder_1838a)) }
    TextButton(onClick = { try { context.startActivity(downloads.openFolderIntent()) } catch (e: Exception) { error = e.message ?: e.toString() } }) { Text(uiText(UiR.string.ui_open_folder_f9630)) }
    if (downloads.folderUri != null) TextButton(onClick = { downloads.useDefaultFolder(); folderLabel = downloads.locationLabel }) { Text(uiText(UiR.string.ui_use_downloads_folder_59e3c)) }
   }
   Text(uiText(UiR.string.ui_new_downloads_use_this_folder_files_already_downloaded_stay_in_th_67444), style = MaterialTheme.typography.bodySmall)
   error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
  } }
 }
 if(directDownload && ByokPolicy.FEATURE_BYOK) FeatureOptionsSheet(uiText(UiR.string.ui_download_from_link_1687d),{directDownload=false},directScroll) {

   OutlinedTextField(url, { url = it }, label = { Text(uiText(UiR.string.ui_https_file_url_0c976)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().padding(top=2.dp), singleLine = true)
   OutlinedTextField(filename, { filename = it }, label = { Text(uiText(UiR.string.ui_filename_including_extension_ccfb0)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth().padding(top=2.dp), singleLine = true)
   Button(enabled = !busy, onClick = {
    try { consider(DownloadSpec.parse(url, filename), fromLink = true) }
    catch (e: Exception) { error = e.message ?: e.toString() }
   }) { Text(uiText(UiR.string.ui_download_file_77402)) }

  error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
 }
 removing?.let { item -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(uiText(UiR.string.ui_remove_1_s_436e1, item.title)) }, text = { Text(uiText(UiR.string.ui_this_cancels_the_transfer_and_deletes_the_downloaded_file_install_a0c35)) }, confirmButton = { TextButton(onClick = { scope.launch {
  try { withContext(Dispatchers.IO) { downloads.remove(item.id) }; records = withContext(Dispatchers.IO) { downloads.list() } }
  catch (e: CancellationException) { throw e }
  catch(e: Exception) { error = e.message ?: e.toString() }
  removing = null
 } }) { Text(uiText(UiR.string.ui_remove_e9639)) } }, dismissButton = { TextButton(onClick = { removing = null }) { Text(uiText(UiR.string.ui_keep_466fc)) } }) }
 choice?.let { selected ->
  AlertDialog(onDismissRequest = { choice = null; if (selected.fromLink) directDownload = true },
   title = { Text(uiText(UiR.string.ui_existing_download_title)) },
   text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(selected.spec.fileName)
    if (selected.location.isNotBlank()) Text(selected.location, style = MaterialTheme.typography.bodySmall)
    Text(uiText(UiR.string.ui_original_check, healthLabel(selected.inspection.original)))
    selected.inspection.originalError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (selected.modelId.isNotBlank()) Text(uiText(UiR.string.ui_installed_check, healthLabel(selected.inspection.installed)))
    selected.inspection.installedError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    Text(uiText(UiR.string.ui_download_choice_hint), style = MaterialTheme.typography.bodySmall)
   } },
   confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
    busy = true; error = null
    try {
     withContext(Dispatchers.IO) { downloads.enqueue(selected.spec, selected.modelPackage, selected.modelId, duplicate = true) }
     records = withContext(Dispatchers.IO) { downloads.list() }
     if (selected.fromLink) { url = ""; filename = ""; directDownload = false }
     choice = null
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { error = e.message ?: e.toString() }
    finally { busy = false }
   } }) { Text(uiText(UiR.string.ui_download_another_copy)) } },
   dismissButton = { FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    TextButton(onClick = { choice = null; if (selected.fromLink) directDownload = false }) { Text(uiText(UiR.string.ui_keep_copy)) }
    TextButton(enabled = !busy, onClick = { scope.launch {
     busy = true; error = null
     try {
      withContext(Dispatchers.IO) {
       if (selected.inspection.original == DownloadHealth.VERIFIED && selected.modelId.isNotBlank() && selected.inspection.installed != DownloadHealth.VERIFIED)
        downloads.reinstall(selected.existingId)
       else downloads.restart(selected.existingId)
      }
      records = withContext(Dispatchers.IO) { downloads.list() }
      if (selected.fromLink) { url = ""; filename = ""; directDownload = false }
      choice = null
     } catch (e: CancellationException) { throw e }
     catch (e: Exception) { error = e.message ?: e.toString() }
     finally { busy = false }
    } }) { Text(uiText(UiR.string.ui_repair_copy)) }
   } })
 }
}

internal fun formatDownloadMiB(bytes: Long, locale: java.util.Locale): String = String.format(locale, "%,.1f", bytes.coerceAtLeast(0) / 1_048_576.0)

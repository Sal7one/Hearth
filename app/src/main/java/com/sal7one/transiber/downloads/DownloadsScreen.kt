package com.sal7one.transiber.downloads

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ViewModule
import com.sal7one.transiber.ui.components.FeatureAction
import com.sal7one.transiber.ui.components.FeatureOptionsSheet
import com.sal7one.transiber.ui.theme.glassPanel
import androidx.compose.ui.graphics.Color
import android.app.DownloadManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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

@Composable
fun DownloadsScreen(onBrowseModels: () -> Unit = {}) {
    val uiText = rememberUiText()

 val context = LocalContext.current
 val scope = rememberCoroutineScope()
 val downloads = remember { FileDownloads(context.applicationContext) }
 var folderSettings by rememberSaveable { mutableStateOf(false) }
 val folderScroll = rememberScrollState()
 val directScroll = rememberScrollState()
 var url by rememberSaveable { mutableStateOf("") }
 var directDownload by rememberSaveable { mutableStateOf(false) }
 var filename by rememberSaveable { mutableStateOf("") }
 var records by remember { mutableStateOf(emptyList<FileDownload>()) }
 var error by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
 var message by remember { mutableStateOf<String?>(null) }
 var folderLabel by remember { mutableStateOf(downloads.locationLabel) }
 var removing by remember { mutableStateOf<FileDownload?>(null) }
 val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
  if (uri != null) try { downloads.chooseFolder(uri); folderLabel = downloads.locationLabel; error = null }
  catch (e: Exception) { error = e.message ?: e.toString() }
 }
 LaunchedEffect(Unit) {
  if (ByokPolicy.FEATURE_BYOK) while(isActive) {
   try { records = withContext(Dispatchers.IO) { downloads.list() } }
   catch(e: CancellationException) { throw e }
   catch(e: Exception) { error = e.message ?: e.toString() }
   delay(1000)
  }
 }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
  if (!ByokPolicy.FEATURE_BYOK) {
   Text(uiText(UiR.string.ui_import_models_already_on_your_device_35a79))
   FeatureAction(uiText(UiR.string.ui_import_models_721f0), Icons.Default.ViewModule, onBrowseModels)
   return@Column
  }
  FeatureAction(uiText(UiR.string.ui_get_models_21b53), Icons.Default.ViewModule, onBrowseModels, detail=uiText(UiR.string.ui_browse_speech_translation_camera_and_voices_d335d))
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
   FilledTonalButton(onClick={folderSettings=true},modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Icon(Icons.Default.FolderOpen,null); Spacer(Modifier.width(8.dp)); Text(uiText(UiR.string.ui_folder_30baa)) }
   OutlinedButton(onClick={directDownload=true},modifier=Modifier.weight(1f).heightIn(min=52.dp)) { Icon(Icons.Default.Download,null); Spacer(Modifier.width(8.dp)); Text(uiText(UiR.string.ui_from_link_b9ad3)) }
  }
  Text(folderLabel,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  message?.let { Text(it) }
  if (records.isEmpty()) Text(uiText(UiR.string.ui_your_downloads_will_appear_here_1bc2a), Modifier.padding(vertical=24.dp))
  records.forEach { item ->
   Card(Modifier.fillMaxWidth().glassPanel(),colors=CardDefaults.cardColors(containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface)) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(item.title, style = MaterialTheme.typography.titleMedium)
    Text(item.phase.ifBlank { when(item.status) {
     DownloadManager.STATUS_SUCCESSFUL -> uiText(UiR.string.ui_downloaded_c6197)
     DownloadManager.STATUS_FAILED -> uiText(UiR.string.ui_downloadmanager_failed_reason_1_s_54785, item.reason)
     DownloadManager.STATUS_PAUSED -> uiText(UiR.string.ui_paused_downloadmanager_reason_1_s_ea493, item.reason)
     DownloadManager.STATUS_PENDING -> uiText(UiR.string.ui_waiting_33d30)
     else -> uiText(UiR.string.ui_downloading_9b459)
    } })
    if (item.location.isNotBlank()) Text(item.location, style = MaterialTheme.typography.bodySmall)
    Text("${item.bytes / 1_048_576} MiB" + if(item.total > 0) " / ${item.total / 1_048_576} MiB" else "")
    if (item.error.isNotBlank()) Text(item.error, color = MaterialTheme.colorScheme.error)
    if(item.active) {
     if (item.installing || item.total <= 0) LinearProgressIndicator(Modifier.fillMaxWidth())
     else LinearProgressIndicator(progress = { (item.bytes.toFloat() / item.total).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
    }
    val installableModel = item.modelId.takeIf { it.isNotBlank() }
      ?: if (item.id > 0) SpeechDownloads.all.firstOrNull { it.fileName == item.title }?.profile?.id
        ?: downloads.translationModel(item)?.id else null
    if (item.complete && !item.installed && installableModel != null && MarianPackage.part(item.modelId) == null) Button(enabled = !busy, onClick = { scope.launch {
     busy = true; error = null; message = uiText(UiR.string.ui_installing_1_s_b55a5, item.title)
     try { downloads.installModel(item.id, installableModel); message = uiText(UiR.string.ui_installed_select_the_model_in_models_06092) }
     catch (e: CancellationException) { throw e }
     catch (e: Exception) { message = null; error = e.message ?: e.toString() }
     finally { busy = false }
    } }) { Text(uiText(UiR.string.ui_install_downloaded_model_d458c)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
     if (item.id < 0 && item.active) TextButton(enabled = !busy, onClick = { try { downloads.pause(item.id) } catch (e: Exception) { error = e.message ?: e.toString() } }) { Text(uiText(UiR.string.ui_pause_download)) }
     if (item.id < 0 && item.paused) TextButton(enabled = !busy, onClick = { try { downloads.retry(item.id) } catch (e: Exception) { error = e.message ?: e.toString() } }) { Text(uiText(UiR.string.ui_resume_download)) }
     if (item.id < 0 && (item.paused || item.failed)) TextButton(enabled = !busy, onClick = { try { downloads.restart(item.id) } catch (e: Exception) { error = e.message ?: e.toString() } }) { Text(uiText(UiR.string.ui_restart_download)) }
     if (item.installed) TextButton(enabled = !busy, onClick = { scope.launch {
      try { downloads.selectInstalled(item); message = uiText(UiR.string.ui_model_selected_8d93b) }
      catch (e: CancellationException) { throw e }
      catch (e: Exception) { error = e.message ?: e.toString() }
     } }) { Text(uiText(UiR.string.ui_use_model_8d558)) }
     if (item.failed && item.id < 0) TextButton(enabled = !busy, onClick = { try { downloads.retry(item.id) } catch(e: Exception) { error = e.message ?: e.toString() } }) { Text(uiText(UiR.string.ui_retry_9f5cd)) }
     if(item.complete) TextButton(onClick = {
      try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(downloads.uri(item.id), "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
      catch(e: Exception) { error = e.message ?: e.toString() }
     }) { Text(uiText(UiR.string.ui_open_file_f11b8)) }
     TextButton(enabled = !busy, onClick = { removing = item }) { Text(if(item.active) uiText(UiR.string.ui_cancel_77dfd) else uiText(UiR.string.ui_delete_download_d41b5)) }
    }
   } }
  }
  Text(uiText(UiR.string.ui_downloads_may_use_mobile_data_installed_models_keep_a_verified_ap_da870), style = MaterialTheme.typography.bodySmall)
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
   Button(enabled = !busy, onClick = { scope.launch {
    busy = true; error = null
    try { val spec = DownloadSpec.parse(url, filename); withContext(Dispatchers.IO) { downloads.enqueue(spec) }; url = ""; filename = ""; directDownload = false }
    catch (e: CancellationException) { throw e }
    catch(e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
   } }) { Text(uiText(UiR.string.ui_download_file_77402)) }

  error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
 }
 removing?.let { item -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(uiText(UiR.string.ui_remove_1_s_436e1, item.title)) }, text = { Text(uiText(UiR.string.ui_this_cancels_the_transfer_and_deletes_the_downloaded_file_install_a0c35)) }, confirmButton = { TextButton(onClick = { scope.launch {
  try { withContext(Dispatchers.IO) { downloads.remove(item.id) }; records = withContext(Dispatchers.IO) { downloads.list() } }
  catch (e: CancellationException) { throw e }
  catch(e: Exception) { error = e.message ?: e.toString() }
  removing = null
 } }) { Text(uiText(UiR.string.ui_remove_e9639)) } }, dismissButton = { TextButton(onClick = { removing = null }) { Text(uiText(UiR.string.ui_keep_466fc)) } }) }
}

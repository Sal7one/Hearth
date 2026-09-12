package com.sal7one.transiber.downloads

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
import kotlinx.coroutines.*

@Composable
fun DownloadsScreen(onBrowseModels: () -> Unit = {}) {
 val context = LocalContext.current
 val scope = rememberCoroutineScope()
 val downloads = remember { FileDownloads(context.applicationContext) }
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
  Text("Downloads", style = MaterialTheme.typography.headlineSmall)
  if (!ByokPolicy.FEATURE_BYOK) {
   Text("This offline build cannot download files. Import models from your device in Models.")
   return@Column
  }
  OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
   Text("Download folder", style = MaterialTheme.typography.titleSmall)
   Text(folderLabel)
   Text("Models go in models/; other files go in files/. Model downloads install automatically. No export or re-import.", style = MaterialTheme.typography.bodySmall)
   FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    TextButton(onClick = { folderPicker.launch(downloads.folderUri) }) { Text("Choose folder") }
    TextButton(onClick = { try { context.startActivity(downloads.openFolderIntent()) } catch (e: Exception) { error = e.message ?: e.toString() } }) { Text("Open folder") }
    if (downloads.folderUri != null) TextButton(onClick = { downloads.useDefaultFolder(); folderLabel = downloads.locationLabel }) { Text("Use Downloads folder") }
   }
   Text("New downloads use this folder. Files already downloaded stay in their original folder.", style = MaterialTheme.typography.bodySmall)
  } }
  TextButton(onClick = { directDownload = !directDownload }) { Text(if (directDownload) "Hide direct download" else "Download a direct file URL") }
  if (directDownload) {
   OutlinedTextField(url, { url = it }, label = { Text("HTTPS file URL") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(), singleLine = true)
   OutlinedTextField(filename, { filename = it }, label = { Text("Filename, including extension") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(), singleLine = true)
   Button(enabled = !busy, onClick = { scope.launch {
    busy = true; error = null
    try { val spec = DownloadSpec.parse(url, filename); withContext(Dispatchers.IO) { downloads.enqueue(spec) }; url = ""; filename = "" }
    catch (e: CancellationException) { throw e }
    catch(e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
   } }) { Text("Download file") }
  }
  if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  message?.let { Text(it) }
  if (records.isEmpty()) { Text("No downloads yet."); Button(onClick = onBrowseModels) { Text("Browse models") } }
  records.forEach { item ->
   Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(item.title, style = MaterialTheme.typography.titleMedium)
    Text(item.phase.ifBlank { when(item.status) {
     DownloadManager.STATUS_SUCCESSFUL -> "Downloaded"
     DownloadManager.STATUS_FAILED -> "DownloadManager failed · reason ${item.reason}"
     DownloadManager.STATUS_PAUSED -> "Paused · DownloadManager reason ${item.reason}"
     DownloadManager.STATUS_PENDING -> "Waiting"
     else -> "Downloading"
    } })
    if (item.location.isNotBlank()) Text(item.location, style = MaterialTheme.typography.bodySmall)
    Text("${item.bytes / 1_048_576} MiB" + if(item.total > 0) " / ${item.total / 1_048_576} MiB" else "")
    if (item.error.isNotBlank()) Text(item.error, color = MaterialTheme.colorScheme.error)
    if(item.active) {
     if (item.installing || item.total <= 0) LinearProgressIndicator(Modifier.fillMaxWidth())
     else LinearProgressIndicator(progress = { (item.bytes.toFloat() / item.total).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
    }
    val oldModel = if (item.id > 0) SpeechDownloads.all.firstOrNull { it.fileName == item.title }?.profile?.id
      ?: downloads.translationModel(item)?.id else null
    if (item.complete && oldModel != null) Button(enabled = !busy, onClick = { scope.launch {
     busy = true; error = null; message = "Installing ${item.title}…"
     try { downloads.installModel(item.id, oldModel); message = "Installed. Select the model in Models." }
     catch (e: CancellationException) { throw e }
     catch (e: Exception) { message = null; error = e.message ?: e.toString() }
     finally { busy = false }
    } }) { Text("Install downloaded model") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
     if (item.installed) TextButton(enabled = !busy, onClick = { scope.launch {
      try { downloads.selectInstalled(item); message = "Model selected." }
      catch (e: CancellationException) { throw e }
      catch (e: Exception) { error = e.message ?: e.toString() }
     } }) { Text("Use model") }
     if (item.failed && item.id < 0) TextButton(enabled = !busy, onClick = { try { downloads.retry(item.id) } catch(e: Exception) { error = e.message ?: e.toString() } }) { Text("Retry") }
     if(item.complete) TextButton(onClick = {
      try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(downloads.uri(item.id), "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
      catch(e: Exception) { error = e.message ?: e.toString() }
     }) { Text("Open file") }
     TextButton(enabled = !busy, onClick = { removing = item }) { Text(if(item.active) "Cancel" else "Delete download") }
    }
   } }
  }
  Text("Downloads may use mobile data. Installed models keep a verified app-owned copy; deleting a download does not uninstall its model.", style = MaterialTheme.typography.bodySmall)
 }
 removing?.let { item -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${item.title}?") }, text = { Text("This cancels the transfer and deletes the downloaded file. Installed models remain available.") }, confirmButton = { TextButton(onClick = { scope.launch {
  try { withContext(Dispatchers.IO) { downloads.remove(item.id) }; records = withContext(Dispatchers.IO) { downloads.list() } }
  catch (e: CancellationException) { throw e }
  catch(e: Exception) { error = e.message ?: e.toString() }
  removing = null
 } }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } }) }
}

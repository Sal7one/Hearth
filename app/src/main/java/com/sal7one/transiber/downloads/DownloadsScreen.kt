package com.sal7one.transiber.downloads

import android.app.DownloadManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.CaptionConfigStore
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
 var exportId by rememberSaveable { mutableStateOf<Long?>(null) }
 var removing by remember { mutableStateOf<FileDownload?>(null) }
 val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { target ->
  val id = exportId
  if(target != null && id != null) scope.launch {
   busy = true
   try { withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(downloads.uri(id))!!.use { input ->
     context.contentResolver.openOutputStream(target)!!.use { output -> input.copyTo(output) }
    }
   } } catch(e: Exception) { error = e.message ?: e.toString() } finally { busy = false; exportId = null }
  }
 }
 LaunchedEffect(Unit) {
  if (ByokPolicy.FEATURE_BYOK) while(isActive) {
   try { records = withContext(Dispatchers.IO) { downloads.list() } }
   catch(e: CancellationException) { throw e }
   catch(e: Exception) { error = e.message ?: e.toString() }
   delay(1500)
  }
 }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
  Text("File downloads", style = MaterialTheme.typography.headlineSmall)
  if (!ByokPolicy.FEATURE_BYOK) {
   Text("This offline build cannot download files. Import models from your device in Models, or install the cloud-enabled build for downloads.")
   return@Column
  }
  Text("Saved in ${downloads.locationLabel}/models or /files. Install completed translation models here.")
  TextButton(onClick = { directDownload = !directDownload }) { Text(if (directDownload) "Hide direct download" else "Download a direct file URL") }
  if (directDownload) {
  OutlinedTextField(url, { url = it }, label = { Text("HTTPS file URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(filename, { filename = it }, label = { Text("Filename, including extension") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  Button(enabled = !busy, onClick = {
   scope.launch {
    busy = true; error = null
    try { val spec = DownloadSpec.parse(url, filename); withContext(Dispatchers.IO) { downloads.enqueue(spec) }; url = ""; filename = "" }
    catch(e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
   }
  }) { Text("Download file") }
  }
  if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  message?.let { Text(it) }
  if (records.isEmpty()) {
   Text("No downloads yet.")
   Button(onClick = onBrowseModels) { Text("Browse models") }
  }
  records.forEach { item ->
   Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(item.title, style = MaterialTheme.typography.titleMedium)
    Text(when(item.status) {
     DownloadManager.STATUS_SUCCESSFUL -> "Complete"
     DownloadManager.STATUS_FAILED -> "DownloadManager failed · reason ${item.reason}"
     DownloadManager.STATUS_PAUSED -> "Paused · DownloadManager reason ${item.reason}"
     DownloadManager.STATUS_PENDING -> "Waiting"
     else -> "Downloading"
    })
    Text("${item.bytes / 1_048_576} MiB" + if(item.total > 0) " / ${item.total / 1_048_576} MiB" else "")
    if(item.total > 0 && !item.complete && !item.failed) LinearProgressIndicator(progress = { (item.bytes.toFloat() / item.total).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
    val translation = downloads.translationModel(item)
    if (item.complete && translation != null) {
     Button(enabled = !busy, onClick = { scope.launch {
      busy = true; error = null; message = "Verifying and installing ${translation.label}…"
      try {
       downloads.installTranslation(item.id, translation)
       CaptionConfigStore.update(context) { it.copy(localTranslationModelId = translation.id) }
       message = "${translation.label} installed and selected. Enable the local translation bridge in Models."
      } catch (e: CancellationException) { throw e }
      catch (e: Exception) { message = null; error = e.message ?: e.toString() }
      finally { busy = false }
     } }) { Text("Install translation model") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
     if(item.complete) {
      TextButton(enabled = !busy, onClick = { exportId = item.id; export.launch(item.title) }) { Text("Export file") }
      TextButton(onClick = {
       try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(downloads.uri(item.id), "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
       catch(e: Exception) { error = e.message ?: e.toString() }
      }) { Text("Open") }
     }
     TextButton(enabled = !busy, onClick = { removing = item }) { Text(if(item.complete) "Delete" else "Cancel") }
    }
   } }
  }
  Text("Downloads may use mobile data. Roaming is off.")
 }
 removing?.let { item -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${item.title}?") }, text = { Text("This cancels the download and deletes its downloaded file. Installed models and exported copies remain.") }, confirmButton = { TextButton(onClick = { scope.launch { try { withContext(Dispatchers.IO) { downloads.remove(item.id) }; records = withContext(Dispatchers.IO) { downloads.list() } } catch(e: Exception) { error = e.message ?: e.toString() }; removing = null } }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } }) }
}

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
import kotlinx.coroutines.*

@Composable
fun DownloadsScreen() {
 val context = LocalContext.current
 val scope = rememberCoroutineScope()
 val downloads = remember { FileDownloads(context.applicationContext) }
 var url by rememberSaveable { mutableStateOf("") }
 var filename by rememberSaveable { mutableStateOf("") }
 var records by remember { mutableStateOf(emptyList<FileDownload>()) }
 var error by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
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
  Text("Download a model package or any direct HTTPS file URL. Downloads continue in the system notification. Files use app storage; export them to keep a copy after uninstalling.")
  OutlinedTextField(url, { url = it }, label = { Text("HTTPS file URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(filename, { filename = it }, label = { Text("Filename, including extension") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  Button(enabled = !busy, onClick = {
   scope.launch {
    busy = true; error = null
    try { val spec = DownloadSpec.parse(url, filename); withContext(Dispatchers.IO) { downloads.enqueue(spec) }; url = ""; filename = "" }
    catch(e: Exception) { error = e.message ?: e.toString() } finally { busy = false }
   }
  }) { Text("Download file") }
  if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
  error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  if (records.isEmpty()) Text("No downloads yet.")
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
     if(item.complete) {
      TextButton(enabled = !busy, onClick = { exportId = item.id; export.launch(item.title) }) { Text("Export file") }
      TextButton(onClick = {
       try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(downloads.uri(item.id), "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
       catch(e: Exception) { error = e.message ?: e.toString() }
      }) { Text("Open") }
     }
     TextButton(onClick = { removing = item }) { Text(if(item.complete) "Delete" else "Cancel") }
    }
   } }
  }
  Text("To install a downloaded model: export the completed file, then open Models and import it. Large downloads may use mobile data. Roaming is disabled.")
 }
 removing?.let { item -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${item.title}?") }, text = { Text("This cancels the download and deletes its app-stored file. Exported copies remain.") }, confirmButton = { TextButton(onClick = { scope.launch { try { withContext(Dispatchers.IO) { downloads.remove(item.id) }; records = withContext(Dispatchers.IO) { downloads.list() } } catch(e: Exception) { error = e.message ?: e.toString() }; removing = null } }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } }) }
}

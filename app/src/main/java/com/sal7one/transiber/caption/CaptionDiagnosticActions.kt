package com.sal7one.transiber.caption

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

@Composable
internal fun CaptionDiagnosticActions() {
 val context = LocalContext.current
 val scope = rememberCoroutineScope()
 var exporting by remember { mutableStateOf(false) }
 val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
  if (uri != null) scope.launch {
   exporting = true
   try {
    withContext(Dispatchers.IO) { CaptionDiagnostics.exportTrace(context, uri) }
    Toast.makeText(context, "Android exit trace exported", Toast.LENGTH_LONG).show()
   } catch(e: CancellationException) { throw e }
   catch(e: Exception) { Toast.makeText(context, e.message ?: e.toString(), Toast.LENGTH_LONG).show() }
   finally { exporting = false }
  }
 }
 Column {
  TextButton(onClick = {
   val report = try { CaptionDiagnostics.report(context) } catch(e: Exception) { e.toString() }
   context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Caption startup report", report))
   Toast.makeText(context, "Startup report copied", Toast.LENGTH_SHORT).show()
  }) { Text("Copy startup / crash report") }
  if (Build.VERSION.SDK_INT >= 30) TextButton(enabled = !exporting, onClick = { exporter.launch("hearth-android-exit-trace.bin") }) {
   Text(if (exporting) "Exporting…" else "Export Android crash trace")
  }
 }
}

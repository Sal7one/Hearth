package com.sal7one.transiber.caption

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

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
    val uiText = rememberUiText()

 val context = LocalContext.current
 val scope = rememberCoroutineScope()
 var exporting by remember { mutableStateOf(false) }
 val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
  if (uri != null) scope.launch {
   exporting = true
   try {
    withContext(Dispatchers.IO) { CaptionDiagnostics.exportTrace(context, uri) }
    Toast.makeText(context, uiText(UiR.string.ui_android_exit_trace_exported_c5ce1), Toast.LENGTH_LONG).show()
   } catch(e: CancellationException) { throw e }
   catch(e: Exception) { Toast.makeText(context, e.message ?: e.toString(), Toast.LENGTH_LONG).show() }
   finally { exporting = false }
  }
 }
 Column {
  TextButton(onClick = {
   val report = try { CaptionDiagnostics.report(context) } catch(e: Exception) { e.toString() }
   context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(uiText(UiR.string.ui_caption_startup_report_3488f), report))
   Toast.makeText(context, uiText(UiR.string.ui_startup_report_copied_4305c), Toast.LENGTH_SHORT).show()
  }) { Text(uiText(UiR.string.ui_copy_startup_crash_report_a5045)) }
  if (Build.VERSION.SDK_INT >= 30) TextButton(enabled = !exporting, onClick = { exporter.launch("hearth-android-exit-trace.bin") }) {
   Text(if (exporting) uiText(UiR.string.ui_exporting_583cc) else uiText(UiR.string.ui_export_android_crash_trace_79311))
  }
 }
}

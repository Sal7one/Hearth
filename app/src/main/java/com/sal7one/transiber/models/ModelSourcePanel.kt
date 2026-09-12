package com.sal7one.transiber.models

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.*
import kotlinx.coroutines.*

/** Used by both the model library and advanced setup. Network delegation stays play-only. */
@Composable
internal fun ModelSourcePanel(sources: List<ModelSource>) {
    if (sources.isEmpty()) return
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable(sources) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var details by rememberSaveable(sources) { mutableStateOf(false) }
    var message by remember(sources) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val source = sources[selected]
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Get model files", style = MaterialTheme.typography.titleSmall)
            if (sources.size > 1) Box {
                OutlinedButton(onClick = { menu = true }) { Text(source.label) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    sources.forEachIndexed { index, item -> DropdownMenuItem(text = { Text(item.label) }, onClick = { selected = index; menu = false; message = null }) }
                }
            } else Text(source.label, style = MaterialTheme.typography.bodyMedium)
            Text(if (source.download != null) "Publisher files · prepare a speech ZIP before importing" else source.installation, style = MaterialTheme.typography.bodySmall)
            if (ByokPolicy.FEATURE_BYOK) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { try { uri.openUri(source.publisher) } catch (e: Exception) { message = e.message ?: e.toString() } }) { Text("Source & license") }
                    TextButton(onClick = { try { uri.openUri(source.files) } catch (e: Exception) { message = e.message ?: e.toString() } }) { Text("Browse publisher files") }
                }
                source.download?.let { url ->
                    OutlinedButton(enabled = !busy, onClick = { scope.launch {
                        busy = true
                        try {
                            val downloads = FileDownloads(context.applicationContext)
                            withContext(Dispatchers.IO) { downloads.enqueue(DownloadSpec.parse(url, url.substringAfterLast('/')), modelPackage = true) }
                            message = "Download started. Find it in Downloads or ${downloads.locationLabel}/models. Prepare the speech ZIP before import."
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = e.message ?: e.toString() }
                        finally { busy = false }
                    } }) { Text("Download publisher files" + (source.downloadBytes?.let { " · ${it / 1_048_576} MiB" } ?: "")) }
                }
            } else Text("Offline build: obtain files on another device, then import locally. Source: ${source.publisher}", style = MaterialTheme.typography.bodySmall)
            if (source.download != null) {
                TextButton(onClick = { details = !details }) { Text(if (details) "Hide installation steps" else "How to prepare & import") }
                if (details) Text(source.installation, style = MaterialTheme.typography.bodySmall)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

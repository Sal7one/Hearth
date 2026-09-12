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
    var message by remember(sources) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var records by remember { mutableStateOf<List<FileDownload>>(emptyList()) }
    val downloads = remember { FileDownloads(context.applicationContext) }
    LaunchedEffect(Unit) {
        if (ByokPolicy.FEATURE_BYOK) while (isActive) {
            try { records = withContext(Dispatchers.IO) { downloads.list() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: e.toString() }
            delay(1500)
        }
    }
    val source = sources[selected]
    val artifact = SpeechDownloads.find(source.id)
    val record = records.firstOrNull { it.title == artifact?.fileName }

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Get model", style = MaterialTheme.typography.titleSmall)
            if (sources.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                sources.forEachIndexed { index, item ->
                    FilterChip(selected = selected == index, onClick = { selected = index; message = null }, label = { Text(item.label) })
                }


            } else Text(source.label, style = MaterialTheme.typography.bodyMedium)
            Text(source.installation, style = MaterialTheme.typography.bodySmall)
            if (ByokPolicy.FEATURE_BYOK) {
                artifact?.let { model ->
                    OutlinedButton(enabled = !busy && record?.active != true, onClick = { scope.launch {
                        busy = true
                        try {
                            if (record?.failed == true && record.id < 0) downloads.retry(record.id)
                            else if (record?.installed == true) downloads.selectInstalled(record)
                            else withContext(Dispatchers.IO) { downloads.enqueue(DownloadSpec.parse(model.url, model.fileName), modelPackage = true, installModelId = model.profile.id) }
                            records = withContext(Dispatchers.IO) { downloads.list() }
                            message = if (record?.installed == true) "Selected ${source.label}" else null
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = e.message ?: e.toString() }
                        finally { busy = false }
                    } }) { Text(when {
                        record?.installed == true -> "Use ${source.label}"
                        record?.failed == true -> "Retry download & install"
                        record?.installing == true -> "Installing…"
                        record?.active == true -> "Downloading · ${record.bytes / 1_048_576} MiB"
                        else -> "Download & install · ${model.bytes / 1_048_576} MiB"
                    }) }
                    if (record?.failed == true) Text(record.error.ifBlank { "Download failed · reason ${record.reason}" }, color = MaterialTheme.colorScheme.error)
                    if (record?.active == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Download folder: ${downloads.locationLabel}/models", style = MaterialTheme.typography.bodySmall)
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { try { uri.openUri(source.publisher) } catch (e: Exception) { message = e.message ?: e.toString() } }) { Text("Source & license") }
                    if (artifact == null) TextButton(onClick = { try { uri.openUri(source.files) } catch (e: Exception) { message = e.message ?: e.toString() } }) { Text("Browse publisher files") }
                }
            } else Text("Offline build: obtain files on another device, then import locally. Source: ${source.publisher}", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

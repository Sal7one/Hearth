package com.sal7one.transiber.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.ui.components.FeatureOptionsSheet
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** Queue controls remain usable while any unrelated transfer, hash or import is running. */
@Composable
internal fun DownloadQueueToolbar(downloads: FileDownloads, records: List<FileDownload>,
    onChanged: (List<FileDownload>) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val text = rememberUiText()
    val scope = rememberCoroutineScope()
    val preferences = remember { DownloadQueuePreferences(context.applicationContext) }
    var policy by remember { mutableStateOf(preferences.read()) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var results by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    fun operate(action: () -> Unit) { scope.launch {
        try {
            val refreshed = withContext(Dispatchers.IO) { action(); downloads.list() }
            onChanged(refreshed)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { onError(e.message ?: e.toString()) }
    } }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text(R.string.download_queue_background), style = MaterialTheme.typography.titleSmall)
            Text(text(R.string.download_queue_policy_summary, policy.simultaneousFiles, policy.connectionsPerFile), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { operate(downloads::pauseAll) }, enabled = records.any { it.id < 0 && it.active }) { Text(text(R.string.download_queue_pause_all)) }
                OutlinedButton(onClick = { operate(downloads::resumeAll) }, enabled = records.any { it.id < 0 && (it.paused || it.phase == "Interrupted" || it.phase == "Waiting") }) { Text(text(R.string.download_queue_resume_all)) }
                if (records.any { it.id < 0 && it.failed }) TextButton(onClick = { operate(downloads::retryFailed) }) { Text(text(R.string.download_queue_retry_failed)) }
                TextButton(onClick = { settings = true }) { Text(text(R.string.download_queue_settings)) }
                TextButton(onClick = { results = true }) { Text(text(R.string.download_queue_results)) }
            }
        }
    }
    if (settings) DownloadQueueSettings(policy, DownloadRunner.ariaAvailable(context), onDismiss = { settings = false }) { updated ->
        operate {
            preferences.save(updated)
            policy = updated
            if (records.any { it.id < 0 && it.active }) DownloadRunner.start(context)
        }
        settings = false
    }
    if (results) {
        val rows = remember(records) { transferResults(records) }
        AlertDialog(onDismissRequest = { results = false }, title = { Text(text(R.string.download_queue_results)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text(R.string.download_queue_results_hint), style = MaterialTheme.typography.bodySmall)
                if (rows.isEmpty()) Text(text(R.string.download_queue_results_empty))
                LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(rows, key = { it.modelId + ":" + it.title + ":" + rows.indexOf(it) }) { row ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.title, style = MaterialTheme.typography.titleSmall)
                            Text("${row.backend} · ${row.connections}× · ${formatDownloadMiB(row.bytesPerSecond.toLong(), text.locale)} MiB/s", style = MaterialTheme.typography.bodyMedium)
                            Text("${formatDownloadMiB(row.transferredBytes, text.locale)} MiB · ${row.elapsedMillis / 1000}s · ${row.state}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } }, confirmButton = { TextButton(enabled = rows.isNotEmpty(), onClick = { clipboard.setText(AnnotatedString(transferResultsCsv(rows))) }) { Text(text(R.string.download_queue_copy_results)) } },
            dismissButton = { TextButton(onClick = { results = false }) { Text(text(R.string.download_queue_done)) } })
    }
}

@Composable
private fun DownloadQueueSettings(current: DownloadQueuePolicy, ariaAvailable: Boolean, onDismiss: () -> Unit,
    onSave: (DownloadQueuePolicy) -> Unit) {
    val text = rememberUiText()
    var draft by remember { mutableStateOf(current) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var bandwidth by rememberSaveable { mutableStateOf(current.bandwidthKiB.toString()) }
    val scroll = rememberScrollState()
    FeatureOptionsSheet(text(R.string.download_queue_settings), onDismiss, scroll) {
        Text(text(R.string.download_queue_background), style = MaterialTheme.typography.titleMedium)
        Text(text(R.string.download_queue_independent_lanes), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = draft == DownloadQueuePolicy.BALANCED, onClick = { draft = DownloadQueuePolicy.BALANCED; bandwidth = "0" }, label = { Text(text(R.string.download_queue_balanced)) })
            FilterChip(selected = draft == DownloadQueuePolicy.FAST, onClick = { draft = DownloadQueuePolicy.FAST; bandwidth = "0" }, label = { Text(text(R.string.download_queue_fast)) })
        }
        Text(text(R.string.download_queue_simultaneous, draft.simultaneousFiles), style = MaterialTheme.typography.titleSmall)
        Slider(value = draft.simultaneousFiles.toFloat(), onValueChange = { draft = draft.copy(simultaneousFiles = it.roundToInt()) }, valueRange = 1f..8f, steps = 6)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text(R.string.download_queue_unmetered), Modifier.weight(1f))
            Switch(checked = draft.unmeteredOnly, onCheckedChange = { draft = draft.copy(unmeteredOnly = it) })
        }
        TextButton(onClick = { advanced = !advanced }) { Text(text(if (advanced) R.string.download_queue_hide_advanced else R.string.download_queue_advanced)) }
        if (advanced) {
            Text(text(R.string.download_queue_backend), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to R.string.download_queue_auto, "http" to R.string.download_queue_http, "aria2" to R.string.download_queue_aria).forEach { (id, label) ->
                    FilterChip(selected = draft.backend == id, enabled = id != "aria2" || ariaAvailable,
                        onClick = { draft = draft.copy(backend = id) }, label = { Text(text(label)) })
                }
            }
            Text(text(if (ariaAvailable) R.string.download_queue_aria_available else R.string.download_queue_aria_unavailable), style = MaterialTheme.typography.bodySmall)
            Text(text(R.string.download_queue_connections, draft.connectionsPerFile), style = MaterialTheme.typography.titleSmall)
            Slider(value = draft.connectionsPerFile.toFloat(), onValueChange = { draft = draft.copy(connectionsPerFile = it.roundToInt()) }, valueRange = 1f..16f, steps = 14)
            Text(text(R.string.download_queue_connection_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(bandwidth, onValueChange = { value -> if (value.length <= 7 && value.all(Char::isDigit)) bandwidth = value },
                label = { Text(text(R.string.download_queue_bandwidth)) }, supportingText = { Text(text(R.string.download_queue_unlimited)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(text(R.string.download_queue_retries, draft.retries), style = MaterialTheme.typography.titleSmall)
            Slider(value = draft.retries.toFloat(), onValueChange = { draft = draft.copy(retries = it.roundToInt()) }, valueRange = 0f..10f, steps = 9)
        }
        Button(onClick = { onSave(draft.copy(bandwidthKiB = bandwidth.toIntOrNull()?.coerceIn(0, 1_048_576) ?: 0)) }, modifier = Modifier.fillMaxWidth()) { Text(text(R.string.download_queue_apply)) }
        Text(text(R.string.download_queue_apply_hint), style = MaterialTheme.typography.bodySmall)
    }
}

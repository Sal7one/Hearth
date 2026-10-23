package com.sal7one.transiber.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R
import com.sal7one.transiber.i18n.rememberUiText

@Composable
internal fun ModelImportStatus(owner: String) {
    val batches by ModelImportQueue.state.collectAsState()
    val batch = batches[owner] ?: return
    val refreshFailure by ModelInstallRefresh.failures.collectAsState()
    val text = rememberUiText()
    var details by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val successful = batch.outcomes.count { it.error.isBlank() }
            val failed = batch.outcomes.count { it.error.isNotBlank() }
            Text(text(R.string.model_import_batch_summary, successful, failed, batch.requested), style = MaterialTheme.typography.titleSmall)
            if (batch.active) {
                Text(text(R.string.model_import_batch_current, batch.current, batch.requested, batch.currentFile), style = MaterialTheme.typography.bodySmall)
                if (batch.totalBytes > 0) LinearProgressIndicator(progress = { (batch.bytes.toDouble() / batch.totalBytes).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(text(R.string.model_import_batch_background), style = MaterialTheme.typography.bodySmall)
            } else if (batch.cancelled) Text(text(R.string.model_import_batch_cancelled), style = MaterialTheme.typography.bodySmall)
            else Text(text(R.string.model_import_batch_choose), style = MaterialTheme.typography.bodySmall)
            refreshFailure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (batch.active) TextButton(onClick = { ModelImportQueue.cancel(owner) }) { Text(text(R.string.model_import_batch_cancel)) }
                if (batch.outcomes.isNotEmpty()) TextButton(onClick = { details = true }) { Text(text(R.string.model_import_batch_results)) }
            }
        }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(text(R.string.model_import_batch_results)) },
        text = { LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(batch.outcomes) { _, outcome -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(outcome.label, style = MaterialTheme.typography.titleSmall)
                Text(outcome.error.ifBlank { outcome.result }, color = if (outcome.error.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            } }
        } }, confirmButton = { TextButton(onClick = { details = false }) { Text(text(R.string.download_queue_done)) } })
}

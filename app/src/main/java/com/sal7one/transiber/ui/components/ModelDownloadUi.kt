package com.sal7one.transiber.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.sal7one.transiber.R
import com.sal7one.transiber.downloads.DownloadStage
import com.sal7one.transiber.i18n.UiText
import com.sal7one.transiber.i18n.rememberUiText

/** A compact picker replaces growing walls of filter chips as catalogs expand. */
@Composable
internal fun ModelFilterPicker(
    label: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    searchable: Boolean = options.size > 12,
    enabled: Boolean = true,
) {
    val uiText = rememberUiText()
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { query = ""; expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                if (searchable) OutlinedTextField(query, { query = it }, singleLine = true,
                    label = { Text(uiText(R.string.model_picker_search)) },
                    modifier = Modifier.widthIn(min = 240.dp, max = 360.dp).padding(horizontal = 12.dp, vertical = 4.dp))
                val matches = options.filter { query.isBlank() || it.first.contains(query.trim(), ignoreCase = true) || it.second.contains(query.trim(), ignoreCase = true) }
                matches.forEach { (id, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(id) },
                        leadingIcon = if (id == selected) ({ Icon(Icons.Default.Check, null) }) else null,
                        modifier = Modifier.semantics { this.selected = id == selected })
                }
                if (matches.isEmpty()) Text(uiText(R.string.model_picker_no_matches), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal fun UiText.downloadStage(stage: DownloadStage): String = this(when (stage) {
    DownloadStage.WAITING -> R.string.model_download_waiting
    DownloadStage.TRANSFERRING -> R.string.model_download_transferring
    DownloadStage.VERIFYING -> R.string.model_download_verifying
    DownloadStage.INSTALLING -> R.string.model_download_installing
    DownloadStage.PAUSED -> R.string.model_download_paused
    DownloadStage.FAILED -> R.string.model_download_failed
    DownloadStage.READY -> R.string.model_download_ready
    DownloadStage.INSTALLED -> R.string.model_download_installed
})

@Composable
internal fun ModelStatusLabel(text: String, selected: Boolean = false) {
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

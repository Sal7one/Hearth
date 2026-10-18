package com.sal7one.transiber.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.sal7one.transiber.R
import com.sal7one.transiber.downloads.DownloadStage
import com.sal7one.transiber.i18n.UiText
import com.sal7one.transiber.i18n.rememberUiText
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.models.ModelSearch
import com.sal7one.transiber.models.isSafeModelSourceUrl
import com.sal7one.transiber.models.modelSourceBrowserUrl

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
                val matches = options.filter { ModelSearch.matches(query, it.first, it.second) }
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

@Composable
internal fun ModelSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: String? = null) {
    val uiText = rememberUiText()
    OutlinedTextField(value, onValueChange, singleLine = true, modifier = modifier.fillMaxWidth(),
        label = { Text(label ?: uiText(R.string.model_browser_search)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = if (value.isNotEmpty()) ({
            IconButton(onClick = { onValueChange("") }) {
                Icon(Icons.Default.Close, uiText(R.string.model_explorer_clear_search))
            }
        }) else null)
}

@Composable
internal fun ModelBrowserEmptyState(onReset: () -> Unit, modifier: Modifier = Modifier) {
    val uiText = rememberUiText()
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(uiText(R.string.model_explorer_no_results_title), style = MaterialTheme.typography.titleMedium)
            Text(uiText(R.string.model_browser_no_results), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onReset) { Text(uiText(R.string.model_explorer_reset_filters)) }
        }
    }
}

/** External navigation, including a system browser, is unavailable in the offline build. */
@Composable
internal fun ModelSourceLinks(publisherUrl: String, artifactUrl: String? = null, licenseUrl: String? = null,
    onError: (String) -> Unit, modifier: Modifier = Modifier) {
    val uiText = rememberUiText()
    val handler = LocalUriHandler.current
    val links = buildList {
        add(R.string.model_explorer_model_card to publisherUrl)
        artifactUrl?.takeIf { it != publisherUrl }?.let { add(R.string.model_explorer_pinned_files to it) }
        licenseUrl?.takeIf { it != publisherUrl && it != artifactUrl }?.let { add(R.string.model_explorer_license to it) }
    }
    if (!ByokPolicy.FEATURE_BYOK) {
        var details by rememberSaveable(publisherUrl, artifactUrl, licenseUrl) { mutableStateOf(false) }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SelectionContainer {
                Text(uiText(R.string.model_explorer_source_offline, publisherUrl),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (links.size > 1) {
                TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) {
                    Text(uiText(if (details) R.string.model_explorer_hide_source_details else R.string.model_explorer_source_details))
                }
                if (details) links.drop(1).forEach { (label, url) ->
                    Text(uiText(label), style = MaterialTheme.typography.labelMedium)
                    SelectionContainer {
                        Text(modelSourceBrowserUrl(url), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        return
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        links.forEach { (label, url) ->
            TextButton(contentPadding = PaddingValues(horizontal = 0.dp), onClick = {
                if (!isSafeModelSourceUrl(url)) onError(uiText(R.string.model_explorer_invalid_source))
                else try { handler.openUri(modelSourceBrowserUrl(url)) }
                catch (e: Exception) { onError(e.message ?: e.toString()) }
            }) {
                Text(uiText(label))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.OpenInNew, null, Modifier.size(14.dp))
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

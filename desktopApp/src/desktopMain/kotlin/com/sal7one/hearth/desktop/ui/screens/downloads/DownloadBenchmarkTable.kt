package com.sal7one.hearth.desktop.ui.screens.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.*
import com.sal7one.hearth.desktop.ui.screens.models.ModelsDivider
import com.sal7one.hearth.desktop.ui.screens.models.formatBytes
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** All verified transfer measurements, sorted locally; no catalogue or selection limit is applied. */
@Composable
internal fun DownloadBenchmarkTable(state: DownloadsState) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("Recent") }
    var limit by rememberSaveable(query, sort) { mutableStateOf(40) }
    val names = state.items.associate { it.id to it.name }
    val matching = state.measurements.filter { measurement -> query.isBlank() ||
        listOf(measurement.modelId, names[measurement.modelId].orEmpty(), measurement.component, measurement.backend)
            .any { it.contains(query, ignoreCase = true) } }
    val rows = when (sort) {
        "Speed" -> matching.sortedByDescending { it.bytesPerSecond }
        "Duration" -> matching.sortedBy { it.elapsedMillis }
        else -> matching.sortedByDescending { it.recordedAtMillis }
    }
    HearthCard(padding = 16.dp) {
        Text("Verified transfer history · ${matching.size} measurements", style = HearthType.rowTitle)
        Spacer(Modifier.height(10.dp))
        HearthTextField(query, { query = it }, "Search model, component or backend")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Recent", "Speed", "Duration").forEach { value ->
                HearthButton(value, { sort = value }, size = ButtonSize.Small,
                    tone = if (sort == value) ButtonTone.Primary else ButtonTone.Secondary)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Cell("Model / component", 3f); Cell("Backend", 1f); Cell("Network", 1f)
            Cell("Speed", 1f); Cell("Seconds", 1f); Cell("Connections", 1f); Cell("Resumed", 1f)
        }
        Spacer(Modifier.height(8.dp)); ModelsDivider()
        rows.take(limit).forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Cell("${names[m.modelId] ?: m.modelId}\n${m.component}", 3f)
                Cell(m.backend, 1f); Cell(formatBytes(m.bytes), 1f); Cell("${formatBytes(m.bytesPerSecond)}/s", 1f)
                Cell("%.2f".format(m.elapsedMillis / 1000.0), 1f); Cell(m.connections.toString(), 1f)
                Cell(if (m.resumedBytes < 0) "Unknown" else formatBytes(m.resumedBytes), 1f)
            }
            ModelsDivider()
        }
        if (rows.size > limit) {
            Spacer(Modifier.height(12.dp))
            HearthButton("Show ${minOf(40, rows.size - limit)} more", { limit += 40 }, tone = ButtonTone.Ghost, size = ButtonSize.Small)
        }
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float) {
    Text(text, modifier = Modifier.weight(weight).padding(end = 10.dp), style = HearthType.small,
        color = HearthTheme.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

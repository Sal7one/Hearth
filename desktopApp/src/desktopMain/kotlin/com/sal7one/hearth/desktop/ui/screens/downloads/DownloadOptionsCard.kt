package com.sal7one.hearth.desktop.ui.screens.downloads

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.data.*
import com.sal7one.hearth.desktop.ui.components.*
import com.sal7one.hearth.desktop.ui.screens.models.formatBytes
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

@Composable
internal fun DownloadOptionsCard(state: DownloadsState, actions: DownloadsActions) {
    val colors = HearthTheme.colors
    val options = state.options
    var advanced by rememberSaveable { mutableStateOf(false) }
    HearthCard(padding = 16.dp) {
        Text("Download options", style = HearthType.rowTitle)
        Spacer(Modifier.height(8.dp))
        Text("${options.simultaneousFiles} files at once. Verification and imports use a separate lane.", style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HearthButton("Balanced", { actions.onConfigure(DesktopDownloadOptions.Balanced.copy(aria2Executable = options.aria2Executable)) }, size = ButtonSize.Small, tone = ButtonTone.Secondary)
            HearthButton("Fast", { actions.onConfigure(DesktopDownloadOptions.Fast.copy(aria2Executable = options.aria2Executable)) }, size = ButtonSize.Small, tone = ButtonTone.Secondary)
        }
        Spacer(Modifier.height(12.dp))
        NumberControl("Simultaneous files", options.simultaneousFiles, 1..8) { actions.onConfigure(options.copy(simultaneousFiles = it)) }
        Spacer(Modifier.height(12.dp))
        HearthButton(if (advanced) "Hide advanced" else "Advanced", { advanced = !advanced }, size = ButtonSize.Small, tone = ButtonTone.Ghost)
        if (advanced) {
            Spacer(Modifier.height(12.dp))
            Text("Transport", style = HearthType.label)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DesktopDownloadBackend.entries.forEach { backend ->
                    HearthButton(when (backend) { DesktopDownloadBackend.AUTO -> "Auto"; DesktopDownloadBackend.HTTP -> "HTTP"; DesktopDownloadBackend.ARIA2 -> "aria2" },
                        { actions.onConfigure(options.copy(backend = backend)) }, size = ButtonSize.Small,
                        tone = if (backend == options.backend) ButtonTone.Primary else ButtonTone.Secondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            NumberControl("Connections per file", options.connectionsPerFile, 1..16) { actions.onConfigure(options.copy(connectionsPerFile = it)) }
            Spacer(Modifier.height(6.dp))
            Text("aria2 uses parallel connections. HTTP uses one resumable connection. Auto finds installed aria2 on this computer, or uses HTTP.", style = HearthType.small, color = colors.muted)
            Spacer(Modifier.height(12.dp))
            NumberControl("Retries", options.retries, 0..10) { actions.onConfigure(options.copy(retries = it)) }
            Spacer(Modifier.height(12.dp))
            val limitMiB = (options.bandwidthBytesPerSecond / (1024 * 1024)).toInt().coerceAtMost(1024)
            NumberControl("MiB/s per file (0 = unlimited)", limitMiB, 0..1024) {
                actions.onConfigure(options.copy(bandwidthBytesPerSecond = it * 1024L * 1024L))
            }
            Spacer(Modifier.height(12.dp))
            HearthButton(if (options.aria2Executable.isBlank()) "Locate aria2 executable" else "Change aria2 executable", actions.onChooseAria2,
                tone = ButtonTone.Secondary, size = ButtonSize.Small)
            if (options.aria2Executable.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(options.aria2Executable, style = HearthType.small, color = colors.muted)
                HearthButton("Use HTTP fallback", { actions.onConfigure(options.copy(aria2Executable = "", backend = DesktopDownloadBackend.AUTO)) }, size = ButtonSize.Small, tone = ButtonTone.Ghost)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Pinned SHA-256 checks always run before installation. Paused downloads retain their partial files across restarts.", style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(12.dp))
        HearthButton(DownloadsCopy.OPEN_FOLDER, actions.onOpenFolder, tone = ButtonTone.Ghost, size = ButtonSize.Small)
    }
}

@Composable
private fun NumberControl(label: String, value: Int, range: IntRange, update: (Int) -> Unit) {
    Column {
        Text(label, style = HearthType.small, color = HearthTheme.colors.muted)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HearthButton("−", { update(value - 1) }, enabled = value > range.first, size = ButtonSize.Small, tone = ButtonTone.Secondary)
            Text(value.toString(), style = HearthType.label, modifier = Modifier.width(30.dp))
            HearthButton("+", { update(value + 1) }, enabled = value < range.last, size = ButtonSize.Small, tone = ButtonTone.Secondary)
        }
    }
}

@Composable
internal fun DownloadMeasurementsCard(state: DownloadsState, actions: DownloadsActions) {
    val colors = HearthTheme.colors
    HearthCard(padding = 16.dp) {
        Text("Transfer benchmarks", style = HearthType.rowTitle)
        Spacer(Modifier.height(8.dp))
        Text("Measured network throughput from verified downloads. Resume bytes are reported separately in the CSV; verification time is excluded from throughput. Host speed and cache state affect comparisons.",
            style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(12.dp))
        if (state.measurements.isEmpty()) Text("Download a model to record a measurement.", style = HearthType.small, color = colors.muted)
        state.measurements.takeLast(5).reversed().forEach { m ->
            Text("${m.backend} · ${formatBytes(m.bytesPerSecond)}/s", style = HearthType.label)
            Text("${m.modelId} · ${formatBytes(m.bytes)} · ${m.elapsedMillis / 1000.0}s", style = HearthType.small, color = colors.muted)
            Spacer(Modifier.height(10.dp))
        }
        HearthButton("Export ${state.measurements.size} measurements", actions.onExportMeasurements,
            enabled = state.measurements.isNotEmpty(), tone = ButtonTone.Secondary, size = ButtonSize.Small)
    }
}

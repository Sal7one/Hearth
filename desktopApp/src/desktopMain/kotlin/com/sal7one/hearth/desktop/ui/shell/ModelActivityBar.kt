package com.sal7one.hearth.desktop.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ModelLibraryView
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.ui.components.*
import com.sal7one.hearth.desktop.ui.screens.models.formatBytes
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** App-wide feedback remains visible while the user works on another screen. */
@Composable
internal fun ModelActivityBar(
    state: ModelLibraryView, catalog: List<DesktopModelCard>,
    onOpenDownloads: () -> Unit, onDismiss: () -> Unit,
) {
    if (state.transfers.isEmpty() && state.notice == null && !state.refreshing) return
    val active = state.transfers.values.firstOrNull { !it.queued } ?: state.transfers.values.firstOrNull()
    val queued = state.transfers.values.count { it.queued }
    val title = if (active != null) {
        val name = catalog.firstOrNull { it.id == active.id }?.name ?: active.id
        "${active.label} $name" + if (state.transfers.size > 1) " · ${state.transfers.size} jobs ($queued queued)" else ""
    } else state.notice ?: "Checking model library…"
    Row(Modifier.fillMaxWidth().background(HearthTheme.colors.surface2).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = HearthType.small, color = HearthTheme.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (active != null && active.total > 0 && !active.queued) {
                Meter((active.downloaded.toFloat() / active.total).coerceIn(0f, 1f))
                Text("${formatBytes(active.downloaded)} of ${formatBytes(active.total)}" +
                    active.component.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                    style = HearthType.rowSub, color = HearthTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        HearthButton("Downloads", onOpenDownloads, size = ButtonSize.Small, tone = ButtonTone.Secondary)
        if (active == null && state.notice != null) HearthButton("Dismiss", onDismiss, size = ButtonSize.Small, tone = ButtonTone.Ghost)
    }
}

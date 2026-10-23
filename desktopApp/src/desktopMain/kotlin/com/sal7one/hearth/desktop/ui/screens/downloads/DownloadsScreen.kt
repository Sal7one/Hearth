package com.sal7one.hearth.desktop.ui.screens.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.sal7one.hearth.desktop.ui.components.HearthTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.components.SwitchRow
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.screens.models.ModelsSubnav
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.Space

/**
 * Transfers. Four honest buckets — downloading, available, installed, broken —
 * driven by the library's real installation map and the controller's live transfer.
 */
@Composable
internal fun DownloadsScreen(
    state: DownloadsState,
    actions: DownloadsActions,
    current: DesktopScreen = DesktopScreen.DOWNLOADS,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showMeasurements by rememberSaveable { mutableStateOf(false) }
    val visible = state.copy(items = state.items.filter { query.isBlank() ||
        listOf(it.name, it.id, it.stageLabel, it.detail).any { value -> value.contains(query, ignoreCase = true) } })
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = DownloadsCopy.EYEBROW,
            title = DownloadsCopy.TITLE,
            lede = DownloadsCopy.LEDE,
            actions = {
                HearthButton(
                    text = if (state.refreshing) DownloadsCopy.RECHECKING else DownloadsCopy.RECHECK,
                    onClick = actions.onRefresh,
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                    icon = HearthIcons.Check,
                    enabled = !state.refreshing,
                )
            },
        )

        ModelsSubnav(current = current, onNavigate = actions.onNavigate)

        DownloadSummary(state)
        if (state.measurements.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HearthButton(if (showMeasurements) "Hide transfer benchmarks" else "Compare ${state.measurements.size} transfer benchmarks", { showMeasurements = !showMeasurements },
                tone = ButtonTone.Secondary, size = ButtonSize.Small)
            if (showMeasurements) {
                Spacer(Modifier.height(12.dp))
                DownloadBenchmarkTable(state)
            }
        }
        Spacer(Modifier.height(16.dp))
        HearthTextField(query, { query = it }, "Search downloads, models or stages")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HearthButton("Pause all", actions.onPauseAll, tone = ButtonTone.Secondary, size = ButtonSize.Small,
                enabled = state.items.any { it.bucket == DownloadBucket.DOWNLOADING && !it.paused })
            HearthButton("Resume all", actions.onResumeAll, tone = ButtonTone.Secondary, size = ButtonSize.Small,
                enabled = state.items.any { it.paused })
            HearthButton("Queue ${visible.available.size} matching models", { actions.onDownloadAll(visible.available.map { it.id }) },
                tone = ButtonTone.Secondary, size = ButtonSize.Small, enabled = visible.available.isNotEmpty())
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DownloadGroupCard(
                    title = "Download queue and imports",
                    items = visible.downloading,
                    emptyText = "No transfer is running. Start one from the library.",
                    tone = ChipTone.Accent,
                    onDownload = actions.onDownload,
                    onCancel = actions.onCancel,
                    onRemove = actions.onRemove,
                    onRestart = actions.onRestart,
                    showWhenEmpty = false,
                )
                DownloadGroupCard(
                    title = DownloadsCopy.AVAILABLE,
                    items = visible.available,
                    emptyText = DownloadsCopy.EMPTY_AVAILABLE,
                    onDownload = actions.onDownload,
                    onCancel = actions.onCancel,
                    onRemove = actions.onRemove,
                    onRestart = actions.onRestart,
                )
                visible.broken.takeIf { it.isNotEmpty() }?.let { broken ->
                    DownloadGroupCard(
                        title = DownloadsCopy.NEEDS_ATTENTION,
                        items = broken,
                        emptyText = "",
                        tone = ChipTone.Err,
                        onDownload = actions.onDownload,
                        onCancel = actions.onCancel,
                    onRemove = actions.onRemove,
                    onRestart = actions.onRestart,
                    )
                }
                DownloadGroupCard(
                    title = DownloadsCopy.INSTALLED,
                    items = visible.installed,
                    emptyText = DownloadsCopy.EMPTY_INSTALLED,
                    tone = ChipTone.Ok,
                    onDownload = actions.onDownload,
                    onCancel = actions.onCancel,
                    onRemove = actions.onRemove,
                    onRestart = actions.onRestart,
                )
            }
            Column(Modifier.width(Space.sideColumn), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DownloadOptionsCard(state, actions)
                DownloadMeasurementsCard(state, actions)
            }
        }
    }
}

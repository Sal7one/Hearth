package com.sal7one.hearth.desktop.ui.screens.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DownloadGroupCard(
                    title = DownloadsCopy.DOWNLOADING,
                    items = state.downloading,
                    emptyText = "No transfer is running. Start one from the library.",
                    tone = ChipTone.Accent,
                    onDownload = actions.onDownload,
                    onCancel = actions.onCancel,
                    showWhenEmpty = false,
                )
                DownloadGroupCard(
                    title = DownloadsCopy.AVAILABLE,
                    items = state.available,
                    emptyText = DownloadsCopy.EMPTY_AVAILABLE,
                    onDownload = actions.onDownload,
                    onCancel = actions.onCancel,
                )
                state.broken.takeIf { it.isNotEmpty() }?.let { broken ->
                    DownloadGroupCard(
                        title = DownloadsCopy.NEEDS_ATTENTION,
                        items = broken,
                        emptyText = "",
                        tone = ChipTone.Err,
                        onDownload = actions.onDownload,
                        onCancel = actions.onCancel,
                    )
                }
                DownloadGroupCard(
                    title = DownloadsCopy.INSTALLED,
                    items = state.installed,
                    emptyText = DownloadsCopy.EMPTY_INSTALLED,
                    tone = ChipTone.Ok,
                    onDownload = actions.onDownload,
                    onCancel = actions.onCancel,
                )
            }
            Column(Modifier.width(Space.sideColumn), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HearthCard(padding = 16.dp) {
                    SwitchRow(
                        DownloadsCopy.VERIFY_AFTER,
                        DownloadsCopy.VERIFY_AFTER_DETAIL,
                        checked = true,
                        onCheckedChange = {},
                        enabled = false,
                    )
                    Spacer(Modifier.height(14.dp))
                    HearthButton(
                        DownloadsCopy.OPEN_FOLDER,
                        actions.onOpenFolder,
                        tone = ButtonTone.Ghost,
                        size = ButtonSize.Small,
                    )
                }
            }
        }
    }
}

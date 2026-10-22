package com.sal7one.hearth.desktop.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import com.sal7one.hearth.desktop.ui.theme.Space

/**
 * The library. One list per stage, a filter row, and a side column that explains
 * verification. Installing and activating are separate actions on purpose.
 */
@Composable
internal fun ModelsScreen(
    state: ModelsState,
    actions: ModelsActions,
    current: DesktopScreen = DesktopScreen.MODELS,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = ModelsCopy.EYEBROW,
            title = ModelsCopy.TITLE,
            lede = ModelsCopy.LEDE,
            actions = {
                HearthButton(
                    text = if (state.refreshing) ModelsCopy.REFRESHING else ModelsCopy.REFRESH,
                    onClick = actions.onRefresh,
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                    icon = HearthIcons.Check,
                    enabled = !state.refreshing,
                )
            },
        )

        ModelsSubnav(current = current, onNavigate = actions.onNavigate)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ModelsToolbar(
                    filter = state.filter,
                    onSearch = actions.onSearch,
                    onStage = actions.onFilterStage,
                    onToggleInstalled = actions.onToggleInstalledOnly,
                )

                state.transfer?.let { transfer -> TransferCard(transfer) }

                if (state.isEmpty) {
                    Banner(
                        text = if (state.isEmptyBecauseOfFilter) ModelsCopy.EMPTY else "The catalog is empty.",
                        tone = BannerTone.Neutral,
                    )
                    Spacer(Modifier.height(2.dp))
                }

                state.groups.forEach { group ->
                    ModelGroupCard(group = group, onAction = actions.onAction, onImport = actions.onImport,
                        importEnabled = !state.refreshing && state.transfer == null)
                }
            }
            ModelsSidebar(
                state = state,
                onOpenFolder = actions.onOpenFolder,
                onOpenDownloads = { actions.onNavigate(DesktopScreen.DOWNLOADS) },
                modifier = Modifier.width(Space.sideColumn),
            )
        }
    }
}

/** Visible progress for a real transfer: model name, bytes, and what is happening. */
@Composable
private fun TransferCard(transfer: TransferView) {
    val colors = HearthTheme.colors
    Column {
        Text(
            if (transfer.checking) "Verifying ${transfer.modelName}" else "Downloading ${transfer.modelName}",
            style = HearthType.rowTitle,
            color = colors.text,
        )
        Spacer(Modifier.height(6.dp))
        com.sal7one.hearth.desktop.ui.components.Meter(fraction = transfer.fraction)
        Spacer(Modifier.height(6.dp))
        Text(transfer.detail, style = HearthType.small, color = colors.muted)
    }
}

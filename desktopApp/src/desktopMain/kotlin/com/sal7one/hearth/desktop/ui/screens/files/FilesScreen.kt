package com.sal7one.hearth.desktop.ui.screens.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.Meter
import com.sal7one.hearth.desktop.ui.components.MeterTone
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.screens.settings.FormRow
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSubnav
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import com.sal7one.hearth.desktop.ui.theme.Space

/**
 * Storage settings. Only folders Hearth actually writes are listed, and export or
 * import always asks for a path first.
 */
@Composable
internal fun FilesScreen(
    state: FilesState,
    actions: FilesActions,
    current: DesktopScreen = DesktopScreen.FILES,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = FilesCopy.EYEBROW,
            title = FilesCopy.TITLE,
            lede = FilesCopy.LEDE,
            actions = {
                HearthButton(
                    FilesCopy.OPEN,
                    onClick = { actions.onOpenFolder(state.dataDirectory) },
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                )
            },
        )

        SettingsSubnav(current = current, onNavigate = actions.onNavigate)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HearthCard(padding = 20.dp) {
                    Text(FilesCopy.LOCATIONS, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                    Spacer(Modifier.height(3.dp))
                    Text(FilesCopy.LOCATIONS_DETAIL, style = HearthType.small, color = colors.muted)
                    Spacer(Modifier.height(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.locations.forEach { entry ->
                            LocationRow(entry, onOpen = { actions.onOpenFolder(entry.absolutePath) })
                        }
                    }
                }

                HearthCard(padding = 20.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(FilesCopy.STORAGE, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                        Spacer(Modifier.weight(1f))
                        Chip(state.modelsLabel, tone = ChipTone.Neutral)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(FilesCopy.STORAGE_DETAIL, style = HearthType.small, color = colors.muted)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(FilesCopy.MODELS_STORAGE, style = HearthType.small, color = colors.textDim, modifier = Modifier.width(140.dp))
                        Meter(fraction = state.storageFraction, modifier = Modifier.weight(1f), tone = MeterTone.Accent)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "${state.modelsLabel} of ${state.catalogLabel}",
                            style = HearthType.monoSmall,
                            color = colors.muted,
                        )
                    }
                }

                HearthCard(padding = 20.dp) {
                    Text(FilesCopy.PORTABLE, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                    Spacer(Modifier.height(3.dp))
                    Text(FilesCopy.PORTABLE_DETAIL, style = HearthType.small, color = colors.muted)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HearthButton(FilesCopy.EXPORT, actions.onExportSetup, tone = ButtonTone.Primary, size = ButtonSize.Small)
                        HearthButton(FilesCopy.IMPORT, actions.onImportSetup, tone = ButtonTone.Secondary, size = ButtonSize.Small)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(FilesCopy.PORTABLE_NOTE, style = HearthType.small, color = colors.muted)
                }
            }

            Column(Modifier.width(Space.sideColumn), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                HearthCard(padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(FilesCopy.DISK, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                        Spacer(Modifier.weight(1f))
                        Chip(FilesCopy.HEALTHY, tone = ChipTone.Ok)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(state.freeLabel, style = HearthType.h2.copy(fontSize = 22.sp), color = colors.text)
                    Spacer(Modifier.height(10.dp))
                    Text(FilesCopy.DISK_DETAIL, style = HearthType.small, color = colors.muted)
                }

                HearthCard(padding = 16.dp) {
                    Text(FilesCopy.ADVANCED, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                    Spacer(Modifier.height(4.dp))
                    Text(FilesCopy.ADVANCED_DETAIL, style = HearthType.small, color = colors.muted)
                    Spacer(Modifier.height(12.dp))
                    Banner(FilesCopy.ENV, tone = BannerTone.Neutral)
                }
            }
        }
    }
}

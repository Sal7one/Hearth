package com.sal7one.hearth.desktop.ui.screens.appearance

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
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.Segmented
import com.sal7one.hearth.desktop.ui.components.SwitchRow
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayState
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSubnav
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import com.sal7one.hearth.desktop.ui.theme.Space

/**
 * Appearance. The palette applies to the whole window as soon as it is chosen, so
 * the page is its own preview.
 */
@Composable
internal fun AppearanceScreen(
    state: AppearanceState,
    overlay: OverlayState,
    actions: AppearanceActions,
    storageLabel: String,
    freeLabel: String,
    current: DesktopScreen = DesktopScreen.APPEARANCE,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = AppearanceCopy.EYEBROW,
            title = AppearanceCopy.TITLE,
            lede = AppearanceCopy.LEDE,
            actions = {
                Text("Now: ${state.brightnessLabel}", style = HearthType.small, color = colors.muted)
            },
        )

        SettingsSubnav(current = current, onNavigate = actions.onNavigate)

        HearthCard(padding = 20.dp) {
            Text(AppearanceCopy.THEME, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
            Spacer(Modifier.height(3.dp))
            Text(AppearanceCopy.THEME_DETAIL, style = HearthType.small, color = colors.muted)
            Spacer(Modifier.height(16.dp))
            PaletteSwatches(
                palettes = state.palettes,
                selected = state.selected,
                onSelect = actions.onPalette,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HearthCard(Modifier.weight(1f), padding = 20.dp) {
                Text(AppearanceCopy.BRIGHTNESS, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                Spacer(Modifier.height(3.dp))
                Text(AppearanceCopy.BRIGHTNESS_DETAIL, style = HearthType.small, color = colors.muted)
                Spacer(Modifier.height(14.dp))
                Segmented(
                    options = listOf("Light", "Dark"),
                    selected = if (state.dark) 1 else 0,
                    onSelect = { index -> actions.onDark(index == 1) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(18.dp))
                SwitchRow(
                    AppearanceCopy.MOTION,
                    AppearanceCopy.MOTION_DETAIL,
                    state.reduceMotion,
                    actions.onReduceMotion,
                )
            }
            Column(Modifier.width(Space.sideColumn)) {
                StorageStrip(
                    storageLabel = storageLabel,
                    freeLabel = freeLabel,
                    onOpenFiles = { actions.onNavigate(DesktopScreen.FILES) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        CaptionPreviewCard(
            overlay = overlay,
            onOpenOverlay = { actions.onNavigate(DesktopScreen.OVERLAY) },
        )
    }
}

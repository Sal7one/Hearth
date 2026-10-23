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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayState
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSubnav
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
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
            actions = { Text("Now: ${state.brightnessLabel}", style = HearthType.small, color = colors.muted) },
        )

        SettingsSubnav(current = current, onNavigate = actions.onNavigate)

        PaletteCard(state, actions.onPalette)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BrightnessCard(state, actions.onDark, actions.onReduceMotion, Modifier.weight(1f))
            Column(Modifier.width(Space.sideColumn)) {
                StorageStrip(
                    storageLabel = storageLabel,
                    freeLabel = freeLabel,
                    onOpenFiles = { actions.onNavigate(DesktopScreen.FILES) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        CaptionPreviewCard(overlay = overlay, onOpenOverlay = { actions.onNavigate(DesktopScreen.OVERLAY) })
    }
}

package com.sal7one.hearth.desktop.ui.screens.overlay

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.SwitchRow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

@Composable
internal fun OverlayShortcutCard(state: OverlayState, onChange: (OverlaySettings) -> Unit) {
    val colors = HearthTheme.colors
    HearthCard(padding = 18.dp) {
        SwitchRow("Keyboard shortcuts", "Control captions while another app is focused.", state.settings.shortcutsEnabled,
            { onChange(state.settings.copy(shortcutsEnabled = it)) })
        Spacer(Modifier.height(12.dp))
        OverlayCommand.entries.forEach { command ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(command.title, style = HearthType.small, color = colors.text)
                Text(command.shortcutLabel(), style = HearthType.monoSmall, color = colors.muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(state.shortcutStatus, style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(8.dp))
        Text("On the caption window: arrows move, Shift moves faster, Alt + arrows resize, Esc hides. Hover for controls. Your layout and style are saved.",
            style = HearthType.small, color = colors.muted)
        state.controlIssue?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = HearthType.small, color = colors.err)
        }
    }
}

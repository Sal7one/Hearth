package com.sal7one.hearth.desktop.ui.screens.overlay

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.HearthSlider
import com.sal7one.hearth.desktop.ui.components.SwitchRow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

@Composable
internal fun OverlayHoverCard(settings: OverlaySettings, onChange: (OverlaySettings) -> Unit) {
    val colors = HearthTheme.colors
    HearthCard(padding = 16.dp) {
        Text("Hover controls", style = HearthType.h3, color = colors.text)
        Spacer(Modifier.height(6.dp))
        Text("Keep the controls compact or give yourself more room to grab.", style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(14.dp))
        Text("Toolbar size · ${(settings.toolbarScale * 100).toInt()}%", style = HearthType.label, color = colors.text)
        HearthSlider((settings.toolbarScale - 0.75f) / 0.75f,
            { onChange(settings.copy(toolbarScale = 0.75f + it * 0.75f)) })
        Spacer(Modifier.height(12.dp))
        val height = settings.bounds?.height ?: settings.windowHeight
        Text("Hover area height · $height pt", style = HearthType.label, color = colors.text)
        HearthSlider((height - 90) / 630f, { value ->
            onChange(overlayWithHeight(settings, (90 + value * 630).toInt()))
        })
        Text("90–720 pt · the bottom edge stays in place. You can also drag any edge of the overlay.",
            style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(14.dp))
        SwitchRow("Shade the hover area", "Show the full draggable region when hovered.", settings.shadeHoverArea,
            { onChange(settings.copy(shadeHoverArea = it)) })
    }
}

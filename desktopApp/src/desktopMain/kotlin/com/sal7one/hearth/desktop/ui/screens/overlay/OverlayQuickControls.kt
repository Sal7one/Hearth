package com.sal7one.hearth.desktop.ui.screens.overlay

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.HearthSlider
import com.sal7one.hearth.desktop.ui.components.SwitchRow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme

/** Compact controls shared by the utility window; a fixed width also permits intrinsic measurement. */
@Composable
internal fun OverlayQuickControls(settings: OverlaySettings, onChange: (OverlaySettings) -> Unit) {
    Column(Modifier.width(240.dp)) {
        Text("Hover controls", color = HearthTheme.colors.text, fontSize = 15.sp)
        Spacer(Modifier.height(14.dp))
        Text("Toolbar · ${(settings.toolbarScale * 100).toInt()}%", color = HearthTheme.colors.muted, fontSize = 12.sp)
        HearthSlider((settings.toolbarScale - 0.75f) / 0.75f,
            { onChange(settings.copy(toolbarScale = 0.75f + it * 0.75f)) })
        Spacer(Modifier.height(10.dp))
        val height = settings.bounds?.height ?: settings.windowHeight
        Text("Area height · $height pt", color = HearthTheme.colors.muted, fontSize = 12.sp)
        HearthSlider((height - 90) / 630f, { onChange(overlayWithHeight(settings, (90 + it * 630).toInt())) })
        Spacer(Modifier.height(10.dp))
        SwitchRow("Shade hover area", "Show the draggable region", settings.shadeHoverArea,
            { onChange(settings.copy(shadeHoverArea = it)) })
    }
}

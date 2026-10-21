package com.sal7one.hearth.desktop.ui.screens.appearance

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** The seven palettes. Choosing one applies to the whole window immediately. */
@Composable
internal fun PaletteCard(
    state: AppearanceState,
    onPalette: (com.sal7one.hearth.desktop.ui.theme.AccentPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 20.dp) {
        Text(AppearanceCopy.THEME, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
        Spacer(Modifier.height(3.dp))
        Text(AppearanceCopy.THEME_DETAIL, style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(16.dp))
        PaletteSwatches(palettes = state.palettes, selected = state.selected, onSelect = onPalette)
    }
}

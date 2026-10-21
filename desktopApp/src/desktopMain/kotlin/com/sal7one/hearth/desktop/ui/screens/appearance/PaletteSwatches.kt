package com.sal7one.hearth.desktop.ui.screens.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Swatches carry three real colours from the palette (accent, raised surface,
 * ground) so the preview is the palette, not an approximation of it.
 */
@Composable
internal fun PaletteSwatches(
    palettes: List<PaletteOption>,
    selected: AccentPreset,
    onSelect: (AccentPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        palettes.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { palette ->
                    PaletteSwatch(
                        palette = palette,
                        selected = palette.preset == selected,
                        onClick = { onSelect(palette.preset) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PaletteSwatch(
    palette: PaletteOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier
            .background(colors.surface, RoundedCornerShape(14.dp))
            .border(
                1.dp,
                when {
                    selected -> colors.accentLine
                    hovered -> colors.borderStrong
                    else -> colors.border
                },
                RoundedCornerShape(14.dp),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(11.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(palette.accent, palette.raised, palette.ground).forEach { swatch ->
                Column(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .background(swatch, RoundedCornerShape(7.dp))
                        .border(1.dp, colors.borderStrong, RoundedCornerShape(7.dp)),
                ) {}
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(palette.preset.label, style = HearthType.rowTitle, color = colors.text)
        Spacer(Modifier.height(2.dp))
        Text(
            palette.preset.description,
            style = HearthType.small.copy(fontSize = 11.sp),
            color = colors.muted,
            maxLines = 2,
        )
    }
}

package com.sal7one.hearth.desktop.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * The language pair as a single control: two named languages and a swap between
 * them. Named, never a code field — a code is the secondary label, not the label.
 */
@Composable
internal fun HeroLanguagePair(
    sourceLabel: String,
    targetLabel: String,
    modifier: Modifier = Modifier,
    onPickSource: () -> Unit = {},
    onSwap: () -> Unit = {},
    onPickTarget: () -> Unit = {},
) {
    val colors = HearthTheme.colors
    Row(
        modifier
            .background(colors.bg.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .border(1.dp, colors.borderStrong, RoundedCornerShape(999.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LanguageChoice(sourceLabel, onPickSource)
        Box(
            Modifier
                .size(30.dp)
                .background(Color.Transparent, CircleShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onSwap),
            contentAlignment = Alignment.Center,
        ) {
            Icon(HearthIcons.Swap, "Swap languages", Modifier.size(15.dp), tint = colors.muted)
        }
        LanguageChoice(targetLabel, onPickTarget)
    }
}

@Composable
private fun LanguageChoice(label: String, onClick: () -> Unit) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .height(32.dp)
            .background(if (hovered) colors.surface3 else Color.Transparent, RoundedCornerShape(999.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = HearthType.label, color = colors.text, maxLines = 1)
        Spacer(Modifier.width(7.dp))
        Text(label.take(2).uppercase(), style = HearthType.monoSmall.copy(fontSize = 10.5.sp), color = colors.muted)
    }
}

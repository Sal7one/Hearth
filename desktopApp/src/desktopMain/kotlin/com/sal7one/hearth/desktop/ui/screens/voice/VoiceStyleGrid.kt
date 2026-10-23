package com.sal7one.hearth.desktop.ui.screens.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Ten styles, five per row. The selected one is the saved `voiceId`; nothing here
 * is a preview until the operator presses Play in the page header.
 */
@Composable
internal fun VoiceStyleGrid(
    styles: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        styles.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { style ->
                    VoiceStyleButton(
                        style = style,
                        selected = style == selected,
                        onClick = { onSelect(style) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceStyleButton(
    style: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier
            .background(if (selected) colors.accentSoft else colors.surface2, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                when {
                    selected -> colors.accentLine
                    hovered -> colors.borderStrong
                    else -> colors.border
                },
                RoundedCornerShape(12.dp),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(26.dp).background(if (selected) colors.accent else colors.surface3, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                style,
                style = HearthType.monoSmall.copy(fontSize = 10.sp),
                color = if (selected) colors.onAccent else colors.muted,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (style.startsWith("F")) "Female" else "Male",
            style = HearthType.small.copy(fontSize = 10.5.sp),
            color = colors.muted,
        )
    }
}

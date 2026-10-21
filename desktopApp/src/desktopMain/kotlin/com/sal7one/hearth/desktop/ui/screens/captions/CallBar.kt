package com.sal7one.hearth.desktop.ui.screens.captions

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * The one control surface for a live session: what it listens to, which pair, which
 * optional stages, and start/stop. Everything else about a session lives in the
 * transcript or the side column.
 */
@Composable
internal fun CallBar(
    state: CaptionsState,
    actions: CaptionsActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    var inputMenu by remember { mutableStateOf(false) }

    Row(
        modifier
            .height(58.dp)
            .shadow(if (colors.isDark) 0.dp else 14.dp, RoundedCornerShape(999.dp))
            .background(colors.surface3, RoundedCornerShape(999.dp))
            .border(1.dp, colors.borderStrong, RoundedCornerShape(999.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            BarItem(
                label = if (state.inputLabel == CaptionsCopy.MICROPHONE) state.deviceName ?: CaptionsCopy.MICROPHONE else state.inputLabel,
                active = state.microphone,
                icon = HearthIcons.Microphone,
                onClick = { inputMenu = true },
            )
            DropdownMenu(inputMenu, { inputMenu = false }) {
                DropdownMenuItem(
                    text = { Text(state.deviceName ?: CaptionsCopy.MICROPHONE) },
                    onClick = { inputMenu = false; actions.onPickMicrophone() },
                )
                DropdownMenuItem(
                    text = { Text(CaptionsCopy.AUDIO_FILE) },
                    onClick = { inputMenu = false; actions.onPickAudioFile() },
                )
                DropdownMenuItem(
                    text = { Text(CaptionsCopy.TEXT_INPUT) },
                    onClick = { inputMenu = false; actions.onPickText() },
                )
            }
        }

        BarDivider()

        BarItem(label = state.sourceLabel, active = false, onClick = actions.onSwapLanguages, mono = true)
        BarIcon(HearthIcons.Swap, "Swap languages", actions.onSwapLanguages)
        BarItem(label = state.targetLabel, active = false, onClick = actions.onSwapLanguages, mono = true)

        BarDivider()

        BarToggle(CaptionsCopy.TRANSLATE, state.translationEnabled, actions.onToggleTranslate)
        BarToggle("Voice", state.voiceEnabled, actions.onToggleVoice)

        BarDivider()

        val busy = state.busy
        Box(
            Modifier
                .size(44.dp)
                .background(if (state.active) colors.err else colors.text, CircleShape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(enabled = !busy) { if (state.active) actions.onStop() else actions.onStart() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state.active) HearthIcons.Stop else HearthIcons.Microphone,
                contentDescription = if (state.active) CaptionsCopy.STOP else CaptionsCopy.START,
                modifier = Modifier.size(18.dp),
                tint = if (state.active) colors.bg else colors.bg,
            )
        }
    }
}

@Composable
private fun BarItem(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    mono: Boolean = false,
) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .height(42.dp)
            .background(if (hovered) colors.raised else Color.Transparent, RoundedCornerShape(999.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = if (active) colors.accent else colors.muted)
        }
        Text(
            label,
            style = if (mono) HearthType.monoSmall.copy(fontSize = 12.sp) else HearthType.label,
            color = if (active) colors.text else colors.textDim,
            maxLines = 1,
        )
    }
}

@Composable
private fun BarIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = HearthTheme.colors
    Box(
        Modifier
            .size(34.dp)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(15.dp), tint = colors.muted)
    }
}

@Composable
private fun BarToggle(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .height(34.dp)
            .background(
                when {
                    on -> colors.accentSoft
                    hovered -> colors.raised
                    else -> Color.Transparent
                },
                RoundedCornerShape(999.dp),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(if (on) colors.accent else colors.faint, CircleShape),
        )
        Spacer(Modifier.width(7.dp))
        Text(label, style = HearthType.small.copy(fontSize = 12.5.sp), color = if (on) colors.text else colors.muted)
    }
}

@Composable
private fun BarDivider() {
    Box(Modifier.width(1.dp).height(24.dp).background(HearthTheme.colors.borderStrong))
}

package com.sal7one.hearth.desktop.ui.screens.captions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * The strip above the transcript: is it running, for how long, in which pair, and
 * the one control that changes that. The clock only exists while a session does.
 */
@Composable
internal fun StageStatusBar(
    state: CaptionsState,
    clock: String,
    onFinishMicrophone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Waveform(color = if (state.active) colors.accent else colors.faint, active = state.active)
        Text(state.statusLabel, style = HearthType.small, color = colors.text)
        if (state.active) {
            Text(clock, style = HearthType.monoSmall, color = colors.faint)
        }
        Spacer(Modifier.weight(1f))
        Chip(
            text = "${state.sourceCodeLabel} → ${state.targetCodeLabel}",
            tone = ChipTone.Outline,
        )
        if (state.microphone && state.active) {
            Spacer(Modifier.width(2.dp))
            HearthButton(
                text = CaptionsCopy.FINISH,
                onClick = onFinishMicrophone,
                tone = ButtonTone.Ghost,
                size = ButtonSize.Small,
            )
        }
    }
}

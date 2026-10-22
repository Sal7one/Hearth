package com.sal7one.hearth.desktop.ui.screens.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.KeyValueRow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Status and expectations. "Needs a listen" is a real limitation of the current
 * build, so it is stated on the page rather than left to a release note.
 */
@Composable
internal fun VoicesSidebar(state: VoicesState, actions: VoicesActions, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HearthCard(padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(VoicesCopy.STATUS, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                Spacer(Modifier.weight(1f))
                Chip(
                    if (state.ready) VoicesCopy.READY else VoicesCopy.OFF,
                    tone = if (state.ready) ChipTone.Ok else ChipTone.Neutral,
                )
            }
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Engine", state.engineDetail)
            KeyValueRow("Style", state.selectedStyle)
            KeyValueRow("Output", state.outputLabel)
            KeyValueRow("Placement", state.placementLabel)
        }

        HearthCard(padding = 16.dp) {
            Text(VoicesCopy.NEEDS_LISTEN, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
            Spacer(Modifier.height(4.dp))
            Text(VoicesCopy.NEEDS_LISTEN_DETAIL, style = HearthType.small, color = colors.muted)
            Spacer(Modifier.height(12.dp))
            Banner("One resident engine is reused across lines, so switching style does not reload the model.", tone = BannerTone.Neutral)
        }

        HearthCard(padding = 16.dp) {
            Text(VoicesCopy.FILES, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
            Spacer(Modifier.height(4.dp))
            Text(VoicesCopy.FILES_DETAIL, style = HearthType.small, color = colors.muted)
            Spacer(Modifier.height(12.dp))
            HearthButton(
                VoicesCopy.OPEN_FILES,
                onClick = { actions.onNavigate(DesktopScreen.FILES) },
                tone = ButtonTone.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
}

package com.sal7one.hearth.desktop.ui.screens.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** State strip: running or not, and the URL the server actually returned. */
@Composable
internal fun IntegrationsStatusCard(state: IntegrationsState, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(
                if (state.running) IntegrationsCopy.RUNNING else IntegrationsCopy.STOPPED,
                tone = if (state.running) ChipTone.Ok else ChipTone.Neutral,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.running) IntegrationsCopy.SHARED else IntegrationsCopy.NOT_SHARED,
                    style = HearthType.rowTitle,
                    color = colors.text,
                )
                Text(
                    if (state.running) state.baseUrl else "No port is bound",
                    style = HearthType.monoSmall,
                    color = colors.muted,
                )
            }
        }
    }
}

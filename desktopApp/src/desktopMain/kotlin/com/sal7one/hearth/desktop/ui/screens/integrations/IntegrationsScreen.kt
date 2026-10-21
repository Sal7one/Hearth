package com.sal7one.hearth.desktop.ui.screens.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Integrations. The server is started explicitly; nothing binds a port until the
 * operator presses Start, and the URLs shown are the ones it actually returned.
 */
@Composable
internal fun IntegrationsScreen(
    state: IntegrationsState,
    actions: IntegrationsActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = IntegrationsCopy.EYEBROW,
            title = IntegrationsCopy.TITLE,
            lede = IntegrationsCopy.LEDE,
            actions = {
                Chip(IntegrationsCopy.LOOPBACK, tone = ChipTone.Ok)
                HearthButton(
                    text = when {
                        state.busy && state.running -> IntegrationsCopy.STOPPING
                        state.busy -> IntegrationsCopy.STARTING
                        state.running -> IntegrationsCopy.STOP
                        else -> IntegrationsCopy.START
                    },
                    onClick = { if (state.running) actions.onStop() else actions.onStart() },
                    tone = if (state.running) ButtonTone.Secondary else ButtonTone.Primary,
                    size = ButtonSize.Small,
                    enabled = !state.busy,
                )
            },
        )

        HearthCard(padding = 16.dp) {
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

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OverlayIntegrationCard(state, actions, Modifier.weight(1f))
            ApiIntegrationCard(state, actions, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Banner(
            if (state.running) IntegrationsCopy.STOP_NOTE else IntegrationsCopy.NOT_RUNNING_NOTE,
            tone = BannerTone.Neutral,
        )

        Spacer(Modifier.height(16.dp))
        HearthCard(padding = 0.dp) {
            Row(Modifier.fillMaxWidth()) {
                state.limits.forEachIndexed { index, (title, value) ->
                    if (index > 0) {
                        Spacer(Modifier.width(1.dp).height(96.dp).background(colors.border))
                    }
                    Column(Modifier.weight(1f).padding(18.dp)) {
                        Text(title.uppercase(), style = HearthType.micro, color = colors.muted)
                        Spacer(Modifier.height(8.dp))
                        Text(value, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                    }
                }
            }
        }
    }
}

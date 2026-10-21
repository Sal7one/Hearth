package com.sal7one.hearth.desktop.ui.screens.integrations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.shell.PageScaffold

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

        IntegrationsStatusCard(state)
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
        IntegrationLimits(state)
    }
}

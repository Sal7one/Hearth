package com.sal7one.hearth.desktop.ui.screens.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.KeyValueRow
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSubnav
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import com.sal7one.hearth.desktop.ui.theme.Space

/** Cloud endpoints and their keys. Local-only setups never need this page. */
@Composable
internal fun ConnectionsScreen(
    state: ConnectionsState,
    actions: ConnectionsActions,
    current: DesktopScreen = DesktopScreen.CONNECTIONS,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = ConnectionsCopy.EYEBROW,
            title = ConnectionsCopy.TITLE,
            lede = ConnectionsCopy.LEDE,
        )

        SettingsSubnav(current = current, onNavigate = actions.onNavigate)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(ConnectionsCopy.PROVIDERS, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                Text(ConnectionsCopy.PROVIDERS_DETAIL, style = HearthType.small, color = colors.muted)
                state.rows.forEach { row ->
                    ConnectionCard(row, actions)
                }
                if (state.rows.isEmpty()) {
                    Banner("No cloud engine is available in this build.", tone = BannerTone.Neutral)
                }
            }

            Column(Modifier.width(Space.sideColumn), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                HearthCard(padding = 16.dp) {
                    Text(ConnectionsCopy.WHERE_KEYS, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                    Spacer(Modifier.height(4.dp))
                    Text(ConnectionsCopy.WHERE_KEYS_DETAIL, style = HearthType.small, color = colors.muted)
                    Spacer(Modifier.height(10.dp))
                    KeyValueRow(ConnectionsCopy.STORED, "OS store")
                    KeyValueRow(ConnectionsCopy.SHOWN, "Only on reveal")
                    KeyValueRow(ConnectionsCopy.EXPORTED, "Never")
                }
                HearthCard(padding = 16.dp) {
                    Text(ConnectionsCopy.ENV_TITLE, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                    Spacer(Modifier.height(4.dp))
                    Text(ConnectionsCopy.ENV_DETAIL, style = HearthType.small, color = colors.muted)
                    Spacer(Modifier.height(10.dp))
                    Banner("env:OPENAI_API_KEY", tone = BannerTone.Neutral)
                    Spacer(Modifier.height(8.dp))
                    Text(ConnectionsCopy.ENV_NOTE, style = HearthType.small, color = colors.muted)
                }
                HearthCard(padding = 16.dp) {
                    Text(ConnectionsCopy.NEVER, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                    Spacer(Modifier.height(4.dp))
                    Text(ConnectionsCopy.NEVER_DETAIL, style = HearthType.small, color = colors.muted)
                }
            }
        }
    }
}

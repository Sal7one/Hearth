package com.sal7one.hearth.desktop.ui.screens.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.KeyValueRow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Where keys live, the env: form, and what is never sent. */
@Composable
internal fun ConnectionsSidebar(modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
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

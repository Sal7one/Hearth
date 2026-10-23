package com.sal7one.hearth.desktop.ui.screens.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** The limits the server actually enforces, stated as limits. */
@Composable
internal fun IntegrationLimits(state: IntegrationsState, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 0.dp) {
        Row(Modifier.fillMaxWidth()) {
            state.limits.forEachIndexed { index, (title, value) ->
                if (index > 0) {
                    Spacer(Modifier.width(1.dp).height(96.dp).background(colors.border))
                }
                Column(Modifier.weight(1f).padding(18.dp)) {
                    Text(title.uppercase(), style = HearthType.micro, color = colors.muted)
                    Spacer(Modifier.height(8.dp))
                    Text(value, style = HearthType.h3, color = colors.text)
                }
            }
        }
    }
}

package com.sal7one.hearth.desktop.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.Subnav
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Speech / Translation / Voices / Connections / Files / Appearance. */
@Composable
internal fun SettingsSubnav(
    current: DesktopScreen,
    onNavigate: (DesktopScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        DesktopScreen.SPEECH to "Speech",
        DesktopScreen.TRANSLATION_SETTINGS to "Translation",
        DesktopScreen.VOICES to "Voices",
        DesktopScreen.CONNECTIONS to "Connections",
        DesktopScreen.FILES to "Files",
        DesktopScreen.APPEARANCE to "Appearance",
    )
    Subnav(
        items = items.map { (screen, label) -> screen.id to label },
        currentId = current.id,
        onSelect = { id -> onNavigate(DesktopScreen.fromId(id)) },
        modifier = modifier,
    )
}

/**
 * One labelled setting. The label column is fixed so controls line up down the
 * page; hints sit under the label, never as tooltips.
 */
@Composable
internal fun FormRow(
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HearthTheme.colors
    Column(modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            val labelContent: @Composable () -> Unit = {
                Text(label, style = HearthType.label, color = colors.text)
                if (hint != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(hint, style = HearthType.rowSub, color = colors.muted)
                }
            }
            if (maxWidth < 640.dp) {
                Column {
                    labelContent()
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.fillMaxWidth(), content = content)
                }
            } else {
                Row {
                    Column(Modifier.width(208.dp)) { labelContent() }
                    Spacer(Modifier.width(24.dp))
                    Column(Modifier.weight(1f), content = content)
                }
            }
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }
    }
}

/** Small uppercase label used to open a block inside a card. */
@Composable
internal fun SettingsLabel(text: String) {
    Text(text.uppercase(), style = HearthType.micro, color = HearthTheme.colors.muted)
}

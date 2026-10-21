package com.sal7one.hearth.desktop.ui.screens.speech

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSubnav
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.Space
import androidx.compose.material3.Text
import com.sal7one.hearth.desktop.ui.theme.HearthType
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import androidx.compose.ui.unit.sp

/**
 * Speech settings. Engine choice, model, language, input and timing in one column;
 * compute and threads kept below the fold. Everything writes to the saved config.
 */
@Composable
internal fun SpeechScreen(
    state: SpeechState,
    actions: SpeechActions,
    current: DesktopScreen = DesktopScreen.SPEECH,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    LaunchedEffect(Unit) { actions.onRefreshDevices() }

    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = SpeechCopy.EYEBROW,
            title = SpeechCopy.TITLE,
            lede = SpeechCopy.LEDE,
            actions = {
                HearthButton(
                    SpeechCopy.TEST_FILE,
                    onClick = { actions.onNavigate(DesktopScreen.CAPTIONS) },
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                )
                HearthButton(
                    SpeechCopy.TEST_MIC,
                    onClick = actions.onTestMicrophone,
                    tone = ButtonTone.Primary,
                    size = ButtonSize.Small,
                    icon = HearthIcons.Microphone,
                )
            },
        )

        SettingsSubnav(current = current, onNavigate = actions.onNavigate)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HearthCard(padding = 20.dp) {
                    Text(SpeechCopy.ENGINE, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                    Spacer(Modifier.height(3.dp))
                    Text(SpeechCopy.ENGINE_DETAIL, style = HearthType.small, color = colors.muted)
                    Spacer(Modifier.height(14.dp))
                    SpeechEngineSection(state, actions.onSelectEngine)
                }
                HearthCard(padding = 20.dp) {
                    SpeechFormSection(state, actions)
                }
            }
            Column(Modifier.width(Space.sideColumn)) {
                SpeechSidebar(state)
            }
        }

        Spacer(Modifier.height(16.dp))
        SpeechAdvancedCard(state, actions)
    }
}

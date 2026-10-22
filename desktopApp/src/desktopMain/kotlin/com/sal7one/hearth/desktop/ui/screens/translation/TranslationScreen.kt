package com.sal7one.hearth.desktop.ui.screens.translation

import androidx.compose.foundation.layout.Arrangement
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
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSubnav
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.Space

/**
 * Translation settings. The glossary from the mockup is deliberately absent: the
 * desktop runtime has no glossary input, and an editor that changes nothing would
 * be worse than no editor.
 */
@Composable
internal fun TranslationScreen(
    state: TranslationState,
    actions: TranslationActions,
    current: DesktopScreen = DesktopScreen.TRANSLATION_SETTINGS,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = TranslationCopy.EYEBROW,
            title = TranslationCopy.TITLE,
            lede = TranslationCopy.LEDE,
            actions = {
                HearthButton(
                    TranslationCopy.TRY,
                    onClick = { actions.onNavigate(DesktopScreen.TRANSLATE) },
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                )
            },
        )

        SettingsSubnav(current = current, onNavigate = actions.onNavigate)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HearthCard(padding = 20.dp) {
                    Text(TranslationCopy.ENGINE, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                    Spacer(Modifier.height(3.dp))
                    Text(TranslationCopy.ENGINE_DETAIL, style = HearthType.small, color = colors.muted)
                    Spacer(Modifier.height(14.dp))
                    TranslationEngineSection(state, actions)
                }
                HearthCard(padding = 20.dp) { TranslationFormSection(state, actions) }
                if (state.supportsPromptMode) Text(
                    TranslationCopy.PROMPT_NOTE,
                    style = HearthType.small,
                    color = colors.muted,
                )
            }
            Column(Modifier.width(Space.sideColumn)) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HearthCard(padding = 16.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(TranslationCopy.STATUS, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                            Spacer(Modifier.weight(1f))
                            Chip(
                                if (state.ready && state.cloudSelected) "Connection configured" else if (state.ready) TranslationCopy.READY else TranslationCopy.NOT_CONFIGURED,
                                tone = if (state.ready) ChipTone.Ok else ChipTone.Warn,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        KeyValueRow("Model", state.modelDetail)
                        KeyValueRow("Placement", state.placementLabel)
                        if (state.supportsPromptMode) KeyValueRow("Prompt", state.promptMode.label)
                        if (state.localSelected) KeyValueRow("Threads", state.threads.toString())
                    }
                    HearthCard(padding = 16.dp) {
                        Text(TranslationCopy.ORDER, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                        Spacer(Modifier.height(4.dp))
                        Text(TranslationCopy.ORDER_DETAIL, style = HearthType.small, color = colors.muted)
                        Spacer(Modifier.height(12.dp))
                        Banner(TranslationCopy.AUTO_WARNING, tone = BannerTone.Warn)
                    }
                    HearthCard(padding = 16.dp) {
                        Text(TranslationCopy.BUNDLE, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                        Spacer(Modifier.height(4.dp))
                        Text(TranslationCopy.BUNDLE_DETAIL, style = HearthType.small, color = colors.muted)
                        Spacer(Modifier.height(12.dp))
                        HearthButton(
                            TranslationCopy.OPEN_PRESETS,
                            onClick = { actions.onNavigate(DesktopScreen.PRESETS) },
                            tone = ButtonTone.Secondary,
                            size = ButtonSize.Small,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (state.localSelected) TranslationAdvancedCard(state, actions)
    }
}

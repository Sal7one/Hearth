package com.sal7one.hearth.desktop.ui.screens.voice

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
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.HearthSlider
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.components.Segmented
import com.sal7one.hearth.desktop.ui.components.SwitchRow
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceCard
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption
import com.sal7one.hearth.desktop.ui.screens.settings.FormRow
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSubnav
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import com.sal7one.hearth.desktop.ui.theme.Space

/**
 * Voice settings: engine, Supertonic style, and when speech happens. Speed and
 * detail map to `speed` and `steps`; the read-aloud switch is the presence of the
 * stage, not a separate mute.
 */
@Composable
internal fun VoicesScreen(
    state: VoicesState,
    actions: VoicesActions,
    current: DesktopScreen = DesktopScreen.VOICES,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = VoicesCopy.EYEBROW,
            title = VoicesCopy.TITLE,
            lede = VoicesCopy.LEDE,
            actions = {
                HearthButton(
                    VoicesCopy.STOP_ALL,
                    onClick = actions.onStopAudio,
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                )
            },
        )

        SettingsSubnav(current = current, onNavigate = actions.onNavigate)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HearthCard(padding = 20.dp) {
                    Text(VoicesCopy.ENGINE, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                    Spacer(Modifier.height(3.dp))
                    Text(VoicesCopy.ENGINE_DETAIL, style = HearthType.small, color = colors.muted)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.engines.forEach { engine ->
                            ChoiceCard(
                                option = engine,
                                selected = engine.id == state.selectedEngineId,
                                onClick = { actions.onSelectEngine(engine.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        ChoiceCard(
                            option = ChoiceOption("", "—", "Off", "Captions only", "Nothing is spoken.", true),
                            selected = state.voiceOff,
                            onClick = { actions.onSelectEngine(null) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                HearthCard(padding = 20.dp) {
                    Text(VoicesCopy.STYLE, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                    Spacer(Modifier.height(3.dp))
                    Text(VoicesCopy.STYLE_DETAIL, style = HearthType.small, color = colors.muted)
                    Spacer(Modifier.height(14.dp))
                    VoiceStyleGrid(
                        styles = state.styles,
                        selected = state.selectedStyle,
                        onSelect = actions.onSelectStyle,
                    )
                }

                HearthCard(padding = 20.dp) {
                    Text(VoicesCopy.WHEN, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                    Spacer(Modifier.height(3.dp))
                    Text(VoicesCopy.WHEN_DETAIL, style = HearthType.small, color = colors.muted)
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        VoicesCopy.READ_ALOUD,
                        VoicesCopy.READ_ALOUD_HINT,
                        state.readAloud,
                        actions.onToggleReadAloud,
                    )
                    Spacer(Modifier.height(16.dp))
                    FormRow(label = VoicesCopy.SPEAK, hint = VoicesCopy.SPEAK_HINT) {
                        Segmented(
                            options = listOf("Original", "Translation"),
                            selected = if (state.speakTranslation) 1 else 0,
                            onSelect = { index -> actions.onSpeakTranslation(index == 1) },
                            modifier = Modifier.width(280.dp),
                        )
                    }
                    FormRow(label = VoicesCopy.SPEED, hint = VoicesCopy.SPEED_HINT) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HearthSlider(
                                value = ((state.speed.coerceIn(0.5f, 2f) - 0.5f) / 1.5f),
                                onValueChange = { fraction -> actions.onSpeed(0.5f + fraction * 1.5f) },
                                modifier = Modifier.width(240.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                String.format(java.util.Locale.ENGLISH, "%.2f×", state.speed),
                                style = HearthType.monoSmall,
                                color = colors.text,
                            )
                        }
                    }
                    FormRow(label = VoicesCopy.STEPS, hint = VoicesCopy.STEPS_HINT, showDivider = false) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HearthSlider(
                                value = (state.steps - 1) / 15f,
                                onValueChange = { fraction -> actions.onSteps((1 + fraction * 15).toInt().coerceIn(1, 16)) },
                                modifier = Modifier.width(240.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("${state.steps}", style = HearthType.monoSmall, color = colors.text)
                        }
                    }
                }
            }

            Column(Modifier.width(Space.sideColumn)) {
                VoicesSidebar(state, actions)
            }
        }
    }
}

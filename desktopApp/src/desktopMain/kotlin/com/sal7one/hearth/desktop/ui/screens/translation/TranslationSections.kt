package com.sal7one.hearth.desktop.ui.screens.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.HearthSlider
import com.sal7one.hearth.desktop.ui.components.Segmented
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceCard
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption
import com.sal7one.hearth.desktop.ui.screens.settings.FormRow
import com.sal7one.hearth.desktop.ui.screens.settings.SelectOption
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSelect
import com.sal7one.hearth.desktop.ui.screens.speech.Compute
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import androidx.compose.ui.unit.sp

/** Engine choice, model, default pair and prompt protocol. */
@Composable
internal fun TranslationFormSection(
    state: TranslationState,
    actions: TranslationActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    Column(modifier.fillMaxWidth()) {
        FormRow(
            label = TranslationCopy.MODEL,
            hint = TranslationCopy.MODEL_HINT,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsSelect(
                    value = state.selectedModelId,
                    options = state.models,
                    onSelect = actions.onSelectModel,
                    placeholder = TranslationCopy.NOT_CONFIGURED,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                HearthButton(
                    TranslationCopy.LIBRARY,
                    onClick = { actions.onNavigate(DesktopScreen.MODELS) },
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                )
            }
            if (state.selectedModelId != null) {
                Chip(
                    if (state.verified) "Verified" else "Not installed",
                    tone = if (state.verified) ChipTone.Ok else ChipTone.Warn,
                )
            }
        }

        FormRow(
            label = TranslationCopy.PAIR,
            hint = TranslationCopy.PAIR_HINT,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsSelect(
                    value = state.sourceLanguage,
                    options = state.languageOptions,
                    onSelect = actions.onSourceLanguage,
                    modifier = Modifier.width(190.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("→", style = HearthType.body, color = colors.muted)
                Spacer(Modifier.width(8.dp))
                SettingsSelect(
                    value = state.targetLanguage,
                    options = state.languageOptions,
                    onSelect = actions.onTargetLanguage,
                    modifier = Modifier.width(190.dp),
                )
            }
        }

        FormRow(
            label = TranslationCopy.PROMPT,
            hint = TranslationCopy.PROMPT_HINT,
            showDivider = false,
        ) {
            SettingsSelect(
                value = state.promptMode.key,
                options = PromptMode.entries.map { SelectOption(it.key, it.label, it.detail) },
                onSelect = { key -> actions.onPromptMode(PromptMode.fromKey(key)) },
                modifier = Modifier.width(280.dp),
            )
            Text(
                state.promptMode.detail,
                style = HearthType.small,
                color = colors.muted,
            )
        }
    }
}

/** Compute and threads, shared shape with the speech page. */
@Composable
internal fun TranslationAdvancedCard(
    state: TranslationState,
    actions: TranslationActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Text(TranslationCopy.ADVANCED, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
            Spacer(Modifier.height(3.dp))
            Text(TranslationCopy.ADVANCED_DETAIL, style = HearthType.small, color = colors.muted)
            FormRow(label = TranslationCopy.COMPUTE, hint = TranslationCopy.COMPUTE_HINT) {
                Segmented(
                    options = state.computeOptions.map { it.label },
                    selected = state.computeOptions.indexOf(state.compute).coerceAtLeast(0),
                    onSelect = { index -> actions.onCompute(state.computeOptions[index]) },
                    modifier = Modifier.width(260.dp),
                )
            }
            FormRow(label = TranslationCopy.THREADS, hint = TranslationCopy.THREADS_HINT, showDivider = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HearthSlider(
                        value = (state.threads - 1) / 15f,
                        onValueChange = { fraction -> actions.onThreads((1 + fraction * 15).toInt().coerceIn(1, 16)) },
                        modifier = Modifier.width(240.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("${state.threads}", style = HearthType.monoSmall, color = colors.text)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Local, cloud, or off — the third card is the honest way to disable the stage. */
@Composable
internal fun TranslationEngineSection(
    state: TranslationState,
    actions: TranslationActions,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.engines.forEach { engine ->
            ChoiceCard(
                option = engine,
                selected = engine.id == state.selectedEngineId,
                onClick = { actions.onSelectEngine(engine.id) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        ChoiceCard(
            option = ChoiceOption(
                id = "",
                badge = "—",
                name = "Off",
                detail = "Captions only",
                note = "Recognition still publishes the original.",
                available = true,
            ),
            selected = state.translationOff,
            onClick = { actions.onSelectEngine(null) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}


package com.sal7one.hearth.desktop.ui.screens.translation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.sal7one.hearth.desktop.ui.screens.settings.FormRow
import com.sal7one.hearth.desktop.ui.screens.settings.SelectOption
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSelect
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Engine choice, model, default pair and prompt protocol. */
@Composable
internal fun TranslationFormSection(
    state: TranslationState,
    actions: TranslationActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    Column(modifier.fillMaxWidth()) {
        FormRow(label = TranslationCopy.MODEL, hint = TranslationCopy.MODEL_HINT) {
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

        FormRow(label = TranslationCopy.PAIR, hint = TranslationCopy.PAIR_HINT) {
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

        FormRow(label = TranslationCopy.PROMPT, hint = TranslationCopy.PROMPT_HINT, showDivider = false) {
            SettingsSelect(
                value = state.promptMode.key,
                options = PromptMode.entries.map { SelectOption(it.key, it.label, it.detail) },
                onSelect = { key -> actions.onPromptMode(PromptMode.fromKey(key)) },
                modifier = Modifier.width(280.dp),
            )
            Text(state.promptMode.detail, style = HearthType.small, color = colors.muted)
        }
    }
}

package com.sal7one.hearth.desktop.ui.screens.speech

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
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.Segmented
import com.sal7one.hearth.desktop.ui.screens.settings.FormRow
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSelect
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * The everyday settings: which model, which language, which input, and how eagerly
 * a line is committed. All four write through to the saved configuration.
 */
@Composable
internal fun SpeechFormSection(
    state: SpeechState,
    actions: SpeechActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    Column(modifier.fillMaxWidth()) {
        if (state.localSelected) FormRow(
            label = SpeechCopy.MODEL,
            hint = SpeechCopy.MODEL_HINT,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsSelect(
                    value = state.selectedModelId,
                    options = state.models.map { model ->
                        com.sal7one.hearth.desktop.ui.screens.settings.SelectOption(
                            id = model.id,
                            label = model.label,
                            detail = if (model.installed) "Installed" else "Not installed",
                        )
                    },
                    onSelect = actions.onSelectModel,
                    placeholder = SpeechCopy.NOT_CONFIGURED,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                HearthButton(
                    SpeechCopy.LIBRARY,
                    onClick = { actions.onNavigate(com.sal7one.hearth.desktop.ui.DesktopScreen.MODELS) },
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                )
            }
            if (state.selectedModelId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(
                        if (state.modelVerified) "Verified" else "Not installed",
                        tone = if (state.modelVerified) ChipTone.Ok else ChipTone.Warn,
                    )
                    Text(
                        state.windowLabel,
                        style = HearthType.small,
                        color = colors.muted,
                    )
                }
            }
        }

        if (state.cloudSelected) FormRow(label = "Connection", hint = "Transcription uses the saved provider endpoint and model.") {
            Text(state.modelDetail, style = HearthType.small, color = colors.text)
            HearthButton("Configure connection", { actions.onNavigate(com.sal7one.hearth.desktop.ui.DesktopScreen.CONNECTIONS) },
                tone = ButtonTone.Secondary, size = ButtonSize.Small)
        }

        FormRow(
            label = SpeechCopy.LANGUAGE,
            hint = SpeechCopy.LANGUAGE_HINT,
        ) {
            SettingsSelect(
                value = state.language,
                options = state.languageOptions,
                onSelect = actions.onLanguage,
            )
            state.languageIssue?.let { Text(it, style = HearthType.small, color = colors.err) }
            Text(
                "Local translation needs an explicit language. Whisper passes its detected language on; cloud recognition does not.",
                style = HearthType.small,
                color = colors.muted,
            )
        }

        FormRow(
            label = SpeechCopy.INPUT,
            hint = SpeechCopy.INPUT_HINT,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SettingsSelect(
                    value = state.selectedDevice ?: "",
                    options = state.devices,
                    onSelect = { id -> actions.onDevice(id.takeIf { it.isNotBlank() }) },
                    placeholder = "System default",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                HearthButton(SpeechCopy.REFRESH, actions.onRefreshDevices, tone = ButtonTone.Secondary, size = ButtonSize.Small)
            }
            Text(SpeechCopy.INPUT_NOTE, style = HearthType.small, color = colors.muted)
        }

        if (state.localSelected) FormRow(
            label = SpeechCopy.RESPONSIVENESS,
            hint = SpeechCopy.RESPONSIVENESS_HINT,
            showDivider = false,
        ) {
            Segmented(
                options = Responsiveness.entries.map { it.label },
                selected = state.responsiveness.ordinal,
                onSelect = { index -> actions.onResponsiveness(Responsiveness.entries[index]) },
                modifier = Modifier.width(320.dp),
            )
            Text(
                state.responsiveness.detail,
                style = HearthType.small,
                color = colors.muted,
            )
        }
    }
}

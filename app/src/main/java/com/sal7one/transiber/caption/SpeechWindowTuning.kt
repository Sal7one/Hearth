package com.sal7one.transiber.caption

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.speech.SpeechProfile
import kotlinx.coroutines.launch

/**
 * Per-model caption delivery tuning: preset basics in a dropdown, custom
 * windows allowed, persisted per speech profile with the engine default
 * marked and restorable. Shown only for utterance-windowed engines.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun SpeechWindowTuningSection(profile: SpeechProfile, onApplied: () -> Unit) {
    val uiText = rememberUiText()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stored by remember(profile.id) { mutableStateOf<SpeechTuning?>(null) }
    var ready by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(profile.id) {
        SpeechTuningStore.tuning(context, profile.id).collect {
            stored = it
            ready = true
        }
    }
    if (!ready) return
    val current = stored ?: SpeechTuning.STANDARD
    var editingCustom by remember(profile.id) { mutableStateOf(current.preset == null) }
    var windowMs by remember(profile.id, current) { mutableStateOf(current.maxUtteranceMs) }
    var silenceMs by remember(profile.id, current) { mutableStateOf(current.silenceMs) }

    fun persist(tuning: SpeechTuning?) {
        scope.launch {
            if (tuning == null || tuning.isStandard) SpeechTuningStore.clear(context, profile.id)
            else SpeechTuningStore.save(context, profile.id, tuning)
            stored = tuning?.takeUnless { it.isStandard }
            onApplied()
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(uiText(UiR.string.ui_caption_delivery_for_1_s_47c31, profile.label), style = MaterialTheme.typography.titleSmall)
        Text(uiText(UiR.string.ui_caption_delivery_note_5a1e0), style = MaterialTheme.typography.bodySmall)

        val presetLabels = listOf(
            SpeechTuning.STANDARD to UiR.string.ui_tuning_standard_2c94d,
            SpeechTuning.RESPONSIVE to UiR.string.ui_tuning_responsive_7b36e,
            SpeechTuning.EAGER to UiR.string.ui_tuning_eager_9f58c,
        )
        var menuOpen by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = menuOpen, onExpandedChange = { menuOpen = it }) {
            OutlinedTextField(
                readOnly = true,
                value = if (!editingCustom && current.preset != null) uiText(presetLabels.first { it.first.matches(current) }.second)
                else uiText(UiR.string.ui_tuning_custom_4b71a),
                onValueChange = {},
                label = { Text(uiText(UiR.string.ui_tuning_preset_label_6d94e)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                shape = MaterialTheme.shapes.medium,
            )
            ExposedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                presetLabels.forEach { (preset, label) ->
                    DropdownMenuItem(
                        text = { Text(uiText(label)) },
                        trailingIcon = if (preset.isStandard) {
                            { Text(uiText(UiR.string.ui_tuning_default_badge_c6158), style = MaterialTheme.typography.labelSmall) }
                        } else null,
                        onClick = {
                            menuOpen = false; editingCustom = false
                            windowMs = preset.maxUtteranceMs; silenceMs = preset.silenceMs
                            persist(preset)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(uiText(UiR.string.ui_tuning_custom_4b71a)) },
                    onClick = { menuOpen = false; editingCustom = true },
                )
            }
        }

        if (editingCustom || current.preset == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = windowMs == SpeechTuning.RESPONSIVE.maxUtteranceMs && silenceMs == SpeechTuning.RESPONSIVE.silenceMs,
                    onClick = { windowMs = SpeechTuning.RESPONSIVE.maxUtteranceMs; silenceMs = SpeechTuning.RESPONSIVE.silenceMs },
                    label = { Text(uiText(UiR.string.ui_tuning_responsive_7b36e)) },
                )
                FilterChip(
                    selected = windowMs == SpeechTuning.EAGER.maxUtteranceMs && silenceMs == SpeechTuning.EAGER.silenceMs,
                    onClick = { windowMs = SpeechTuning.EAGER.maxUtteranceMs; silenceMs = SpeechTuning.EAGER.silenceMs },
                    label = { Text(uiText(UiR.string.ui_tuning_eager_9f58c)) },
                )
            }
            Slider(
                value = windowMs.toFloat(),
                onValueChange = { windowMs = (it.toInt() / 20) * 20 },
                onValueChangeFinished = { if (SpeechTuning.isValid(windowMs, silenceMs)) persist(SpeechTuning(windowMs, silenceMs)) },
                valueRange = 1000f..15000f,
                steps = (15000 - 1000) / 20 - 1,
            )
            Text(uiText(UiR.string.ui_tuning_window_slider_8e02f, String.format(java.util.Locale.ROOT, "%.1f", windowMs / 1000f)),
                style = MaterialTheme.typography.labelMedium)
            Slider(
                value = silenceMs.toFloat(),
                onValueChange = { silenceMs = (it.toInt() / 20) * 20 },
                onValueChangeFinished = { if (SpeechTuning.isValid(windowMs, silenceMs)) persist(SpeechTuning(windowMs, silenceMs)) },
                valueRange = 200f..2000f,
                steps = (2000 - 200) / 20 - 1,
            )
            Text(uiText(UiR.string.ui_tuning_silence_slider_31d5b, String.format(java.util.Locale.ROOT, "%.1f", silenceMs / 1000f)),
                style = MaterialTheme.typography.labelMedium)
            if (!SpeechTuning.isValid(windowMs, silenceMs)) {
                Text(uiText(UiR.string.ui_tuning_invalid_bounds_9d27a), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
        }

        if (stored != null) {
            TextButton(onClick = { editingCustom = false; persist(null) }) {
                Text(uiText(UiR.string.ui_tuning_reset_a903c))
            }
        }
    }
}

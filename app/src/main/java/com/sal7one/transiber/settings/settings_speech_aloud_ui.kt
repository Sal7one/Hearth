package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.i18n.label

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.CaptionSpeakerChoice
import com.sal7one.transiber.caption.CaptionOverlayConfig
import com.sal7one.transiber.caption.SpeakerGender
import com.sal7one.transiber.caption.DeviceMediaVolumeUi
import com.sal7one.transiber.caption.toVoiceGender
import java.io.File

/**
 * Read-aloud (TTS) layer for the speech overlay: speak recognized/translated
 * lines aloud straight after STT, with engine choice (device voice, on-device
 * neural, self-hosted or BYOK cloud), voice gender preference and loudness.
 * The same options exist in the overlay's settings sheet for live changes.
 */
@Composable
internal fun SettingsSpeechAloudUi(
    config: CaptionOverlayConfig,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
    showDeviceMediaVolume: Boolean = false,
    onOpenVoices: (() -> Unit)? = null,
) {
    val uiText = rememberUiText()
    val context = LocalContext.current
    val voiceReady = remember(context, config.speakerGender) {
        val choice = com.sal7one.transiber.voice.VoiceSettings.choice(context)
        com.sal7one.transiber.voice.VoiceModels(File(context.filesDir, "voice-models"))
            .ready(choice.voice, config.speakerGender.toVoiceGender())
    }

    Text(uiText(UiR.string.ui_read_captions_aloud_title_e5b31), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(uiText(UiR.string.ui_speak_captions_47294), style = MaterialTheme.typography.bodyLarge)
            Text(uiText(UiR.string.ui_speak_captions_setting_note_4c1a9), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = config.speakCaptions, onCheckedChange = { checked -> update { it.copy(speakCaptions = checked) } })
    }
    // Preferences remain editable while off; preparing them must not start TTS.
    var volume by remember(config.speakerVolume) { mutableStateOf(config.speakerVolume.toFloat()) }
    Text(uiText(UiR.string.ui_speaker_volume_label_b31f0, volume.toInt()), style = MaterialTheme.typography.labelLarge)
    Slider(
        value = volume,
        onValueChange = { volume = it },
        onValueChangeFinished = { update { it.copy(speakerVolume = volume.toInt().coerceIn(0, 100)) } },
        valueRange = 0f..100f,
        modifier = Modifier.fillMaxWidth(),
    )
    if (showDeviceMediaVolume) DeviceMediaVolumeUi()
    Text(uiText(UiR.string.ui_speaker_live_settings_note), style = MaterialTheme.typography.bodySmall)

    Text(uiText(UiR.string.ui_speaker_gender_label_3d84e), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SpeakerGender.entries.forEach { gender ->
            FilterChip(
                selected = config.speakerGender == gender,
                onClick = { update { it.copy(speakerGender = gender) } },
                label = { Text(uiText.label(gender)) },
            )
        }
    }
    Text(uiText(UiR.string.ui_speaker_engine_label_7f2c6), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CaptionSpeakerChoice.entries
            .filter { it != CaptionSpeakerChoice.CLOUD || ByokPolicy.FEATURE_BYOK }
            .forEach { choice ->
                FilterChip(
                    selected = config.speakerChoice == choice,
                    enabled = choice != CaptionSpeakerChoice.NATIVE || voiceReady,
                    onClick = { update { it.copy(speakerChoice = choice) } },
                    label = { Text(uiText.label(choice)) },
                )
            }
    }
    Text(if (config.speakerChoice == CaptionSpeakerChoice.NATIVE && !voiceReady)
        uiText(UiR.string.ui_install_supertonic_3_and_a_voice_in_read_aloud_settings_8a8c5)
        else uiText.explanation(config.speakerChoice), style = MaterialTheme.typography.bodySmall)
    Text(uiText(UiR.string.ui_speaker_gender_note_9a25c), style = MaterialTheme.typography.bodySmall)
    onOpenVoices?.let { open -> TextButton(onClick = open) { Text(uiText(UiR.string.ui_read_aloud_voices_downloads_7b18c)) } }
}

package com.sal7one.hearth.desktop.ui.screens.captions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import androidx.compose.material3.Text

/**
 * Exports and playback. Every action is disabled until there is something real to
 * act on — a copy button that copies an empty string is a lie about success.
 */
@Composable
internal fun OutputCard(
    state: CaptionsState,
    onCopyTranscript: () -> Unit,
    onCopyTranslation: () -> Unit,
    onSaveTranscript: () -> Unit,
    onPlaySpeech: () -> Unit,
    onStopSpeech: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 16.dp) {
        Text(CaptionsCopy.OUTPUT_TITLE, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
        Spacer(Modifier.height(4.dp))
        Text(CaptionsCopy.OUTPUT_DETAIL_LOCAL, style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(13.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HearthButton(
                CaptionsCopy.COPY_TRANSCRIPT,
                onCopyTranscript,
                Modifier.fillMaxWidth(),
                tone = ButtonTone.Secondary,
                size = ButtonSize.Small,
                enabled = state.hasFinalTranscript,
            )
            if (state.hasTranslation) {
                HearthButton(
                    CaptionsCopy.COPY_TRANSLATION,
                    onCopyTranslation,
                    Modifier.fillMaxWidth(),
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                )
            }
            HearthButton(
                CaptionsCopy.SAVE_TRANSCRIPT,
                onSaveTranscript,
                Modifier.fillMaxWidth(),
                tone = ButtonTone.Secondary,
                size = ButtonSize.Small,
                enabled = state.hasFinalTranscript,
            )
            val hasAudio = state.audioPath != null
            HearthButton(
                text = if (state.playingAudio) CaptionsCopy.STOP_SPEECH else CaptionsCopy.PLAY_SPEECH,
                onClick = if (state.playingAudio) onStopSpeech else onPlaySpeech,
                modifier = Modifier.fillMaxWidth(),
                tone = if (state.playingAudio) ButtonTone.Secondary else ButtonTone.Ghost,
                size = ButtonSize.Small,
                icon = if (state.playingAudio) HearthIcons.Stop else HearthIcons.Play,
                enabled = hasAudio,
            )
        }
    }
}

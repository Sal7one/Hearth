package com.sal7one.hearth.desktop.ui.screens.translate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** The last real text session's output. Empty means nothing has been translated. */
@Composable
internal fun TranslateResultCard(
    state: TranslateState,
    actions: TranslateActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.targetLabel,
                style = HearthType.h3.copy(fontSize = 15.sp),
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            Chip(
                if (state.busy) "Working" else "Last result",
                tone = if (state.busy) ChipTone.Info else ChipTone.Neutral,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 260.dp)) {
            if (state.hasResult) {
                Text(
                    state.translated.ifBlank { state.original },
                    style = HearthType.h2.copy(fontSize = 20.sp, lineHeight = 30.sp),
                    color = colors.text,
                )
            } else {
                Text(TranslateCopy.EMPTY, style = HearthType.small, color = colors.faint)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HearthButton(
                TranslateCopy.COPY,
                actions.onCopyResult,
                tone = ButtonTone.Secondary,
                size = ButtonSize.Small,
                enabled = state.translated.isNotBlank(),
            )
            HearthButton("Voice settings", actions.onOpenVoice, tone = ButtonTone.Ghost, size = ButtonSize.Small)
        }
    }
}

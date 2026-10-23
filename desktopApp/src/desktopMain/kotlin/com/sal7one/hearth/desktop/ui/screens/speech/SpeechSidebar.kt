package com.sal7one.hearth.desktop.ui.screens.speech

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.KeyValueRow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Status and expectations. No latency figure: it is not measured here. */
@Composable
internal fun SpeechSidebar(state: SpeechState, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HearthCard(padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(SpeechCopy.STATUS, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                Spacer(Modifier.weight(1f))
                Chip(
                    text = if(state.checking) "Checking saved model" else if (state.ready && state.cloudSelected) "Connection configured" else if (state.ready) SpeechCopy.READY else SpeechCopy.NOT_CONFIGURED,
                    tone = if(state.checking)ChipTone.Neutral else if (state.ready) ChipTone.Ok else ChipTone.Warn,
                )
            }
            Spacer(Modifier.height(10.dp))
            KeyValueRow("Model", state.modelDetail)
            KeyValueRow("Placement", state.placementLabel)
            if (state.localSelected) {
                KeyValueRow("Window", state.windowLabel)
                KeyValueRow("Threads", state.threads.toString())
            }
            state.unavailableReason?.let { reason ->
                Spacer(Modifier.height(10.dp))
                Banner(reason, tone = BannerTone.Err)
            }
        }

        if (state.localSelected) HearthCard(padding = 16.dp) {
            Text(SpeechCopy.HOW_IT_LISTENS, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
            Spacer(Modifier.height(4.dp))
            Text(SpeechCopy.HOW_IT_LISTENS_DETAIL, style = HearthType.small, color = colors.muted)
            Spacer(Modifier.height(12.dp))
            if (state.selectedEngineId == "whisper-local") Banner(SpeechCopy.ENGLISH_ONLY_WARNING, tone = BannerTone.Warn)
        }
    }
}

package com.sal7one.hearth.desktop.ui.screens.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * What the three stages are currently set to. This is the only place inside the
 * workspace that exposes engine names — reading captions should not require
 * understanding them, but changing one should be one click away.
 */
@Composable
internal fun StagesCard(
    state: CaptionsState,
    onManage: () -> Unit,
    onChangeStage: (StageSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(CaptionsCopy.STAGES_TITLE, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
            Spacer(Modifier.weight(1f))
            HearthButton(CaptionsCopy.MANAGE, onManage, tone = ButtonTone.Ghost, size = ButtonSize.Small)
        }
        Spacer(Modifier.height(4.dp))
        Text(CaptionsCopy.STAGES_DETAIL, style = HearthType.small, color = colors.muted)

        Spacer(Modifier.height(10.dp))
        StageRow("1", CaptionsCopy.RECOGNIZE, state.speechEngine ?: "Not configured",
            available = state.speechReady, action = CaptionsCopy.CHANGE) { onChangeStage(StageSlot.RECOGNIZE) }
        StageDivider()
        StageRow("2", CaptionsCopy.TRANSLATE, if (state.translationEnabled) state.translationEngine ?: "Configured" else "Off",
            available = state.translationEnabled, action = CaptionsCopy.CHANGE) { onChangeStage(StageSlot.TRANSLATE) }
        StageDivider()
        StageRow("3", CaptionsCopy.SPEAK, if (state.voiceEnabled) state.voiceEngine ?: "Configured" else "Off",
            available = state.voiceEnabled, action = if (state.voiceEnabled) CaptionsCopy.CHANGE else CaptionsCopy.TURN_ON) {
            onChangeStage(StageSlot.SPEAK)
        }
    }
}

internal enum class StageSlot { RECOGNIZE, TRANSLATE, SPEAK }

@Composable
private fun StageRow(
    index: String,
    name: String,
    engine: String,
    available: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    val colors = HearthTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(20.dp).background(colors.surface3, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(index, style = HearthType.monoSmall.copy(fontSize = 10.sp), color = colors.muted)
        }
        Spacer(Modifier.width(11.dp))
        Box(Modifier.size(6.dp).background(if (available) colors.ok else colors.faint, RoundedCornerShape(99.dp)))
        Spacer(Modifier.width(11.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(name, style = HearthType.rowTitle, color = colors.text)
            Text(
                engine,
                style = HearthType.rowSub,
                color = if (available) colors.muted else colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HearthButton(action, onAction, tone = ButtonTone.Ghost, size = ButtonSize.Small)
    }
}

@Composable
private fun StageDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HearthTheme.colors.border))
}

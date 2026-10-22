package com.sal7one.hearth.desktop.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.Eyebrow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Three facts the operator can act on before starting: what is installed, what is
 * ready, and where the audio goes. No marketing, no invented percentages.
 */
@Composable
internal fun HomeReadiness(state: HomeState, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    val shapes = HearthTheme.shapes
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shapes.card)
            .background(colors.surface)
            .border(1.dp, colors.border, shapes.card),
    ) {
        ReadinessCell(
            eyebrow = "On this Mac",
            value = "${state.installedModels} model" + if (state.installedModels == 1) "" else "s",
            chip = if (state.verifiedModels > 0) "Verified" to ChipTone.Ok else null,
            detail = if (state.installedModels == 0) {
                "Install local models or connect a provider."
            } else {
                "${state.verifiedModels} checked against their pinned hash."
            },
            modifier = Modifier.weight(1f),
            showDivider = true,
        )
        ReadinessCell(
            eyebrow = "Pipeline",
            value = if (state.setupIssue == null && state.languages.issue == null) "Configured" else "Needs attention",
            chip = if (state.setupIssue == null && state.languages.issue == null) "Ready" to ChipTone.Ok else "Check settings" to ChipTone.Warn,
            detail = state.setupIssue ?: state.languages.issue ?: "Live captions use the stages selected above.",
            modifier = Modifier.weight(1f),
            showDivider = true,
        )
        ReadinessCell(
            eyebrow = "Privacy",
            value = if (state.onDevice) "On device" else "Cloud selected",
            chip = null,
            detail = if (state.onDevice) "Selected engines process audio and text locally." else "Selected cloud stages send input to your configured providers.",
            modifier = Modifier.weight(1f),
            showDivider = false,
        )
    }
}

@Composable
private fun ReadinessCell(
    eyebrow: String,
    value: String,
    chip: Pair<String, ChipTone>?,
    detail: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean,
) {
    val colors = HearthTheme.colors
    Row(modifier.fillMaxHeight()) {
        Column(Modifier.weight(1f).padding(20.dp)) {
            Eyebrow(eyebrow)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value, style = HearthType.h2.copy(fontSize = 19.sp), color = colors.text)
                chip?.let { (label, tone) -> Chip(label, tone = tone) }
            }
            Spacer(Modifier.height(6.dp))
            Text(detail, style = HearthType.small, color = colors.muted)
        }
        if (showDivider) {
            Box(Modifier.fillMaxHeight().width(1.dp).background(colors.border))
        }
    }
}


package com.sal7one.hearth.desktop.ui.screens.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import com.sal7one.hearth.desktop.ui.theme.Space

/**
 * The three companion screens are phone features. They are shown inside a device
 * frame, labelled, and never claim desktop behaviour they do not have.
 */
@Composable
internal fun CompanionScreen(
    eyebrow: String,
    title: String,
    lede: String,
    notes: List<Pair<String, String>>,
    callout: String,
    modifier: Modifier = Modifier,
    phone: @Composable ColumnScope.() -> Unit,
) {
    val colors = HearthTheme.colors
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = eyebrow,
            title = title,
            lede = lede,
            actions = { Chip(CompanionCopy.MOBILE_TODAY, tone = com.sal7one.hearth.desktop.ui.components.ChipTone.Neutral) },
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                notes.forEach { (heading, detail) ->
                    com.sal7one.hearth.desktop.ui.components.HearthCard(padding = 18.dp) {
                        Text(heading.uppercase(), style = HearthType.micro, color = colors.muted)
                        Spacer(Modifier.height(8.dp))
                        Text(detail, style = HearthType.lede, color = colors.textDim)
                    }
                }
                com.sal7one.hearth.desktop.ui.components.Banner(callout, tone = com.sal7one.hearth.desktop.ui.components.BannerTone.Neutral)
            }
            Column(Modifier.width(Space.sideColumn + 84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DeviceFrame(Modifier.fillMaxHeight(0.95f)) { phone() }
            }
        }
    }
}

/** 318 dp phone bezel. Its screen hosts real layout, not a screenshot. */
@Composable
internal fun DeviceFrame(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HearthTheme.colors
    Column(
        modifier
            .width(318.dp)
            .clip(RoundedCornerShape(44.dp))
            .background(Brush.linearGradient(listOf(colors.surface3, colors.surface, colors.raised)))
            .border(1.dp, colors.borderStrong, RoundedCornerShape(44.dp))
            .padding(9.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(35.dp))
                .background(colors.bg)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
    }
}

/** Status bar shared by the three device screens. */
@Composable
internal fun PhoneStatus(time: String) {
    val colors = HearthTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(time, style = HearthType.monoSmall.copy(fontSize = 10.sp), color = colors.muted)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(width = 76.dp, height = 18.dp).background(androidx.compose.ui.graphics.Color.Black, RoundedCornerShape(99.dp)))
        Spacer(Modifier.weight(1f))
        Text("●", style = HearthType.small.copy(fontSize = 8.sp), color = colors.faint)
    }
}

internal object CompanionCopy {
    const val MOBILE_TODAY = "Mobile today"
}

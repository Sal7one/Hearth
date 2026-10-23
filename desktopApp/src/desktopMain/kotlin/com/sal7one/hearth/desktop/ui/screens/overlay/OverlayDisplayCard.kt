package com.sal7one.hearth.desktop.ui.screens.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.SwitchRow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** What the window says, and what it does not. Native click-through can always be reversed from this page. */
@Composable
internal fun OverlayDisplayCard(
    settings: OverlaySettings,
    onChange: (OverlaySettings) -> Unit,
    modifier: Modifier = Modifier,
    nativeAvailable: Boolean = false,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 16.dp) {
        Text(OverlayCopy.WHAT_IT_SHOWS, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
        Spacer(Modifier.height(4.dp))
        Text(OverlayCopy.WHAT_IT_SHOWS_DETAIL, style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(13.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwitchRow(
                OverlayCopy.ORIGINAL,
                OverlayCopy.ORIGINAL_DETAIL,
                settings.showOriginal,
                { onChange(settings.copy(showOriginal = it)) },
            )
            SwitchRow(
                OverlayCopy.PARTIALS,
                OverlayCopy.PARTIALS_DETAIL,
                settings.showPartials,
                { onChange(settings.copy(showPartials = it)) },
            )
            SwitchRow(
                OverlayCopy.CLICK_THROUGH,
                OverlayCopy.CLICK_THROUGH_DETAIL,
                settings.clickThrough,
                { onChange(settings.copy(clickThrough = it)) },
                enabled = nativeAvailable,
            )
        }
    }
}

/** Three styles, each with a miniature of the real geometry. */
@Composable
internal fun OverlayStyleCard(
    settings: OverlaySettings,
    onChange: (OverlaySettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 16.dp) {
        Text(OverlayCopy.STYLE, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
        Spacer(Modifier.height(4.dp))
        Text(OverlayCopy.STYLE_DETAIL, style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayStyle.entries.forEach { style ->
                OverlayStyleOption(
                    style = style,
                    selected = settings.style == style,
                    onClick = { onChange(settings.copy(style = style)) },
                )
            }
        }
    }
}

@Composable
private fun OverlayStyleOption(
    style: OverlayStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(
                1.dp,
                when {
                    selected -> colors.accentLine
                    hovered -> colors.borderStrong
                    else -> colors.border
                },
                RoundedCornerShape(12.dp),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StylePreview(style)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(style.label, style = HearthType.rowTitle, color = colors.text)
            Text(style.detail, style = HearthType.small.copy(fontSize = 11.sp), color = colors.muted)
        }
    }
}

@Composable
private fun StylePreview(style: OverlayStyle) {
    Box(
        Modifier
            .width(78.dp)
            .height(46.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color(0xFF253440), Color(0xFF0C1013)),
                ),
            ),
    ) {
        when (style) {
            OverlayStyle.GLASS -> Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 7.dp)
                    .fillMaxWidth()
                    .height(13.dp)
                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(3.dp)),
            )
            OverlayStyle.BAR -> Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(20.dp)
                    .background(Color.Black, RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp)),
            )
            OverlayStyle.CLEAR -> Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 9.dp)
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(2.dp)),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(7.dp)
                .width(28.dp)
                .height(4.dp)
                .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
        )
    }
}


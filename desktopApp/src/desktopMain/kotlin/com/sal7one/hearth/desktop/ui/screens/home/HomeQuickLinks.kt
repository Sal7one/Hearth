package com.sal7one.hearth.desktop.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Four setup surfaces that are not part of the feature map but are reached often
 * enough to deserve one click from Home. They are a strip, not a second menu.
 */
@Composable
internal fun HomeQuickLinks(
    onOpen: (DesktopScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        HomeCopy.quickLinks.forEachIndexed { index, link ->
            if (index > 0) {
                Box(Modifier.fillMaxHeight().width(1.dp).background(colors.border))
            }
            QuickLinkCell(link, onOpen, Modifier.weight(1f))
        }
    }
}

@Composable
private fun QuickLinkCell(
    link: HomeFeature,
    onOpen: (DesktopScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val destination = link.destination
    Row(
        modifier
            .fillMaxHeight()
            .background(if (hovered) colors.surface2 else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null) {
                if (destination != null) onOpen(destination)
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).background(colors.surface3, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(link.quickIcon(), null, Modifier.size(15.dp), tint = colors.textDim)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(link.title, style = HearthType.rowTitle, color = colors.text, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                link.description,
                style = HearthType.small.copy(fontSize = 11.5.sp),
                color = colors.muted,
                maxLines = 1,
            )
        }
        Icon(
            HearthIcons.ArrowRight,
            null,
            Modifier.size(14.dp),
            tint = if (hovered) colors.text else colors.faint,
        )
    }
}

private fun HomeFeature.quickIcon(): ImageVector = when (id) {
    "overlay" -> HearthIcons.Overlay
    "presets" -> HearthIcons.Sparkle
    "downloads" -> HearthIcons.Download
    "connections" -> HearthIcons.Swap
    else -> HearthIcons.Overlay
}

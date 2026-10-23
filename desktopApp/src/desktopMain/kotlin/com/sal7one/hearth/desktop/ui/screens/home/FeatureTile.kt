package com.sal7one.hearth.desktop.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.AvailabilityDot
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import com.sal7one.hearth.desktop.ui.theme.LocalReduceMotion

/**
 * An artwork-led entry in the Home map. Hovering lifts the border and reveals the
 * single verb; tiles that need a download or another platform say so in the foot
 * instead of failing after the click.
 */
@Composable
internal fun FeatureTile(
    feature: HomeFeature,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    val shapes = HearthTheme.shapes
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reveal by animateFloatAsState(
        targetValue = if (hovered && onClick != null) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else 180),
        label = "tileReveal",
    )

    Column(
        modifier
            .clip(shapes.card)
            .background(colors.surface)
            .border(1.dp, if (hovered && onClick != null) colors.borderStrong else colors.border, shapes.card)
            .then(
                if (onClick != null) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            TileArtwork(feature.art, Modifier.fillMaxSize())
            if (feature.art == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = feature.placeholderIcon(),
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = colors.text.copy(alpha = 0.72f),
                    )
                }
            }
        }
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp)) {
            Text(feature.title, style = HearthType.h3.copy(fontSize = 15.5.sp), color = colors.text)
            Spacer(Modifier.height(7.dp))
            Text(
                feature.description,
                style = HearthType.small,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Open",
                    style = HearthType.small.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                    color = colors.text.copy(alpha = reveal),
                )
                if (reveal > 0f) {
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        HearthIcons.ArrowRight,
                        null,
                        Modifier.size(13.dp),
                        tint = colors.text.copy(alpha = reveal),
                    )
                }
                Spacer(Modifier.weight(1f))
                AvailabilityDot(
                    label = feature.available.label,
                    available = feature.available == Availability.DESKTOP,
                )
            }
        }
    }
}

private fun HomeFeature.placeholderIcon(): ImageVector = when (id) {
    "integration" -> HearthIcons.Display
    else -> HearthIcons.Overlay
}

package com.sal7one.hearth.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Thin progress track. [tone] selects the fill colour family. */
@Composable
internal fun Meter(
    fraction: Float,
    modifier: Modifier = Modifier,
    tone: MeterTone = MeterTone.Accent,
    height: Int = 6,
) {
    val colors = HearthTheme.colors
    val fill = when (tone) {
        MeterTone.Accent -> colors.accent
        MeterTone.Warm -> colors.warm
        MeterTone.Ok -> colors.ok
        MeterTone.Muted -> colors.muted
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(colors.surface3, RoundedCornerShape(999.dp)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(fill, RoundedCornerShape(999.dp)),
        )
    }
}

internal enum class MeterTone { Accent, Warm, Ok, Muted }

internal enum class BannerTone { Neutral, Ok, Warn, Err }

/** Inline message. Wording states the problem; colour only reinforces it. */
@Composable
internal fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Neutral,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = HearthTheme.colors
    val (bg, border, fg) = when (tone) {
        BannerTone.Neutral -> Triple(colors.surface2, colors.border, colors.textDim)
        BannerTone.Ok -> Triple(colors.okSoft, colors.ok.copy(alpha = 0.3f), colors.textDim)
        BannerTone.Warn -> Triple(colors.warnSoft, colors.warn.copy(alpha = 0.34f), colors.textDim)
        BannerTone.Err -> Triple(colors.errSoft, colors.err.copy(alpha = 0.34f), colors.textDim)
    }
    val glyph = icon ?: when (tone) {
        BannerTone.Ok -> com.sal7one.hearth.desktop.ui.icons.HearthIcons.Check
        BannerTone.Warn, BannerTone.Err -> com.sal7one.hearth.desktop.ui.icons.HearthIcons.Warning
        BannerTone.Neutral -> com.sal7one.hearth.desktop.ui.icons.HearthIcons.Info
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(14.dp))
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            glyph,
            contentDescription = null,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
            tint = when (tone) {
                BannerTone.Ok -> colors.ok
                BannerTone.Warn -> colors.warn
                BannerTone.Err -> colors.err
                BannerTone.Neutral -> colors.muted
            },
        )
        Text(text, style = HearthType.small, color = fg, modifier = Modifier.weight(1f))
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

/** Small dot + label used by availability and status rows. */
@Composable
internal fun DotLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = HearthType.small.copy(fontSize = 11.5.sp), color = color)
    }
}

/** A row that looks clickable and reports hover, used by list items. */
@Composable
internal fun HoverRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = HearthTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(if (hovered && onClick != null) colors.surface2 else Color.Transparent)
            .then(
                if (onClick != null) {
                    Modifier.androidxClickable(interaction, onClick)
                } else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun Modifier.androidxClickable(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = clickable(interactionSource = interaction, indication = null, onClick = onClick)

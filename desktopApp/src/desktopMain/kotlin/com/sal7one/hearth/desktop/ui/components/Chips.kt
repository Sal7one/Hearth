package com.sal7one.hearth.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

internal enum class ChipTone { Neutral, Ok, Warn, Err, Info, Accent, Warm, Outline }

/** Small status label. Colour never carries meaning alone: the label always says it. */
@Composable
internal fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.Neutral,
    icon: ImageVector? = null,
) {
    val colors = HearthTheme.colors
    val (fg, bg, border) = when (tone) {
        ChipTone.Neutral -> Triple(colors.muted, colors.surface2, colors.border)
        ChipTone.Ok -> Triple(colors.ok, colors.okSoft, Color.Transparent)
        ChipTone.Warn -> Triple(colors.warn, colors.warnSoft, Color.Transparent)
        ChipTone.Err -> Triple(colors.err, colors.errSoft, Color.Transparent)
        ChipTone.Info -> Triple(colors.info, colors.infoSoft, Color.Transparent)
        ChipTone.Accent -> Triple(colors.accent, colors.accentSoft, Color.Transparent)
        ChipTone.Warm -> Triple(colors.warm, colors.warmSoft, Color.Transparent)
        ChipTone.Outline -> Triple(colors.muted, Color.Transparent, colors.border)
    }
    Row(
        modifier = modifier
            .height(26.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(11.dp), tint = fg)
        }
        Text(text, style = HearthType.small.copy(fontSize = 11.5.sp), color = fg, maxLines = 1)
    }
}


/** Availability marker used on feature tiles and preset bundles. */
@Composable
internal fun AvailabilityDot(label: String, available: Boolean, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    val color = if (available) colors.ok else colors.muted
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Text(
            "  $label",
            style = HearthType.small.copy(fontSize = 11.sp),
            color = color,
            maxLines = 1,
        )
    }
}

/** Live status pill: dot + word, pulsing only while something is actually running. */
@Composable
internal fun StatusPill(text: String, live: Boolean, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    Row(
        modifier = modifier
            .height(32.dp)
            .background(if (live) colors.accentSoft else colors.surface2, RoundedCornerShape(999.dp))
            .border(1.dp, if (live) colors.accentLine else colors.border, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(if (live) colors.accent else if (text.isNotEmpty()) colors.ok else colors.muted, CircleShape),
        )
        Text(
            "  $text",
            style = HearthType.small.copy(fontSize = 12.5.sp),
            color = if (live) colors.text else colors.textDim,
            maxLines = 1,
        )
    }
}

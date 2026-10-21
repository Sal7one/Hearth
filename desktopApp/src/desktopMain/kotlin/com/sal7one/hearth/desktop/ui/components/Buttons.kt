package com.sal7one.hearth.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

internal enum class ButtonTone { Primary, Secondary, Ghost, Accent, Danger }
internal enum class ButtonSize(val height: Dp, val horizontal: Dp, val fontSize: Float, val radius: Dp) {
    Small(32.dp, 12.dp, 12.5f, 9.dp),
    Medium(40.dp, 17.dp, 13.5f, 12.dp),
    Large(48.dp, 22.dp, 14.5f, 14.dp),
}

/**
 * The one button in the app. Primary is inverted (light on dark, dark on light) and
 * there is exactly one per region; accent is reserved for the live action.
 */
@Composable
internal fun HearthButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Secondary,
    size: ButtonSize = ButtonSize.Medium,
    icon: ImageVector? = null,
    iconAfter: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background: Color
    val foreground: Color
    val borderColor: Color
    when (tone) {
        ButtonTone.Primary -> {
            background = if (hovered && enabled) Color.White else colors.text
            foreground = colors.bg
            borderColor = Color.Transparent
        }
        ButtonTone.Secondary -> {
            background = if (hovered && enabled) colors.surface3 else colors.surface2
            foreground = colors.text
            borderColor = if (hovered && enabled) colors.muted else colors.borderStrong
        }
        ButtonTone.Ghost -> {
            background = if (hovered && enabled) colors.surface2 else Color.Transparent
            foreground = if (hovered && enabled) colors.text else colors.textDim
            borderColor = Color.Transparent
        }
        ButtonTone.Accent -> {
            background = colors.accent
            foreground = colors.onAccent
            borderColor = Color.Transparent
        }
        ButtonTone.Danger -> {
            background = if (hovered && enabled) colors.errSoft else Color.Transparent
            foreground = colors.err
            borderColor = colors.err.copy(alpha = 0.4f)
        }
    }
    val opacity = if (enabled) 1f else 0.38f

    Row(
        modifier = modifier
            .height(size.height)
            .then(
                if (tone == ButtonTone.Ghost) Modifier
                else Modifier.border(1.dp, borderColor, RoundedCornerShape(size.radius)),
            )
            .background(background.copy(alpha = background.alpha * opacity), RoundedCornerShape(size.radius))
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = size.horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val iconColor = foreground.copy(alpha = opacity)
        if (icon != null && !iconAfter) {
            Icon(icon, null, Modifier.size(size.height * 0.4f), tint = iconColor)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = HearthType.label.copy(fontSize = size.fontSize.sp),
            color = foreground.copy(alpha = opacity),
            maxLines = 1,
        )
        if (icon != null && iconAfter) {
            Spacer(Modifier.width(8.dp))
            Icon(icon, null, Modifier.size(size.height * 0.4f), tint = iconColor)
        }
    }
}


/** Square, icon-only control used in toolbars and row actions. */
@Composable
internal fun HearthIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .size(34.dp)
            .background(if (hovered && enabled) colors.surface2 else Color.Transparent, RoundedCornerShape(10.dp))
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(17.dp),
            tint = (tint ?: if (hovered) colors.text else colors.muted).copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

/** Convenience for the frequent "ghost with a trailing chevron" row action. */
@Composable
internal fun QuietAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    HearthButton(text, onClick, modifier, tone = ButtonTone.Ghost, size = ButtonSize.Small, icon = HearthIcons.ChevronDown, iconAfter = true)

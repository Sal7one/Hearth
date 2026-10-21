package com.sal7one.hearth.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType
import com.sal7one.hearth.desktop.ui.theme.LocalReduceMotion

/** Three-or-fewer choice control. Selecting is immediate; there is no apply step. */
@Composable
internal fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = HearthTheme.colors
    Row(
        modifier
            .height(36.dp)
            .background(colors.surface2, RoundedCornerShape(11.dp))
            .border(1.dp, colors.border, RoundedCornerShape(11.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val active = index == selected
            val interaction = remember(option) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                Modifier
                    .weight(1f)
                    .height(30.dp)
                    .background(
                        when {
                            active -> colors.raised
                            hovered && enabled -> colors.surface3
                            else -> Color.Transparent
                        },
                        RoundedCornerShape(8.dp),
                    )
                    .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                    .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    style = HearthType.small.copy(fontSize = 12.5.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
                    color = when {
                        !enabled -> colors.faint
                        active -> colors.text
                        else -> colors.muted
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** 40×23 switch with a cyan thumb when on. */
@Composable
internal fun HearthSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = HearthTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val offset by animateDpAsState(
        targetValue = if (checked) 17.dp else 2.dp,
        animationSpec = tween(if (reduceMotion) 0 else 180),
        label = "switchThumb",
    )
    val track by animateColorAsState(
        targetValue = if (checked) colors.accentSoft else colors.raised,
        animationSpec = tween(if (reduceMotion) 0 else 180),
        label = "switchTrack",
    )
    Box(
        modifier
            .size(width = 40.dp, height = 23.dp)
            .background(track, RoundedCornerShape(999.dp))
            .border(1.dp, if (checked) colors.accentLine else colors.borderStrong, RoundedCornerShape(999.dp))
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = offset, y = 2.dp)
                .size(17.dp)
                .background(if (checked) colors.accent else colors.muted, CircleShape),
        )
    }
}

/** Label + detail on the left, switch on the right. */
@Composable
internal fun SwitchRow(
    title: String,
    detail: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = HearthTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = HearthType.rowTitle, color = colors.text)
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(detail, style = HearthType.rowSub, color = colors.muted)
            }
        }
        Spacer(Modifier.width(12.dp))
        HearthSwitch(checked, onCheckedChange, enabled = enabled)
    }
}

/** Horizontal section navigation under a page header. */
@Composable
internal fun Subnav(
    items: List<Pair<String, String>>,
    currentId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEach { (id, label) ->
                val active = id == currentId
                val interaction = remember(id) { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Column {
                    Text(
                        label,
                        style = HearthType.label,
                        color = when {
                            active -> colors.text
                            hovered -> colors.text
                            else -> colors.muted
                        },
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(interactionSource = interaction, indication = null) { onSelect(id) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                    )
                    Box(
                        Modifier
                            .padding(horizontal = 10.dp)
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (active) colors.accent else Color.Transparent, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Spacer(Modifier.height(26.dp))
    }
}

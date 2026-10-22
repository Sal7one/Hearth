package com.sal7one.hearth.desktop.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

internal data class SelectOption(val id: String, val label: String, val detail: String? = null)

/**
 * Dropdown that states the current choice and nothing else. The menu lists real
 * options only — an unavailable engine appears with its reason, not as a dead row.
 */
@Composable
internal fun SettingsSelect(
    value: String?,
    options: List<SelectOption>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Not set",
    enabled: Boolean = true,
) {
    val colors = HearthTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selected = options.firstOrNull { it.id == value }

    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .background(colors.surface2, RoundedCornerShape(10.dp))
                .border(1.dp, if (hovered && enabled) colors.borderStrong else colors.border, RoundedCornerShape(10.dp))
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selected?.label ?: placeholder,
                style = HearthType.small.copy(fontSize = 13.sp),
                color = if (selected == null) colors.faint else colors.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Icon(HearthIcons.ChevronDown, null, Modifier.size(14.dp), tint = colors.muted)
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, style = HearthType.small.copy(fontSize = 13.sp))
                            option.detail?.let {
                                Text(it, style = HearthType.rowSub, color = colors.muted)
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option.id)
                    },
                )
            }
        }
    }
}

internal data class ChoiceOption(
    val id: String,
    val badge: String,
    val name: String,
    val detail: String,
    val note: String,
    val available: Boolean,
    val unavailableReason: String? = null,
)

/**
 * Engine choice card. The selected card gets the accent ring; an unavailable one
 * stays visible and disabled so its reason can be read.
 */
@Composable
internal fun ChoiceCard(
    option: ChoiceOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier
            .fillMaxWidth()
            .background(if (selected) colors.accentSoft else colors.surface, RoundedCornerShape(14.dp))
            .border(
                1.dp,
                when {
                    selected -> colors.accentLine
                    hovered && option.available -> colors.borderStrong
                    else -> colors.border
                },
                RoundedCornerShape(14.dp),
            )
            .pointerHoverIcon(if (option.available) PointerIcon.Hand else PointerIcon.Default)
            .selectable(selected = selected, interactionSource = interaction, indication = null,
                enabled = option.available, role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).background(
                    if (selected) colors.accent else colors.surface3,
                    RoundedCornerShape(8.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.badge,
                    style = HearthType.monoSmall.copy(fontSize = 11.sp),
                    color = if (selected) colors.onAccent else colors.muted,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(option.name, style = HearthType.rowTitle, color = colors.text)
                Text(option.detail, style = HearthType.small.copy(fontSize = 11.5.sp), color = colors.muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (option.available) option.note else option.unavailableReason ?: "Unavailable on this computer",
            style = HearthType.small,
            color = if (option.available) colors.muted else colors.err,
        )
    }
}

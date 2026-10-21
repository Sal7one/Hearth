package com.sal7one.hearth.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * One-line input. Flat like the rest of the system: a border that brightens on
 * focus, no Material box or floating label.
 */
@Composable
internal fun HearthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = HearthTheme.colors
    val shapes = HearthTheme.shapes
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = HearthType.label.copy(color = colors.text, fontSize = 13.5.sp),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(colors.surface, shapes.controlShape)
            .border(
                1.dp,
                when {
                    focused -> colors.accentLine
                    else -> colors.border
                },
                shapes.controlShape,
            ),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (leading != null) {
                    Icon(leading, null, Modifier.size(16.dp), tint = if (focused) colors.textDim else colors.muted)
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = HearthType.small, color = colors.faint, maxLines = 1)
                    }
                    inner()
                }
            }
        },
    )
}

/** Selectable filter pill. Selection is the only state; it is never disabled. */
@Composable
internal fun FilterPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = HearthTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier
            .height(30.dp)
            .background(if (selected) colors.raised else androidx.compose.ui.graphics.Color.Transparent, shape)
            .border(1.dp, if (selected) androidx.compose.ui.graphics.Color.Transparent else colors.border, shape)
            .androidxClickable(interaction, onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = HearthType.small.copy(fontSize = 12.5.sp),
            color = if (selected) colors.text else colors.muted,
            maxLines = 1,
        )
    }
}

private fun Modifier.androidxClickable(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = clickable(interactionSource = interaction, indication = null, onClick = onClick)

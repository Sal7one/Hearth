package com.sal7one.hearth.desktop.ui.screens.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.HearthSlider
import com.sal7one.hearth.desktop.ui.components.Segmented
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Position, width, text size and backdrop. Every control is live: the scene behind
 * this card changes as you drag, so there is no Apply button and no guessing.
 */
@Composable
internal fun OverlayPlacementCard(
    settings: OverlaySettings,
    widthPercentLabel: String,
    backdropPercentLabel: String,
    onChange: (OverlaySettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(OverlayCopy.PLACEMENT, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
            Spacer(Modifier.weight(1f))
            Chip("Live", tone = ChipTone.Accent)
        }
        Spacer(Modifier.height(12.dp))

        val displays = androidx.compose.runtime.remember { runCatching { overlayDisplays() }.getOrDefault(emptyList()) }
        com.sal7one.hearth.desktop.ui.screens.settings.SettingsSelect(
            value = settings.pinnedDisplay,
            options = listOf(com.sal7one.hearth.desktop.ui.screens.settings.SelectOption("auto", "Current display")) +
                displays.map { com.sal7one.hearth.desktop.ui.screens.settings.SelectOption(it.id, it.name) },
            onSelect = { onChange(settings.copy(pinnedDisplay = it, bounds = null)) },
        )
        Spacer(Modifier.height(12.dp))
        FieldLabel(OverlayCopy.POSITION)
        Spacer(Modifier.height(6.dp))
        Segmented(
            options = OverlayPosition.entries.map { it.label },
            selected = settings.position.ordinal,
            onSelect = { onChange(settings.copy(position = OverlayPosition.entries[it], bounds = null)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        FieldLabel(OverlayCopy.WIDTH)
        HearthSlider(
            value = (settings.widthFraction - 0.25f) / 0.75f,
            onValueChange = { onChange(overlayWithWidth(settings, 0.25f + it * 0.75f, displays)) },
            modifier = Modifier.padding(top = 2.dp),
        )
        ScaleLegend(left = "25%", center = widthPercentLabel, right = "100%")

        Spacer(Modifier.height(12.dp))
        FieldLabel(OverlayCopy.TEXT)
        Spacer(Modifier.height(6.dp))
        Segmented(
            options = OverlayTextSize.entries.map { it.label },
            selected = settings.textSize.ordinal,
            onSelect = { onChange(settings.copy(textSize = OverlayTextSize.entries[it], textScale = 1f)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text("${(settings.textSize.baseSp * settings.textScale).toInt()} pt · fine-tune with ${OverlayCommand.BIGGER.shortcutLabel()} / ↓",
            style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(16.dp))
        FieldLabel(OverlayCopy.BACKDROP)
        HearthSlider(
            value = settings.backdropFraction,
            onValueChange = { onChange(settings.copy(backdropFraction = it.coerceIn(0f, 1f))) },
            modifier = Modifier.padding(top = 2.dp),
        )
        ScaleLegend(left = "Clear", center = backdropPercentLabel, right = "Solid")
        Spacer(Modifier.height(12.dp))
        FieldLabel("Maximum lines")
        HearthSlider(value = (settings.maxLines - 1) / 5f,
            onValueChange = { onChange(settings.copy(maxLines = (1 + it * 5).toInt().coerceIn(1, 6))) })
        ScaleLegend("1", settings.maxLines.toString(), "6")
        Spacer(Modifier.height(12.dp))
        Segmented(options = OverlayTextAlign.entries.map { it.label }, selected = settings.textAlign.ordinal,
            onSelect = { onChange(settings.copy(textAlign = OverlayTextAlign.entries[it])) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = HearthType.label, color = HearthTheme.colors.text)
}

@Composable
private fun ScaleLegend(left: String, center: String, right: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, style = HearthType.monoSmall, color = HearthTheme.colors.muted)
        Text(center, style = HearthType.monoSmall, color = HearthTheme.colors.text)
        Text(right, style = HearthType.monoSmall, color = HearthTheme.colors.muted)
    }
}

/** Three small facts under the scene: where it goes, the shortcut, and its state. */
@Composable
internal fun OverlayFacts(
    state: OverlayState,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        FactCard(
            eyebrow = OverlayCopy.WHERE,
            value = if (state.settings.bounds != null) "Saved position" else "${state.settings.position.label} of display",
            detail = OverlayCopy.WHERE_DETAIL,
            modifier = Modifier.weight(1f),
        )
        FactCard(
            eyebrow = OverlayCopy.SHORTCUT,
            value = OverlayCopy.SHORTCUT_VALUE,
            detail = state.shortcutStatus,
            modifier = Modifier.weight(1f),
            mono = true,
        )
        FactCard(
            eyebrow = OverlayCopy.CLICK_STATE,
            value = if (state.settings.visible) "On screen" else "Hidden",
            detail = if (state.settings.visible) "The floating window is open." else "Press Show on screen to open it.",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FactCard(
    eyebrow: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 16.dp) {
        Text(eyebrow.uppercase(), style = HearthType.micro, color = colors.muted)
        Spacer(Modifier.height(8.dp))
        Text(
            value,
            style = if (mono) HearthType.monoSmall.copy(fontSize = 13.sp) else HearthType.h3.copy(fontSize = 14.sp),
            color = colors.text,
        )
        Spacer(Modifier.height(4.dp))
        Text(detail, style = HearthType.small, color = colors.muted)
    }
}

@Composable
private fun RowScopeSpacer() = Spacer(Modifier.width(8.dp))

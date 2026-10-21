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

        FieldLabel(OverlayCopy.POSITION)
        Spacer(Modifier.height(6.dp))
        Segmented(
            options = OverlayPosition.entries.map { it.label },
            selected = settings.position.ordinal,
            onSelect = { onChange(settings.copy(position = OverlayPosition.entries[it])) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        FieldLabel(OverlayCopy.WIDTH)
        HearthSlider(
            value = settings.widthFraction,
            onValueChange = { onChange(settings.copy(widthFraction = it.coerceIn(0.4f, 1f))) },
            modifier = Modifier.padding(top = 2.dp),
        )
        ScaleLegend(left = "40%", center = widthPercentLabel, right = "100%")

        Spacer(Modifier.height(12.dp))
        FieldLabel(OverlayCopy.TEXT)
        Spacer(Modifier.height(6.dp))
        Segmented(
            options = OverlayTextSize.entries.map { it.label },
            selected = settings.textSize.ordinal,
            onSelect = { onChange(settings.copy(textSize = OverlayTextSize.entries[it])) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        FieldLabel(OverlayCopy.BACKDROP)
        HearthSlider(
            value = settings.backdropFraction,
            onValueChange = { onChange(settings.copy(backdropFraction = it.coerceIn(0f, 1f))) },
            modifier = Modifier.padding(top = 2.dp),
        )
        ScaleLegend(left = "Clear", center = backdropPercentLabel, right = "Solid")
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
            value = state.settings.pinnedDisplay,
            detail = OverlayCopy.WHERE_DETAIL,
            modifier = Modifier.weight(1f),
        )
        FactCard(
            eyebrow = OverlayCopy.SHORTCUT,
            value = OverlayCopy.SHORTCUT_VALUE,
            detail = OverlayCopy.SHORTCUT_DETAIL,
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

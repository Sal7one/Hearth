package com.sal7one.hearth.desktop.ui.screens.presets

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * One preset. It states what it sets, what it costs and whether it can run before
 * you press anything — a preset that needs a download says so and offers it.
 */
@Composable
internal fun PresetCard(
    preset: PresetView,
    onApply: () -> Unit,
    onSetUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    val shapes = HearthTheme.shapes
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    HearthCard(modifier, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                preset.title,
                style = HearthType.h3.copy(fontSize = 15.5.sp),
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            Chip(
                text = preset.statusLabel,
                tone = when {
                    preset.active -> ChipTone.Accent
                    preset.ready -> ChipTone.Ok
                    else -> ChipTone.Warn
                },
            )
        }
        Spacer(Modifier.height(7.dp))
        Text(preset.description, style = HearthType.small, color = colors.muted)

        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            preset.stages.forEach { stage -> PresetStageRow(stage) }
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (preset.ready) "${preset.sizeLabel} · ${PresetsCopy.ALL_INSTALLED}"
                else "${preset.sizeLabel} · ${preset.missing.joinToString(", ")}",
                style = HearthType.small,
                color = if (preset.ready) colors.muted else colors.warn,
                modifier = Modifier.weight(1f),
            )
            if (preset.active) {
                HearthButton(PresetsCopy.EDIT, onSetUp, tone = ButtonTone.Ghost, size = ButtonSize.Small)
            } else if (preset.ready) {
                HearthButton(PresetsCopy.USE, onApply, tone = ButtonTone.Primary, size = ButtonSize.Small)
            } else {
                HearthButton(PresetsCopy.SET_UP, onSetUp, tone = ButtonTone.Secondary, size = ButtonSize.Small)
            }
        }
    }
}

@Composable
private fun PresetStageRow(stage: PresetStage) {
    val colors = HearthTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(18.dp).background(colors.surface3, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stage.index.toString(),
                style = HearthType.monoSmall.copy(fontSize = 9.5.sp),
                color = colors.muted,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                stage.off -> "${stage.label} off"
                stage.unavailable -> "${stage.label} · not in this build"
                else -> stage.modelName.orEmpty()
            },
            style = HearthType.rowSub,
            color = if (stage.off) colors.faint else colors.textDim,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (stage.unavailable) {
            Text("missing", style = HearthType.monoSmall.copy(fontSize = 10.sp), color = colors.err)
        } else if (!stage.off) {
            Box(
                Modifier
                    .size(5.dp)
                    .background(if (stage.installed) colors.ok else colors.faint, RoundedCornerShape(99.dp)),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (stage.installed) "installed" else "missing",
                style = HearthType.monoSmall.copy(fontSize = 10.sp),
                color = if (stage.installed) colors.muted else colors.faint,
            )
        }
    }
}

/** The dashed "build your own" affordance from the design, wired to the library. */
@Composable
internal fun BuildPresetCard(onOpenLibrary: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    val shapes = HearthTheme.shapes
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(shapes.card)
            .background(colors.surface.copy(alpha = 0.4f))
            .border(1.dp, if (hovered) colors.borderStrong else colors.border, shapes.card)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpenLibrary)
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(34.dp).background(colors.surface3, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                com.sal7one.hearth.desktop.ui.icons.HearthIcons.Plus,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.muted,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("Build a preset", style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
        Spacer(Modifier.height(4.dp))
        Text(
            "Pick a speech model, a translator and a voice in the library.",
            style = HearthType.small,
            color = colors.muted,
        )
    }
}

package com.sal7one.hearth.desktop.ui.screens.presets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.screens.models.ModelMeasurements
import com.sal7one.hearth.desktop.ui.screens.models.formatBytes
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

@Composable
internal fun PresetCard(
    preset: PresetView,
    actions: PresetsActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(preset.title, style = HearthType.h3, color = colors.text, modifier = Modifier.weight(1f))
            Chip(
                text = preset.statusLabel,
                tone = when {
                    preset.ready && preset.active -> ChipTone.Accent
                    preset.ready -> ChipTone.Ok
                    preset.unavailableReason != null -> ChipTone.Warn
                    else -> ChipTone.Neutral
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(preset.languages, style = HearthType.small, color = colors.accent)
        Spacer(Modifier.height(8.dp))
        Text(preset.description, style = HearthType.small, color = colors.muted)

        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            preset.stages.forEach { stage ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${stage.label} · ${stage.modelName}", style = HearthType.small, color = colors.textDim,
                        modifier = Modifier.weight(1f))
                    Text(
                        if (stage.installed) "Verified" else stage.activity ?: "Missing",
                        style = HearthType.monoSmall,
                        color = if (stage.installed) colors.ok else colors.muted,
                    )
                }
            }
        }

        if (preset.measurements.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ModelMeasurements(preset.measurements, showModel = true)
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                preset.unavailableReason ?: if (preset.ready) "All files verified"
                else "${formatBytes(preset.totalBytes)} for the complete bundle",
                style = HearthType.small,
                color = if (preset.unavailableReason == null) colors.muted else colors.warn,
                modifier = Modifier.weight(1f),
            )
            PresetPrimaryAction(preset, actions)
        }
    }
}

@Composable
internal fun PresetPrimaryAction(preset: PresetView, actions: PresetsActions) {
    when {
        preset.unavailableReason != null || preset.ready && preset.active -> Unit
        preset.ready -> HearthButton("Use preset", { actions.onApply(preset.id) }, tone = ButtonTone.Primary, size = ButtonSize.Small)
        preset.downloadIds.isNotEmpty() -> HearthButton("Download ${formatBytes(preset.downloadBytes)}",
            { actions.onDownload(preset.id) }, tone = ButtonTone.Secondary, size = ButtonSize.Small)
        else -> HearthButton("View progress", actions.onOpenDownloads, tone = ButtonTone.Ghost, size = ButtonSize.Small)
    }
}

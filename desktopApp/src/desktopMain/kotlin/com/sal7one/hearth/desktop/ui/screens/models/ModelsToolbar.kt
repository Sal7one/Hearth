package com.sal7one.hearth.desktop.ui.screens.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.FilterPill
import com.sal7one.hearth.desktop.ui.components.HearthTextField
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSelect
import com.sal7one.hearth.desktop.ui.screens.settings.SelectOption
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * Search plus stage filters. The filter row is the same control the section uses
 * everywhere, so "Installed" means one thing across the app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelsToolbar(
    filter: ModelsFilter,
    onSearch: (String) -> Unit,
    onStage: (PipelineStage?) -> Unit,
    onToggleInstalled: () -> Unit,
    onHardware: (ModelHardwareFilter) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HearthTextField(
            value = filter.query,
            onValueChange = onSearch,
            placeholder = ModelsCopy.SEARCH,
            leading = HearthIcons.Search,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("All stages", filter.stage == null) { onStage(null) }
            FilterPill("Speech", filter.stage == PipelineStage.STT) { onStage(PipelineStage.STT) }
            FilterPill("Translation", filter.stage == PipelineStage.TRANSLATION) { onStage(PipelineStage.TRANSLATION) }
            FilterPill("Voice", filter.stage == PipelineStage.TTS) { onStage(PipelineStage.TTS) }
            FilterPill("Installed", filter.onlyInstalled) { onToggleInstalled() }
            SettingsSelect(
                value = filter.hardware.key,
                options = ModelHardwareFilter.entries.map { SelectOption(it.key, it.label) },
                onSelect = { onHardware(ModelHardwareFilter.fromKey(it)) },
                modifier = Modifier.width(220.dp),
            )
            if (filter != ModelsFilter()) HearthButton("Reset filters", onReset,
                tone = ButtonTone.Ghost, size = ButtonSize.Small)
        }
        Text(filter.hardware.detail, style = HearthType.small, color = colors.muted)
    }
}

/** Group header chip: how much of this stage is actually installed. */
@Composable
internal fun GroupCountChip(count: Int) {
    Chip(
        text = installedLabel(count),
        tone = if (count > 0) ChipTone.Ok else ChipTone.Neutral,
    )
}

/** Single row inside a stage group. */
@Composable
internal fun ModelRowView(
    row: ModelRow,
    onAction: () -> Unit,
    onImport: () -> Unit,
    importEnabled: Boolean,
) {
    val colors = HearthTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(colors.surface3, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when (row.stage) {
                    PipelineStage.STT -> HearthIcons.Microphone
                    PipelineStage.TRANSLATION -> HearthIcons.Swap
                    PipelineStage.TTS -> HearthIcons.Speaker
                },
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = colors.textDim,
            )
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, style = HearthType.rowTitle, color = colors.text, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    ModelStatusChip(row)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${row.sizeLabel} · ${row.metaLabel}",
                    style = HearthType.rowSub,
                    color = colors.muted,
                    maxLines = 1,
                )
                row.gpuLabel?.let { Text(it, style = HearthType.rowSub, color = colors.accent, maxLines = 2) }
                row.issue?.let {
                    Text(it, style = HearthType.rowSub, color = colors.err, maxLines = 2)
                }
                if (row.status == ModelStatus.BROKEN && row.activity == null) {
                    Text("Repair downloads missing or changed files.", style = HearthType.rowSub, color = colors.muted)
                }
            }
        }
        HearthButton(
            text = "Import existing…",
            onClick = onImport,
            enabled = importEnabled && row.activity == null,
            tone = ButtonTone.Ghost,
            size = ButtonSize.Small,
        )
        Spacer(Modifier.width(8.dp))
        HearthButton(
            text = row.actionLabel,
            onClick = onAction,
            enabled = row.actionEnabled,
            tone = if (row.inUse) ButtonTone.Ghost else ButtonTone.Secondary,
            size = ButtonSize.Small,
        )
    }
}

@Composable
private fun ModelStatusChip(row: ModelRow) {
    when {
        row.inUse -> Chip("In use", tone = ChipTone.Accent)
        row.status == ModelStatus.BROKEN -> Chip("Changed on disk", tone = ChipTone.Err)
        row.status == ModelStatus.INSTALLED -> Chip("Verified", tone = ChipTone.Ok)
        else -> Chip("Not installed", tone = ChipTone.Neutral)
    }
}

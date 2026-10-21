package com.sal7one.hearth.desktop.ui.screens.files

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.sal7one.hearth.desktop.ui.components.Meter
import com.sal7one.hearth.desktop.ui.components.MeterTone
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Verified model bytes against the catalog total. Counted, not estimated. */
@Composable
internal fun StorageCard(state: FilesState, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 20.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(FilesCopy.STORAGE, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
            Spacer(Modifier.weight(1f))
            Chip(state.modelsLabel, tone = ChipTone.Neutral)
        }
        Spacer(Modifier.height(3.dp))
        Text(FilesCopy.STORAGE_DETAIL, style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                FilesCopy.MODELS_STORAGE,
                style = HearthType.small,
                color = colors.textDim,
                modifier = Modifier.width(140.dp),
            )
            Meter(fraction = state.storageFraction, modifier = Modifier.weight(1f), tone = MeterTone.Accent)
            Spacer(Modifier.width(14.dp))
            Text(
                "${state.modelsLabel} of ${state.catalogLabel}",
                style = HearthType.monoSmall,
                color = colors.muted,
            )
        }
    }
}

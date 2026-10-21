package com.sal7one.hearth.desktop.ui.screens.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.Meter
import com.sal7one.hearth.desktop.ui.components.MeterTone
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Header strip: what is moving, what it costs, and how much disk is left. */
@Composable
internal fun DownloadSummary(state: DownloadsState) {
    val colors = HearthTheme.colors
    val fraction = if (state.totalCount > 0) state.installedCount.toFloat() / state.totalCount else 0f
    HearthCard(padding = 18.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f)) {
                Text(DownloadsCopy.IN_PROGRESS.uppercase(), style = HearthType.micro, color = colors.muted)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.activeCount > 0) "${state.activeCount} downloading" else "Nothing downloading",
                    style = HearthType.h3.copy(fontSize = 15.sp),
                    color = colors.text,
                )
                Spacer(Modifier.height(8.dp))
                Meter(fraction = fraction, tone = MeterTone.Accent)
                Spacer(Modifier.height(7.dp))
                Text(
                    "${state.installedCount} of ${state.totalCount} catalog models installed",
                    style = HearthType.small,
                    color = colors.muted,
                )
            }
            Column(Modifier.width(220.dp)) {
                Text("Disk", style = HearthType.micro, color = colors.muted)
                Spacer(Modifier.height(8.dp))
                Text(state.freeDiskLabel, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
                Spacer(Modifier.height(3.dp))
                Text("free · ${state.storageLabel} used by models", style = HearthType.small, color = colors.muted)
            }
        }
    }
}

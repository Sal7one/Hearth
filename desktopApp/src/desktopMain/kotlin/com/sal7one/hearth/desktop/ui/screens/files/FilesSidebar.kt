package com.sal7one.hearth.desktop.ui.screens.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Disk headroom and the one-launch environment overrides. */
@Composable
internal fun FilesSidebar(state: FilesState, modifier: Modifier = Modifier) {
    val colors = HearthTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HearthCard(padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(FilesCopy.DISK, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                Spacer(Modifier.weight(1f))
                Chip(FilesCopy.HEALTHY, tone = ChipTone.Ok)
            }
            Spacer(Modifier.height(10.dp))
            Text(state.freeLabel, style = HearthType.h2.copy(fontSize = 22.sp), color = colors.text)
            Spacer(Modifier.height(10.dp))
            Text(FilesCopy.DISK_DETAIL, style = HearthType.small, color = colors.muted)
        }
        HearthCard(padding = 16.dp) {
            Text(FilesCopy.ADVANCED, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
            Spacer(Modifier.height(4.dp))
            Text(FilesCopy.ADVANCED_DETAIL, style = HearthType.small, color = colors.muted)
            Spacer(Modifier.height(12.dp))
            Banner(FilesCopy.ENV, tone = BannerTone.Neutral)
        }
    }
}

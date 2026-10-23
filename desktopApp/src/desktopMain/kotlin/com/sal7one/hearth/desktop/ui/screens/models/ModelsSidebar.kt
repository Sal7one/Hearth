package com.sal7one.hearth.desktop.ui.screens.models

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
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.Banner
import com.sal7one.hearth.desktop.ui.components.BannerTone
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.Meter
import com.sal7one.hearth.desktop.ui.components.MeterTone
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * The side column: how much is installed, why verification exists, and where a
 * file you already have can be used. No marketing, no "recommended" flags.
 */
@Composable
internal fun ModelsSidebar(
    state: ModelsState,
    onOpenFolder: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HearthCard(padding = 16.dp) {
            Text(ModelsCopy.STORAGE.uppercase(), style = HearthType.micro, color = colors.muted)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(state.storageLabel, style = HearthType.h2.copy(fontSize = 22.sp), color = colors.text)
                Spacer(Modifier.height(0.dp))
                Text(
                    "  of ${state.catalogLabel} in the catalog",
                    style = HearthType.small,
                    color = colors.muted,
                )
            }
            Spacer(Modifier.height(14.dp))
            Meter(fraction = state.storageFraction, tone = MeterTone.Accent)
            Spacer(Modifier.height(10.dp))
            Text(ModelsCopy.STORAGE_DETAIL, style = HearthType.small, color = colors.muted)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HearthButton(ModelsCopy.OPEN_FOLDER, onOpenFolder, tone = ButtonTone.Ghost, size = ButtonSize.Small)
                HearthButton("Downloads", onOpenDownloads, tone = ButtonTone.Ghost, size = ButtonSize.Small)
            }
        }

        HearthCard(padding = 16.dp) {
            Text(ModelsCopy.VERIFY_TITLE, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
            Spacer(Modifier.height(4.dp))
            Text(ModelsCopy.VERIFY_DETAIL, style = HearthType.small, color = colors.muted)
            Spacer(Modifier.height(12.dp))
            Banner(ModelsCopy.VERIFY_BANNER, tone = BannerTone.Neutral)
        }

        HearthCard(padding = 16.dp) {
            Text(ModelsCopy.OWN_TITLE, style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
            Spacer(Modifier.height(4.dp))
            Text(ModelsCopy.OWN_DETAIL, style = HearthType.small, color = colors.muted)
        }

        if (state.issues.isNotEmpty()) {
            HearthCard(padding = 16.dp) {
                Text("Needs attention", style = HearthType.h3.copy(fontSize = 14.sp), color = colors.text)
                Spacer(Modifier.height(8.dp))
                state.issues.take(3).forEach { issue ->
                    Text(issue, style = HearthType.small, color = colors.err)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

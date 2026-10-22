package com.sal7one.hearth.desktop.ui.screens.downloads

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.Meter
import com.sal7one.hearth.desktop.ui.components.MeterTone
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** One transfer or catalog entry. The action depends on the real bucket. */
@Composable
internal fun DownloadRow(
    item: DownloadItem,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = HearthTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(colors.surface3, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (item.bucket) {
                    DownloadBucket.DOWNLOADING -> HearthIcons.Download
                    DownloadBucket.INSTALLED -> HearthIcons.Check
                    DownloadBucket.BROKEN -> HearthIcons.Warning
                    DownloadBucket.AVAILABLE -> HearthIcons.Download
                },
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = when (item.bucket) {
                    DownloadBucket.INSTALLED -> colors.ok
                    DownloadBucket.BROKEN -> colors.err
                    else -> colors.textDim
                },
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = HearthType.rowTitle, color = colors.text, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                com.sal7one.hearth.desktop.ui.components.Chip(item.stageLabel, tone = com.sal7one.hearth.desktop.ui.components.ChipTone.Neutral)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "${item.sizeLabel} · ${item.detail}",
                style = HearthType.rowSub,
                color = if (item.bucket == DownloadBucket.BROKEN) colors.err else colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.progress?.let { progress ->
                Spacer(Modifier.height(8.dp))
                Meter(fraction = progress, tone = MeterTone.Accent)
            }
        }
        Spacer(Modifier.width(12.dp))
        when (item.bucket) {
            DownloadBucket.DOWNLOADING -> HearthButton(if (item.cancellable) DownloadsCopy.CANCEL else "Cancelling…", onCancel, enabled = item.cancellable, tone = ButtonTone.Secondary, size = ButtonSize.Small)
            DownloadBucket.AVAILABLE -> HearthButton(DownloadsCopy.DOWNLOAD, onDownload, tone = ButtonTone.Secondary, size = ButtonSize.Small)
            DownloadBucket.BROKEN -> HearthButton("Retry", onDownload, tone = ButtonTone.Secondary, size = ButtonSize.Small)
            DownloadBucket.INSTALLED -> Text("Verified", style = HearthType.monoSmall, color = colors.muted)
        }
    }
}

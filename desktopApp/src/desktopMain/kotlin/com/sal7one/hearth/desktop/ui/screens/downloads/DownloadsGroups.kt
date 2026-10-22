package com.sal7one.hearth.desktop.ui.screens.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.Chip
import com.sal7one.hearth.desktop.ui.components.ChipTone
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.screens.models.ModelsDivider
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/**
 * A bucket of transfers. Empty buckets say so rather than disappearing, so the page
 * does not jump around while a download completes.
 */
@Composable
internal fun DownloadGroupCard(
    title: String,
    items: List<DownloadItem>,
    emptyText: String,
    tone: ChipTone = ChipTone.Neutral,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    showWhenEmpty: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    if (items.isEmpty() && !showWhenEmpty) return
    HearthCard(modifier, padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) {
                Chip(if (items.size == 1) "1 item" else "${items.size} items", tone = tone)
            }
        }
        ModelsDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            if (items.isEmpty()) {
                Text(
                    emptyText,
                    style = HearthType.small,
                    color = colors.muted,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                items.forEachIndexed { index, item ->
                    if (index > 0) ModelsDivider()
                    DownloadRow(
                        item = item,
                        onDownload = { onDownload(item.id) },
                        onCancel = { onCancel(item.id) },
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

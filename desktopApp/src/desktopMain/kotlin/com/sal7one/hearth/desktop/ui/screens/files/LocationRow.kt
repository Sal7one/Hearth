package com.sal7one.hearth.desktop.ui.screens.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.icons.HearthIcons
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** One owned folder: name, what it holds, the real path, and how to open it. */
@Composable
internal fun LocationRow(
    entry: LocationEntry,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(colors.surface3, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(entry.id), null, Modifier.size(16.dp), tint = colors.textDim)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title, style = HearthType.rowTitle, color = colors.text)
                Text(entry.detail, style = HearthType.small.copy(fontSize = 11.sp), color = colors.muted)
                entry.sizeLabel?.let { Text(it, style = HearthType.monoSmall.copy(fontSize = 11.sp), color = colors.muted) }
            }
            Spacer(Modifier.height(3.dp))
            Text(entry.pathLabel, style = HearthType.monoSmall, color = colors.muted, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        HearthButton(FilesCopy.SHOW, onOpen, tone = ButtonTone.Ghost, size = ButtonSize.Small)
    }
}

private fun iconFor(id: String): ImageVector = when (id) {
    "models" -> HearthIcons.Download
    else -> HearthIcons.Folder
}

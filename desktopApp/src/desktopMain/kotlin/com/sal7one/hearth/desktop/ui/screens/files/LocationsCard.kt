package com.sal7one.hearth.desktop.ui.screens.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** The folders Hearth owns. Opening one uses the absolute path, never the label. */
@Composable
internal fun LocationsCard(
    state: FilesState,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 20.dp) {
        Text(FilesCopy.LOCATIONS, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
        Spacer(Modifier.height(3.dp))
        Text(FilesCopy.LOCATIONS_DETAIL, style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.locations.forEach { entry ->
                LocationRow(entry, onOpen = { onOpen(entry.absolutePath) })
            }
        }
    }
}

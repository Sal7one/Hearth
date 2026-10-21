package com.sal7one.hearth.desktop.ui.screens.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Export and import always ask for a path before touching the filesystem. */
@Composable
internal fun PortableSetupCard(
    actions: FilesActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 20.dp) {
        Text(FilesCopy.PORTABLE, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
        Spacer(Modifier.height(3.dp))
        Text(FilesCopy.PORTABLE_DETAIL, style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HearthButton(FilesCopy.EXPORT, actions.onExportSetup, tone = ButtonTone.Primary, size = ButtonSize.Small)
            HearthButton(FilesCopy.IMPORT, actions.onImportSetup, tone = ButtonTone.Secondary, size = ButtonSize.Small)
        }
        Spacer(Modifier.height(12.dp))
        Text(FilesCopy.PORTABLE_NOTE, style = HearthType.small, color = colors.muted)
    }
}

package com.sal7one.hearth.desktop.ui.screens.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.PageHeader
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsSubnav
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.Space

/**
 * Storage settings. Only folders Hearth actually writes are listed, and export or
 * import always asks for a path first.
 */
@Composable
internal fun FilesScreen(
    state: FilesState,
    actions: FilesActions,
    current: DesktopScreen = DesktopScreen.FILES,
    modifier: Modifier = Modifier,
) {
    PageScaffold(modifier, bottom = 40) {
        PageHeader(
            eyebrow = FilesCopy.EYEBROW,
            title = FilesCopy.TITLE,
            lede = FilesCopy.LEDE,
            actions = {
                HearthButton(
                    FilesCopy.OPEN,
                    onClick = { actions.onOpenFolder(state.dataDirectory) },
                    tone = ButtonTone.Secondary,
                    size = ButtonSize.Small,
                )
            },
        )

        SettingsSubnav(current = current, onNavigate = actions.onNavigate)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LocationsCard(state, onOpen = actions.onOpenFolder)
                StorageCard(state)
                PortableSetupCard(actions)
            }
            Column(Modifier.width(Space.sideColumn)) {
                FilesSidebar(state)
            }
        }
    }
}

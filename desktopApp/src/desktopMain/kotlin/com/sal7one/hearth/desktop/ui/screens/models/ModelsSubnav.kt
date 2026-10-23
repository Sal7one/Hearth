package com.sal7one.hearth.desktop.ui.screens.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.Subnav
import com.sal7one.hearth.desktop.ui.theme.HearthTheme

/** Library / Presets / Downloads. Shared by the three pages in this section. */
@Composable
internal fun ModelsSubnav(
    current: DesktopScreen,
    onNavigate: (DesktopScreen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        DesktopScreen.MODELS to "Library",
        DesktopScreen.PRESETS to "Presets",
        DesktopScreen.DOWNLOADS to "Downloads",
    )
    Subnav(
        items = items.map { (screen, label) -> screen.id to label },
        currentId = current.id,
        onSelect = { id -> onNavigate(DesktopScreen.fromId(id)) },
        modifier = modifier,
    )
}

/** Hairline used between grouped rows and inside card footers. */
@Composable
internal fun ModelsDivider(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(HearthTheme.colors.border),
    )
}

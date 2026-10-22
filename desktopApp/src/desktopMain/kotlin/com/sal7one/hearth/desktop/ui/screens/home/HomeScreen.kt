package com.sal7one.hearth.desktop.ui.screens.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.shell.PageScaffold

/**
 * Home. One hero, the map, four shortcuts, three readiness facts — in that order,
 * because that is the order a session starts in.
 */
@Composable
internal fun HomeScreen(
    state: HomeState,
    onStart: () -> Unit,
    onSwapLanguages: () -> Unit,
    onOpen: (DesktopScreen) -> Unit,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
    onSourceLanguage: (String) -> Unit = {},
    onTargetLanguage: (String) -> Unit = {},
    onOpenSetup: () -> Unit = {},
) {
    PageScaffold(modifier) {
        HomeHero(
            state = state,
            onStart = onStart,
            onSwapLanguages = onSwapLanguages,
            onSourceLanguage = onSourceLanguage,
            onTargetLanguage = onTargetLanguage,
            onOpenSetup = onOpenSetup,
            onOpenInput = onOpenWorkspace,
        )
        Spacer(Modifier.height(44.dp))
        HomeFeatureMap(
            features = HomeCopy.features,
            onOpen = onOpen,
            onOpenWorkspace = onOpenWorkspace,
        )
        Spacer(Modifier.height(16.dp))
        HomeQuickLinks(onOpen)
        Spacer(Modifier.height(26.dp))
        HomeReadiness(state)
    }
}

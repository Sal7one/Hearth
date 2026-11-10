package com.sal7one.hearth.desktop.ui.screens.home

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
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.components.ButtonSize
import com.sal7one.hearth.desktop.ui.components.ButtonTone
import com.sal7one.hearth.desktop.ui.components.Eyebrow
import com.sal7one.hearth.desktop.ui.components.HearthButton
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.screens.models.ModelMeasurements
import com.sal7one.hearth.desktop.ui.screens.presets.PresetPrimaryAction
import com.sal7one.hearth.desktop.ui.screens.presets.PresetView
import com.sal7one.hearth.desktop.ui.screens.presets.PresetsActions
import com.sal7one.hearth.desktop.ui.shell.PageScaffold
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

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
    starter: PresetView? = null,
    presetActions: PresetsActions = PresetsActions(),
) {
    PageScaffold(modifier) {
        if (!state.hasSavedSpeech && !state.statusLive && starter != null) {
            HomeStarter(starter, presetActions)
            Spacer(Modifier.height(16.dp))
        }
        HomeHero(
            state = state,
            onStart = onStart,
            onSwapLanguages = onSwapLanguages,
            onSourceLanguage = onSourceLanguage,
            onTargetLanguage = onTargetLanguage,
            onOpenSetup = onOpenSetup,
            onOpenInput = onOpenWorkspace,
        )
        Spacer(Modifier.height(16.dp))
        HomeReadiness(state)
        Spacer(Modifier.height(44.dp))
        HomeFeatureMap(
            features = HomeCopy.features,
            onOpen = onOpen,
            onOpenWorkspace = onOpenWorkspace,
        )
        Spacer(Modifier.height(16.dp))
        HomeQuickLinks(onOpen)
    }
}

@Composable
private fun HomeStarter(starter: PresetView, actions: PresetsActions) {
    val colors = HearthTheme.colors
    HearthCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow("Start with a preset")
                Text(starter.title, style = HearthType.h3, color = colors.text)
                Text(starter.description, style = HearthType.small, color = colors.muted)
                if (starter.measurements.isNotEmpty()) ModelMeasurements(starter.measurements.take(1))
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PresetPrimaryAction(starter, actions)
                HearthButton("All presets", { actions.onNavigate(DesktopScreen.PRESETS) }, tone = ButtonTone.Ghost,
                    size = ButtonSize.Small)
            }
        }
    }
}

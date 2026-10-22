package com.sal7one.hearth.desktop.ui.screens.speech

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceCard
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsChoiceGrid

/**
 * Local and cloud speech engines as equal choices. An unavailable engine keeps its
 * card and states the reason; the local card leads because it is the default.
 */
@Composable
internal fun SpeechEngineSection(
    state: SpeechState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsChoiceGrid(state.engines, modifier) { engine, cardModifier ->
            ChoiceCard(
                option = engine,
                selected = engine.id == state.selectedEngineId,
                onClick = { onSelect(engine.id) },
                modifier = cardModifier,
            )
    }
}

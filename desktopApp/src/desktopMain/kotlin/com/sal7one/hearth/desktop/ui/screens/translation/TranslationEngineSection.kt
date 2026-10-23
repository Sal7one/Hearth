package com.sal7one.hearth.desktop.ui.screens.translation

import androidx.compose.runtime.Composable
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceCard
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsChoiceGrid

/** Local, cloud, or off — the third card is the honest way to disable the stage. */
@Composable
internal fun TranslationEngineSection(
    state: TranslationState,
    actions: TranslationActions,
) {
    val options = state.engines + ChoiceOption("", "—", "Off", "Captions only", "Recognition still publishes the original.", true)
    SettingsChoiceGrid(options) { engine, cardModifier ->
            ChoiceCard(
                option = engine,
                selected = if (engine.id.isEmpty()) state.translationOff else engine.id == state.selectedEngineId,
                onClick = { actions.onSelectEngine(engine.id.takeIf(String::isNotEmpty)) },
                modifier = cardModifier,
            )
    }
}

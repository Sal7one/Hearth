package com.sal7one.hearth.desktop.ui.screens.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceCard
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption

/** Local, cloud, or off — the third card is the honest way to disable the stage. */
@Composable
internal fun TranslationEngineSection(
    state: TranslationState,
    actions: TranslationActions,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        state.engines.forEach { engine ->
            ChoiceCard(
                option = engine,
                selected = engine.id == state.selectedEngineId,
                onClick = { actions.onSelectEngine(engine.id) },
                modifier = Modifier.weight(1f),
            )
        }
        ChoiceCard(
            option = ChoiceOption(
                id = "",
                badge = "—",
                name = "Off",
                detail = "Captions only",
                note = "Recognition still publishes the original.",
                available = true,
            ),
            selected = state.translationOff,
            onClick = { actions.onSelectEngine(null) },
            modifier = Modifier.weight(1f),
        )
    }
}

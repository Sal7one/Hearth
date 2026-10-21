package com.sal7one.hearth.desktop.ui.screens.speech

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceCard

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
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.engines.forEach { engine ->
            ChoiceCard(
                option = engine,
                selected = engine.id == state.selectedEngineId,
                onClick = { onSelect(engine.id) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

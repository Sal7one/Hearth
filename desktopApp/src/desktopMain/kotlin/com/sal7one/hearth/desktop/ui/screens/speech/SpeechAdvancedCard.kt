package com.sal7one.hearth.desktop.ui.screens.speech

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.HearthSlider
import com.sal7one.hearth.desktop.ui.components.Segmented
import com.sal7one.hearth.desktop.ui.screens.settings.FormRow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Compute and threads. Both map to keys the runtime reads, nothing decorative. */
@Composable
internal fun SpeechAdvancedCard(
    state: SpeechState,
    actions: SpeechActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Text(SpeechCopy.ADVANCED, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
            Spacer(Modifier.height(3.dp))
            Text(SpeechCopy.ADVANCED_DETAIL, style = HearthType.small, color = colors.muted)
            state.computeIssue?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = HearthType.small, color = colors.warn)
            }

            FormRow(
                label = SpeechCopy.COMPUTE,
                hint = SpeechCopy.COMPUTE_HINT,
            ) {
                Segmented(
                    options = state.computeOptions.map { it.label },
                    selected = if (state.computeIssue == null) state.computeOptions.indexOf(state.compute) else -1,
                    onSelect = { index -> actions.onCompute(state.computeOptions[index]) },
                    modifier = Modifier.width(if (state.computeOptions.size > 2) 330.dp else 260.dp),
                )
            }

            FormRow(
                label = SpeechCopy.THREADS,
                hint = SpeechCopy.THREADS_HINT,
                showDivider = false,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HearthSlider(
                        value = (state.threads - 1) / 15f,
                        onValueChange = { fraction -> actions.onThreads((1 + fraction * 15).toInt().coerceIn(1, 16)) },
                        modifier = Modifier.width(240.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("${state.threads}", style = HearthType.monoSmall, color = colors.text)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

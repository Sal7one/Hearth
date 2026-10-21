package com.sal7one.hearth.desktop.ui.screens.translation

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

/** Compute and threads, the same shape as the speech page. */
@Composable
internal fun TranslationAdvancedCard(
    state: TranslationState,
    actions: TranslationActions,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Text(TranslationCopy.ADVANCED, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
            Spacer(Modifier.height(3.dp))
            Text(TranslationCopy.ADVANCED_DETAIL, style = HearthType.small, color = colors.muted)
            FormRow(label = TranslationCopy.COMPUTE, hint = TranslationCopy.COMPUTE_HINT) {
                Segmented(
                    options = state.computeOptions.map { it.label },
                    selected = state.computeOptions.indexOf(state.compute).coerceAtLeast(0),
                    onSelect = { index -> actions.onCompute(state.computeOptions[index]) },
                    modifier = Modifier.width(260.dp),
                )
            }
            FormRow(label = TranslationCopy.THREADS, hint = TranslationCopy.THREADS_HINT, showDivider = false) {
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

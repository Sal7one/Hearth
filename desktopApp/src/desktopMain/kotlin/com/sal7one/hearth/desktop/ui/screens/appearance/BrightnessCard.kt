package com.sal7one.hearth.desktop.ui.screens.appearance

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sal7one.hearth.desktop.ui.components.HearthCard
import com.sal7one.hearth.desktop.ui.components.Segmented
import com.sal7one.hearth.desktop.ui.components.SwitchRow
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import com.sal7one.hearth.desktop.ui.theme.HearthType

/** Light/dark and reduce motion. Both take effect without an apply step. */
@Composable
internal fun BrightnessCard(
    state: AppearanceState,
    onDark: (Boolean) -> Unit,
    onReduceMotion: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HearthTheme.colors
    HearthCard(modifier, padding = 20.dp) {
        Text(AppearanceCopy.BRIGHTNESS, style = HearthType.h3.copy(fontSize = 15.sp), color = colors.text)
        Spacer(Modifier.height(3.dp))
        Text(AppearanceCopy.BRIGHTNESS_DETAIL, style = HearthType.small, color = colors.muted)
        Spacer(Modifier.height(14.dp))
        Segmented(
            options = listOf("Light", "Dark"),
            selected = if (state.dark) 1 else 0,
            onSelect = { index -> onDark(index == 1) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        SwitchRow(AppearanceCopy.MOTION, AppearanceCopy.MOTION_DETAIL, state.reduceMotion, onReduceMotion)
    }
}

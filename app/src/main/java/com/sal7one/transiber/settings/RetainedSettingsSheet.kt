package com.sal7one.transiber.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Keep the sheet's position and inline options while a child page replaces its content.
 *
 * [ready] must describe the actual settings data. Composing a shorter loading placeholder
 * with this scroll state would clamp a restored position before the real options arrive.
 * Keep this owner composed when [visible] is false.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun RetainedSettingsSheet(
    visible: Boolean,
    ready: Boolean,
    presentationSession: Int = 0,
    retainingForChild: Boolean = false,
    content: @Composable (ScrollState, SheetState) -> Unit,
) {
    val scroll = rememberScrollState()
    // Retain the expanded offset and presentation object, not only its saved enum.
    // A child push removes the dialog content, but must not create an entrance on Back.
    val presentation = key(presentationSession) {
        val opening = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var interruptedByChild by rememberSaveable { mutableStateOf(false) }
        SideEffect {
            if (!visible && retainingForChild && !interruptedByChild &&
                (opening.currentValue != SheetValue.Expanded || opening.targetValue != SheetValue.Expanded)) {
                interruptedByChild = true
            }
        }
        if (interruptedByChild) {
            val density = LocalDensity.current
            val positionalThreshold = { with(density) { 56.dp.toPx() } }
            val velocityThreshold = { with(density) { 125.dp.toPx() } }
            rememberSaveable(saver = SheetState.Saver(true, positionalThreshold, velocityThreshold, { true }, false)) {
                SheetState(skipPartiallyExpanded = true, positionalThreshold = positionalThreshold,
                    velocityThreshold = velocityThreshold, initialValue = SheetValue.Expanded)
            }
        } else opening
    }
    val contentState = rememberSaveableStateHolder()
    if (visible && ready) contentState.SaveableStateProvider("quick-settings") {
        content(scroll, presentation)
    }
}

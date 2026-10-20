package com.sal7one.transiber.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

/** Keep the sheet's position and inline options while a child page replaces its content.
 *
 * [ready] must describe the actual settings data. Composing a shorter loading placeholder
 * with this scroll state would clamp a restored position before the real options arrive.
 * Keep this owner composed when [visible] is false.
 */
@Composable
internal fun RetainedSettingsSheet(
    visible: Boolean,
    ready: Boolean,
    content: @Composable (ScrollState) -> Unit,
) {
    val scroll = rememberScrollState()
    val sheetState = rememberSaveableStateHolder()
    if (visible && ready) sheetState.SaveableStateProvider("quick-settings") {
        content(scroll)
    }
}

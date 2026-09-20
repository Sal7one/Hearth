package com.sal7one.transiber.caption

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Lives in its own touchable window; a child of the caption window cannot override NOT_TOUCHABLE. */
@Composable
internal fun TapThroughRecoveryHandle(onRestore: () -> Unit, onDrag: (Int, Int) -> Unit, onDragFinished: () -> Unit) {
    val uiText = rememberUiText()

    val step = with(LocalDensity.current) { 32.dp.roundToPx() }
    Box(Modifier.fillMaxSize()
        .clickable(role = Role.Button, onClickLabel = uiText(UiR.string.ui_restore_caption_controls_c9c06), onClick = onRestore)
        .pointerInput(Unit) {
            detectDragGestures(onDragEnd = onDragFinished, onDragCancel = onDragFinished) { change, delta ->
                change.consume()
                onDrag(delta.x.toInt(), delta.y.toInt())
            }
        }
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(uiText(UiR.string.ui_move_captions_up_d94cb)) { onDrag(0, -step); onDragFinished(); true },
                CustomAccessibilityAction(uiText(UiR.string.ui_move_captions_down_f1f06)) { onDrag(0, step); onDragFinished(); true },
                CustomAccessibilityAction(uiText(UiR.string.ui_move_captions_left_920be)) { onDrag(-step, 0); onDragFinished(); true },
                CustomAccessibilityAction(uiText(UiR.string.ui_move_captions_right_a939e)) { onDrag(step, 0); onDragFinished(); true },
            )
        }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFFE2DFFF))
            .border(1.dp,Color(0xFF423478),CircleShape),contentAlignment=Alignment.Center) {
            Icon(Icons.Default.LockOpen, uiText(UiR.string.ui_restore_caption_controls_drag_to_move_captions_6f8dd), Modifier.size(20.dp), tint = Color(0xFF241A44))
        }
    }
}

package com.sal7one.transiber.caption

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
    val step = with(LocalDensity.current) { 32.dp.roundToPx() }
    Box(Modifier.fillMaxSize().clip(CircleShape)
        .background(Color(0xFFE2DFFF)).border(2.dp, Color(0xFF423478), CircleShape)
        .clickable(role = Role.Button, onClickLabel = "Restore caption controls", onClick = onRestore)
        .pointerInput(Unit) {
            detectDragGestures(onDragEnd = onDragFinished, onDragCancel = onDragFinished) { change, delta ->
                change.consume()
                onDrag(delta.x.toInt(), delta.y.toInt())
            }
        }
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction("Move captions up") { onDrag(0, -step); onDragFinished(); true },
                CustomAccessibilityAction("Move captions down") { onDrag(0, step); onDragFinished(); true },
                CustomAccessibilityAction("Move captions left") { onDrag(-step, 0); onDragFinished(); true },
                CustomAccessibilityAction("Move captions right") { onDrag(step, 0); onDragFinished(); true },
            )
        }, contentAlignment = Alignment.Center) {
        Icon(Icons.Default.LockOpen, "Restore caption controls. Drag to move captions.", Modifier.size(28.dp), tint = Color(0xFF241A44))
    }
}

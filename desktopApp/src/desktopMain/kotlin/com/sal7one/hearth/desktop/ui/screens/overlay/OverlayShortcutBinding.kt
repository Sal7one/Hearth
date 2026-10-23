package com.sal7one.hearth.desktop.ui.screens.overlay

import androidx.compose.runtime.*
import com.sal7one.hearth.desktop.platform.SystemOverlayShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher

/** One owned registration set per application, released even on startup/coroutine failure. */
@Composable
internal fun OverlayShortcutBinding(enabled: Boolean, onCommand: (OverlayCommand) -> Unit): String {
    val command by rememberUpdatedState(onCommand)
    var status by remember { mutableStateOf("Starting system shortcuts…") }
    // Native hotkeys consume physical events. A focused-app fallback also accepts
    // menu/assistive key delivery. Coalesce duplicate delivery of the same press.
    val gate = remember { OverlayShortcutGate() }
    val deliver by rememberUpdatedState<(OverlayCommand) -> Unit>({ action -> if (gate.accept(action)) command(action) })
    LaunchedEffect(enabled) {
        if (!enabled) { status = "System shortcuts off · focused-app fallback is enabled"; return@LaunchedEffect }
        val shortcuts = SystemOverlayShortcuts { deliver(it) }
        try {
            status = withContext(Dispatchers.IO) { shortcuts.start() }
            while (isActive) { delay(2_000); status = shortcuts.status }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { shortcuts.close() }
        }
    }
    DisposableEffect(Unit) {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            val action = overlayCommand(event, System.getProperty("os.name").contains("Mac", true))
            if (action != null && !event.isConsumed) { deliver(action); event.consume(); true } else false
        }
        manager.addKeyEventDispatcher(dispatcher)
        onDispose { manager.removeKeyEventDispatcher(dispatcher) }
    }
    return status
}

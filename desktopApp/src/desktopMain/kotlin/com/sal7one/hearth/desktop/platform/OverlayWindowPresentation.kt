package com.sal7one.hearth.desktop.platform

import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** Repair Swing backing panes as well as AppKit, without altering caption style or saved opacity. */
internal fun prepareOverlayBacking(window: ComposeWindow, mac: Boolean = System.getProperty("os.name").contains("Mac", true)) {
    check(EventQueue.isDispatchThread()) { "Overlay backing must be prepared on AWT" }
    check(window.isTransparent) { "Caption window must use Compose transparency" }
    if (mac) {
        // Do not do this on Windows: Skiko's OpenGL/software renderers own a different transparency workaround.
        if (window.background?.alpha != 0) window.background = Color(0, 0, 0, 0)
        window.rootPane.isOpaque = false
        window.layeredPane.isOpaque = false
        (window.contentPane as? JComponent)?.isOpaque = false
    }
}

/** A peer/renderer can change after the initial effect. Coalesce native repairs outside AWT locks. */
@Composable
internal fun OverlayWindowPresentation(window: ComposeWindow, clickThrough: Boolean,
    onError: (String) -> Unit, onApplied: (Boolean) -> Unit = {}) {
    var revision by remember(window) { mutableIntStateOf(0) }
    val error by rememberUpdatedState(onError)
    val applied by rememberUpdatedState(onApplied)
    DisposableEffect(window) {
        var attached = true
        fun refresh() { if (attached) revision++ }
        val focus = object : WindowAdapter() {
            override fun windowGainedFocus(event: WindowEvent) = refresh()
            override fun windowLostFocus(event: WindowEvent) = refresh()
            override fun windowOpened(event: WindowEvent) = refresh()
        }
        val bounds = object : ComponentAdapter() {
            override fun componentShown(event: ComponentEvent) = refresh()
            override fun componentResized(event: ComponentEvent) = refresh()
        }
        val properties = PropertyChangeListener {
            if (it.propertyName in setOf("graphicsConfiguration", "background", "focusableWindowState")) refresh()
        }
        window.addWindowFocusListener(focus)
        window.addWindowListener(focus)
        window.addComponentListener(bounds)
        window.addPropertyChangeListener(properties)
        // Compose exposes no removal API. This callback belongs to this window and becomes inert on disposal.
        window.onRenderApiChanged { EventQueue.invokeLater { refresh() } }
        onDispose {
            attached = false
            window.removeWindowFocusListener(focus)
            window.removeWindowListener(focus)
            window.removeComponentListener(bounds)
            window.removePropertyChangeListener(properties)
        }
    }
    LaunchedEffect(window, clickThrough, revision) {
        try {
            // Let AWT finish peer/focus/resize updates before AppKit repairs and the fresh frame.
            delay(50)
            var handle = 0L
            repeat(20) {
                if (handle == 0L && window.isDisplayable) handle = window.windowHandle
                if (handle == 0L) delay(25)
            }
            check(handle != 0L) { "The caption window is not ready" }
            // Changing focusability AFTER the native call can invalidate the just-applied backing.
            if (window.focusableWindowState == clickThrough) window.focusableWindowState = !clickThrough
            prepareOverlayBacking(window)
            // Focusability can affect the native peer. Read its current handle after preparation.
            handle = window.windowHandle
            withContext(Dispatchers.IO) { NativeOverlayWindow.apply(handle, clickThrough) }
            window.repaint()
            applied(clickThrough)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            error(failure.message ?: failure.toString())
        }
    }
}

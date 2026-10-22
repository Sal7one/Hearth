package com.sal7one.hearth.desktop.ui.screens.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.sal7one.hearth.desktop.platform.NativeOverlayWindow
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent

/** Saved in AWT logical pixels, independent of the display's physical pixel density. */
internal fun java.awt.Window.overlayBounds() = OverlayBounds(x, y, width, height)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun FloatingCaptionWindow(
    state: OverlayState,
    dark: Boolean,
    preset: AccentPreset,
    onClose: () -> Unit,
    onChange: (OverlaySettings) -> Unit,
    onNativeError: (String) -> Unit,
    onNativeApplied: (Boolean) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val latest by rememberUpdatedState(state.settings)
    val change by rememberUpdatedState(onChange)
    val close by rememberUpdatedState(onClose)
    val initial = remember { initialOverlayBounds(state.settings, overlayDisplays()) }
    val windowState = rememberWindowState(position = WindowPosition.Absolute(initial.x.dp, initial.y.dp),
        width = initial.width.dp, height = initial.height.dp)
    // Unique within the process; the native bridge only resolves this window.
    val title = if (state.preview) "Hearth preview captions" else "Hearth captions"
    Window(onCloseRequest = onClose, title = title, icon = painterResource("hearth.png"), alwaysOnTop = true, undecorated = true,
        transparent = true, resizable = true, state = windowState) {
        var moved by remember { mutableStateOf<OverlayBounds?>(null) }
        var hovered by remember { mutableStateOf(false) }
        var interacting by remember { mutableStateOf(false) }
        var menuOpen by remember { mutableStateOf(false) }
        val controls = !state.settings.clickThrough && (hovered || interacting || menuOpen || !state.hasCaption)
        DisposableEffect(window) {
            window.minimumSize = java.awt.Dimension(340, 90)
            val listener = object : ComponentAdapter() {
                override fun componentMoved(event: ComponentEvent) { moved = window.overlayBounds() }
                override fun componentResized(event: ComponentEvent) { moved = window.overlayBounds() }
            }
            window.addComponentListener(listener)
            onDispose { window.removeComponentListener(listener) }
        }
        LaunchedEffect(state.settings.bounds, state.settings.position, state.settings.widthFraction, state.settings.pinnedDisplay, state.settings.windowHeight) {
            val bounds = initialOverlayBounds(state.settings, overlayDisplays())
            if (window.overlayBounds() != bounds) window.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        }
        LaunchedEffect(moved, interacting) {
            val bounds = moved ?: return@LaunchedEffect
            if (interacting) return@LaunchedEffect
            delay(350)
            val display = overlayDisplayFor(bounds, overlayDisplays())
            val safe = clampOverlayBounds(bounds, display.area)
            if (safe != bounds) window.setBounds(safe.x, safe.y, safe.width, safe.height)
            if (safe != latest.bounds) change(latest.copy(bounds = safe, pinnedDisplay = display.id, windowHeight = safe.height,
                widthFraction = (safe.width.toFloat() / display.area.width).coerceIn(0.25f, 1f)))
        }
        LaunchedEffect(state.settings.clickThrough) {
            // Compose may create its peer after this effect starts. Wait a bounded interval.
            try {
                repeat(20) { if (!window.isDisplayable) delay(25) }
                // AppKit can synchronously query AWT for accessibility. Never wait
                // for AppKit from AWT's event thread or while painting/tree-locked.
                withContext(Dispatchers.IO) { NativeOverlayWindow.apply(window, title, state.settings.clickThrough) }
                window.focusableWindowState = !state.settings.clickThrough
                onNativeApplied(state.settings.clickThrough)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                onNativeError(error.message ?: error.toString())
            }
        }
        DisposableEffect(window) {
            val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            val keys = KeyEventDispatcher { event ->
                    if (manager.focusedWindow != window || event.id != KeyEvent.KEY_PRESSED ||
                        event.isControlDown || event.isMetaDown || latest.clickThrough || event.isConsumed) return@KeyEventDispatcher false
                    if (event.keyCode == KeyEvent.VK_ESCAPE) { close(); event.consume(); return@KeyEventDispatcher true }
                    val step = if (event.isShiftDown) 32 else 8
                    val dx = when (event.keyCode) { KeyEvent.VK_LEFT -> -step; KeyEvent.VK_RIGHT -> step; else -> 0 }
                    val dy = when (event.keyCode) { KeyEvent.VK_UP -> -step; KeyEvent.VK_DOWN -> step; else -> 0 }
                    if (dx == 0 && dy == 0) return@KeyEventDispatcher false
                    val old = window.overlayBounds()
                    val next = if (event.isAltDown) resizeOverlayBounds(old, dx, dy, ResizeEdge.BOTTOM_RIGHT)
                        else old.copy(x = old.x + dx, y = old.y + dy)
                    window.setBounds(next.x, next.y, next.width, next.height)
                    event.consume()
                    true
            }
            manager.addKeyEventDispatcher(keys)
            onDispose { manager.removeKeyEventDispatcher(keys) }
        }
        HearthTheme(preset = preset, dark = dark) {
            Box(Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
                .background(if (controls && state.settings.shadeHoverArea) Color(0xA6161C24) else Color.Transparent, RoundedCornerShape(16.dp))
                .border(1.dp, if (controls) Color.White.copy(alpha = 0.23f) else Color.Transparent, RoundedCornerShape(16.dp))) {
                Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)
                    .windowDrag(window, enabled = !state.settings.clickThrough, activity = { interacting = it }),
                    contentAlignment = Alignment.BottomCenter) {
                    CaptionLayer(state, Modifier.fillMaxWidth())
                }
                if (state.preview) LiveBadge("Preview · sample captions", Modifier.align(Alignment.TopStart).padding(10.dp))
                if (controls) {
                    val density = LocalDensity.current
                    val toolbarScale = latest.toolbarScale.coerceAtMost(((moved?.width ?: initial.width) - 20) / 400f)
                    CompositionLocalProvider(LocalDensity provides Density(density.density * toolbarScale, density.fontScale)) {
                    Row(Modifier.align(Alignment.TopCenter).padding(top = 7.dp)
                        .background(Color(0xF21A222D), RoundedCornerShape(30.dp))
                        .windowDrag(window, activity = { interacting = it }).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Image(painterResource("hearth.png"), contentDescription = "Hearth · Open overlay settings",
                            modifier = Modifier.padding(horizontal = 3.dp).size(26.dp).clip(RoundedCornerShape(8.dp))
                                .clickable(role = Role.Button, onClickLabel = "Open overlay settings", onClick = onOpenSettings))
                        Text("⠿", color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(horizontal = 6.dp), fontSize = 18.sp)
                        OverlayTool("A−", "Smaller text") { change(reduceOverlayCommand(latest, OverlayCommand.SMALLER)) }
                        OverlayTool("A+", "Larger text") { change(reduceOverlayCommand(latest, OverlayCommand.BIGGER)) }
                        OverlayTool("CC", if (latest.showOriginal) "Hide original line" else "Show original line", latest.showOriginal) {
                            change(reduceOverlayCommand(latest, OverlayCommand.ORIGINAL)) }
                        OverlayTool("Style", "Cycle subtitle style") { change(reduceOverlayCommand(latest, OverlayCommand.STYLE)) }
                        if (state.nativeControlsAvailable) OverlayTool("Lock", "Click through · ${OverlayCommand.LOCK.shortcutLabel()} to unlock") {
                            change(latest.copy(clickThrough = true)) }
                        OverlayTool("⋯", "Adjust hover controls") { menuOpen = !menuOpen }
                        OverlayTool("↺", "Reset position") { change(reduceOverlayCommand(latest, OverlayCommand.RESET)) }
                        OverlayTool("×", "Hide captions · ${OverlayCommand.TOGGLE.shortcutLabel()}", onClick = onClose)
                    }
                    }
                    Text("⌟", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                    ResizeHandles(window, { interacting = it })
                }
            }
        }
        if (menuOpen) OverlayControlWindow(latest, preset, dark, onChange = change,
            onClose = { menuOpen = false }, onNativeError = onNativeError)
    }
}

/** A separate utility window stays usable even when the caption area is only 90 pt tall. */
@Composable
private fun OverlayControlWindow(settings: OverlaySettings, preset: AccentPreset, dark: Boolean,
    onChange: (OverlaySettings) -> Unit, onClose: () -> Unit, onNativeError: (String) -> Unit) {
    val initial = remember {
        val area = overlayDisplayFor(settings.bounds, overlayDisplays(), settings.pinnedDisplay).area
        val base = settings.bounds ?: anchoredOverlayBounds(settings, overlayDisplayFor(null, overlayDisplays()))
        clampOverlayBounds(OverlayBounds(base.x + base.width - 320, base.y - 310, 340, 310), area)
    }
    Window(onCloseRequest = onClose, title = "Hearth caption controls", icon = painterResource("hearth.png"), alwaysOnTop = true,
        undecorated = true, transparent = true, resizable = false,
        state = rememberWindowState(position = WindowPosition.Absolute(initial.x.dp, initial.y.dp), width = 340.dp, height = 310.dp),
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onClose(); true
            } else false
        }) {
        LaunchedEffect(Unit) {
            try {
                repeat(20) { if (!window.isDisplayable) delay(25) }
                withContext(Dispatchers.IO) { NativeOverlayWindow.apply(window, "Hearth caption controls", false) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                onNativeError(error.message ?: error.toString())
            }
        }
        HearthTheme(preset = preset, dark = dark) {
            Column(Modifier.fillMaxSize().background(Color(0xF51A2029), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp)).padding(18.dp)) {
                Row(Modifier.fillMaxWidth().windowDrag(window, activity = {}), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource("hearth.png"), "Hearth", Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text("Caption controls", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    OverlayTool("×", "Close caption controls", onClick = onClose)
                }
                Spacer(Modifier.height(8.dp))
                OverlayQuickControls(settings, onChange)
            }
        }
    }
}

private fun Modifier.windowDrag(window: ComposeWindow, edge: ResizeEdge? = null, enabled: Boolean = true,
    activity: (Boolean) -> Unit): Modifier = if (!enabled) this else pointerInput(window, edge) {
    var start = window.overlayBounds()
    var origin = java.awt.Point()
    detectDragGestures(onDragStart = { start = window.overlayBounds(); origin = MouseInfo.getPointerInfo().location; activity(true) },
        onDragEnd = { activity(false) }, onDragCancel = { activity(false) }) { event, _ ->
        event.consume()
        val point = MouseInfo.getPointerInfo().location
        val dx = point.x - origin.x; val dy = point.y - origin.y
        val bounds = edge?.let { resizeOverlayBounds(start, dx, dy, it) } ?: start.copy(x = start.x + dx, y = start.y + dy)
        window.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    }
}.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(if (edge == null) Cursor.MOVE_CURSOR else when (edge) {
    ResizeEdge.LEFT, ResizeEdge.RIGHT -> Cursor.E_RESIZE_CURSOR
    ResizeEdge.TOP, ResizeEdge.BOTTOM -> Cursor.N_RESIZE_CURSOR
    ResizeEdge.TOP_LEFT, ResizeEdge.BOTTOM_RIGHT -> Cursor.NW_RESIZE_CURSOR
    else -> Cursor.NE_RESIZE_CURSOR
})))

@Composable
private fun BoxScope.ResizeHandles(window: ComposeWindow, activity: (Boolean) -> Unit) {
    ResizeEdge.entries.forEach { edge ->
        val corner = (edge.left || edge.right) && (edge.top || edge.bottom)
        val alignment = when (edge) {
            ResizeEdge.LEFT -> Alignment.CenterStart; ResizeEdge.RIGHT -> Alignment.CenterEnd
            ResizeEdge.TOP -> Alignment.TopCenter; ResizeEdge.BOTTOM -> Alignment.BottomCenter
            ResizeEdge.TOP_LEFT -> Alignment.TopStart; ResizeEdge.TOP_RIGHT -> Alignment.TopEnd
            ResizeEdge.BOTTOM_LEFT -> Alignment.BottomStart; ResizeEdge.BOTTOM_RIGHT -> Alignment.BottomEnd
        }
        val dimensions = if (corner) Modifier.size(15.dp) else if (edge.left || edge.right) Modifier.width(7.dp).fillMaxHeight(0.7f)
            else Modifier.height(7.dp).fillMaxWidth(0.85f)
        Box(Modifier.align(alignment).then(dimensions).windowDrag(window, edge = edge, activity = activity))
    }
}

@Composable
private fun OverlayTool(label: String, description: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(label, color = if (selected) Color(0xFF71E4DE) else Color.White, fontSize = 12.sp,
        modifier = Modifier.background(if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClickLabel = description, onClick = onClick).padding(horizontal = 9.dp, vertical = 6.dp)
)
}

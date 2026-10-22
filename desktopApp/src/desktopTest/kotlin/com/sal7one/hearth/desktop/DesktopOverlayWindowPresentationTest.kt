package com.sal7one.hearth.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import com.sal7one.hearth.desktop.platform.MacMainQueue
import com.sal7one.hearth.desktop.platform.NativeOverlayWindow
import com.sal7one.hearth.desktop.platform.OverlayWindowPresentation
import com.sun.jna.Pointer
import org.junit.Assume.assumeTrue
import java.awt.Color
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.event.WindowEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Opt-in AppKit regression. Opens only disposable test windows, without audio, models or saved preferences.
 * HEARTH_OVERLAY_WINDOW_TEST=1 JAVA_TOOL_OPTIONS=-Djava.awt.headless=false ./gradlew
 * -PhearthDesktopOnly=true :desktopApp:desktopTest --tests '*DesktopOverlayWindowPresentationTest*'
 */
class DesktopOverlayWindowPresentationTest {
    @Test fun repairsOpaqueBackingWithoutResizeAndTargetsTheExactPeer() {
        assumeTrue("Requires an unlocked Mac and explicit opt-in", System.getenv("HEARTH_OVERLAY_WINDOW_TEST") == "1" &&
            System.getProperty("os.name").contains("Mac", true))
        assertFalse(GraphicsEnvironment.isHeadless(), "Run with -Djava.awt.headless=false")
        val clickThrough = mutableStateOf(false)
        val errors = ConcurrentLinkedQueue<String>()
        val applications = AtomicInteger()
        val windows = onAwt {
            List(2) { index -> ComposeWindow().apply {
                title = "Hearth transparency regression" // Deliberately duplicated.
                isUndecorated = true
                isTransparent = true
                setSize(400, 130)
                setLocation(60 + index * 420, 80)
                setContent {
                    Box(Modifier.fillMaxSize())
                    if (index == 0) OverlayWindowPresentation(window, clickThrough.value, errors::add,
                        onApplied = { applications.incrementAndGet() })
                }
                isVisible = true
            } }
        }
        try {
            val target = windows[0]
            val other = windows[1]
            await("initial presentation completed") { applications.get() > 0 && isClear(target) }
            makeNativeOpaque(other)
            makeNativeOpaque(target)
            val initialSize = onAwt { target.size }
            onAwt {
                // This represents backing state being reset by the window/rendering stack.
                target.rootPane.isOpaque = true
                target.layeredPane.isOpaque = true
                (target.contentPane as JComponent).isOpaque = true
                target.background = Color.WHITE
            }
            await("backing repair without a resize") { isClear(target) && onAwt {
                !target.rootPane.isOpaque && !target.layeredPane.isOpaque &&
                    !(target.contentPane as JComponent).isOpaque && target.background.alpha == 0
            } }
            assertEquals(initialSize, onAwt { target.size }, "Recovery must not rely on resizing")
            assertFalse(isClear(other), "A second window with the same title must not be modified")
            // AppKit may retain a disposed window briefly. A handle absent from its
            // live registry must be rejected without ever messaging that pointer.
            assertEquals("The caption window has closed", assertFailsWith<IllegalStateException> {
                NativeOverlayWindow.apply(-1L, false)
            }.message, "An unavailable peer must be rejected before dereferencing it")

            // A native-only reset has no Swing property event. Focus loss must also restore it.
            makeNativeOpaque(target)
            onAwt { target.dispatchEvent(WindowEvent(target, WindowEvent.WINDOW_LOST_FOCUS)) }
            await("focus transition repair") { isClear(target) }

            onAwt { clickThrough.value = true }
            await("click-through presentation") { ignoresMouse(target) && onAwt { !target.focusableWindowState } }
            onAwt { clickThrough.value = false }
            await("interactive presentation") { !ignoresMouse(target) && onAwt { target.focusableWindowState } && isClear(target) }
            assertTrue(errors.isEmpty(), "Native presentation failures: $errors")
        } finally {
            onAwt { windows.forEach(ComposeWindow::dispose) }
        }
    }

    private fun makeNativeOpaque(window: ComposeWindow) {
        val handle = onAwt { window.windowHandle }
        MacMainQueue.run {
            val target = Pointer(handle)
            MacMainQueue.set(target, "setOpaque:", 1.toByte())
            MacMainQueue.set(target, "setBackgroundColor:", requireNotNull(MacMainQueue.pointer(MacMainQueue.cls("NSColor"), "whiteColor")))
        }
        assertFalse(isClear(window), "The opaque-backing fixture must actually be opaque")
    }

    private fun isClear(window: ComposeWindow): Boolean {
        val handle = onAwt { window.windowHandle }
        return MacMainQueue.run {
            val target = Pointer(handle)
            MacMainQueue.number(target, "isOpaque") == 0L &&
                MacMainQueue.decimal(MacMainQueue.pointer(target, "backgroundColor"), "alphaComponent") == 0.0
        }
    }

    private fun ignoresMouse(window: ComposeWindow): Boolean {
        val handle = onAwt { window.windowHandle }
        return MacMainQueue.run { MacMainQueue.number(Pointer(handle), "ignoresMouseEvents") != 0L }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val until = System.nanoTime() + 8_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < until) { "Timed out waiting for $description" }
            Thread.sleep(25)
        }
    }

    private fun <T> onAwt(action: () -> T): T {
        check(!EventQueue.isDispatchThread())
        val result = AtomicReference<Result<T>>()
        EventQueue.invokeAndWait { result.set(runCatching(action)) }
        return result.get().getOrThrow()
    }
}

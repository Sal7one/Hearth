package com.sal7one.hearth.desktop

import com.sal7one.hearth.desktop.ui.screens.overlay.*
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.*

class DesktopOverlayControlsTest {
    private val laptop = OverlayDisplay("built-in", "Laptop", OverlayBounds(0, 30, 1512, 900))
    private val left = OverlayDisplay("left", "Left display", OverlayBounds(-1920, -100, 1920, 1080))

    @Test fun removedDisplayRestoresEntireWindowToAnAvailableWorkArea() {
        val saved = OverlaySettings(pinnedDisplay = "unplugged", bounds = OverlayBounds(7000, -500, 1800, 500))
        val restored = initialOverlayBounds(saved, listOf(laptop))
        assertEquals(OverlayBounds(0, 30, 1512, 500), restored)
    }

    @Test fun negativeMonitorCoordinatesSurviveRestoreAndDisplaySelection() {
        val saved = OverlayBounds(-1750, 180, 1000, 200)
        assertEquals(left, overlayDisplayFor(saved, listOf(laptop, left)))
        assertEquals(saved, initialOverlayBounds(OverlaySettings(bounds = saved), listOf(laptop, left)))
    }

    @Test fun anchorsMoveTheWindowWithinTheChosenDisplay() {
        val top = anchoredOverlayBounds(OverlaySettings(position = OverlayPosition.TOP), left)
        val bottom = anchoredOverlayBounds(OverlaySettings(position = OverlayPosition.BOTTOM), left)
        assertEquals(-72, top.y)
        assertEquals(750, bottom.y)
        assertTrue(top.x < 0)
        assertEquals(top.width, bottom.width)
        assertEquals(left.id, overlayDisplayFor(top, listOf(laptop, left)).id)
    }

    @Test fun leftAndTopResizeKeepOppositeEdgesFixedAtMinimumSize() {
        val original = OverlayBounds(-1000, 100, 900, 250)
        val resized = resizeOverlayBounds(original, 4000, 4000, ResizeEdge.TOP_LEFT)
        assertEquals(340, resized.width)
        assertEquals(90, resized.height)
        assertEquals(original.x + original.width, resized.x + resized.width)
        assertEquals(original.y + original.height, resized.y + resized.height)
    }

    @Test fun corruptAndNonFinitePrefsCannotMakeAnInvisibleOrUnboundedWindow() {
        val settings = overlaySettingsFrom(mapOf("overlay-width" to "NaN", "overlay-scale" to "Infinity",
            "overlay-backdrop" to "-Infinity", "overlay-lines" to "9999", "overlay-window-x" to "2147483647",
            "overlay-window-y" to "0", "overlay-window-w" to "900", "overlay-window-h" to "200"))
        assertEquals(OverlaySettings().widthFraction, settings.widthFraction)
        assertEquals(1f, settings.textScale)
        assertEquals(OverlaySettings().backdropFraction, settings.backdropFraction)
        assertEquals(6, settings.maxLines)
        assertNull(settings.bounds)
    }

    @Test fun fullOverlayPreferencesRoundTripWithoutReopeningAWindowOnLaunch() {
        val settings = OverlaySettings(bounds = OverlayBounds(-1500, 50, 1100, 240), pinnedDisplay = "left",
            textScale = 1.6f, maxLines = 5, textAlign = OverlayTextAlign.START, clickThrough = true,
            style = OverlayStyle.GLASS, shortcutsEnabled = false, visible = true, toolbarScale = 1.3f, shadeHoverArea = true, windowHeight = 240)
        assertEquals(settings.copy(visible = false), overlaySettingsFrom(overlayPrefs(settings)))
        assertNull(overlaySettingsFrom(overlayPrefs(settings.copy(bounds = null))).bounds)
    }

    @Test fun shortcutChordsNeverConsumeOrdinaryTypingOrOppositePlatformModifiers() {
        fun event(key: Int, modifiers: Int, kind: Int = KeyEvent.KEY_PRESSED) =
            KeyEvent(Canvas(), kind, 0L, modifiers, key, KeyEvent.CHAR_UNDEFINED)
        val mac = InputEvent.META_DOWN_MASK or InputEvent.ALT_DOWN_MASK
        val windows = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK
        assertEquals(OverlayCommand.TOGGLE, overlayCommand(event(KeyEvent.VK_O, mac), mac = true))
        assertEquals(OverlayCommand.BIGGER, overlayCommand(event(KeyEvent.VK_UP, windows), mac = false))
        assertNull(overlayCommand(event(KeyEvent.VK_O, 0), mac = true))
        assertNull(overlayCommand(event(KeyEvent.VK_O, windows), mac = true))
        assertNull(overlayCommand(event(KeyEvent.VK_O, mac), mac = false))
        assertNull(overlayCommand(event(KeyEvent.VK_O, mac or InputEvent.SHIFT_DOWN_MASK), mac = true))
        assertNull(overlayCommand(event(KeyEvent.VK_O, mac, KeyEvent.KEY_RELEASED), mac = true))
    }

    @Test fun rescueShortcutUnlocksAndClearsPlacementWithoutLosingCaptionPreferences() {
        val original = OverlaySettings(bounds = OverlayBounds(9000, 8000, 1000, 200), pinnedDisplay = "gone",
            clickThrough = true, style = OverlayStyle.BAR, maxLines = 5, textScale = 1.4f, showOriginal = false, visible = true)
        val reset = reduceOverlayCommand(original, OverlayCommand.RESET)
        assertNull(reset.bounds)
        assertFalse(reset.clickThrough)
        assertEquals("auto", reset.pinnedDisplay)
        assertEquals(original.style, reset.style)
        assertEquals(original.maxLines, reset.maxLines)
        assertEquals(original.textScale, reset.textScale)
        assertFalse(reset.showOriginal)
        assertTrue(reset.visible)
    }


    @Test fun overlayPreferencesUseDataStoreWithoutOverwritingLanguagesOrOtherUiPreferences() = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("hearth-overlay-preferences")
        val original = OverlaySettings(bounds = OverlayBounds(-900, 80, 850, 220), toolbarScale = 1.25f,
            shadeHoverArea = true, windowHeight = 220, maxLines = 4)
        val config = com.sal7one.hearth.core.PipelineConfig(sourceLanguage = "en", targetLanguage = "ar")
        try {
            val first = com.sal7one.hearth.desktop.data.DesktopSettingsStore(directory)
            try {
                first.load(); first.save(config)
                first.saveUi(mapOf("screen" to "overlay", "model-hardware" to "metal"))
                first.saveUi(overlayPrefs(original))
            } finally { first.closeAndJoin() }
            val reloaded = com.sal7one.hearth.desktop.data.DesktopSettingsStore(directory)
            try {
                reloaded.load()
                assertEquals(original, overlaySettingsFrom(reloaded.ui.value))
                assertEquals("metal", reloaded.ui.value["model-hardware"])
                assertEquals("overlay", reloaded.ui.value["screen"])
                assertEquals(config, reloaded.config.value)
            } finally { reloaded.closeAndJoin() }
        } finally {
            java.nio.file.Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete) }
        }
    }

    @Test fun nativeMacQueueRejectsAnAwtCallerBeforeEnteringNativeCode() {
        java.awt.EventQueue.invokeAndWait {
            assertFailsWith<IllegalStateException> {
                com.sal7one.hearth.desktop.platform.MacMainQueue.run { error("Must not reach AppKit") }
            }.also { assertTrue(it.message.orEmpty().contains("off AWT")) }
        }
    }

    @Test fun widthControlsPreserveManualPlacementInsteadOfJumpingBackToTheAnchor() {
        val original = OverlaySettings(bounds = OverlayBounds(-1600, 180, 1000, 200))
        val resized = overlayWithWidth(original, 0.5f, listOf(laptop, left)).bounds!!
        assertEquals(960, resized.width)
        assertEquals(-1100, resized.x + resized.width / 2)
        assertEquals(180, resized.y)
    }

    @Test fun hoverHeightChangesKeepTheBottomEdgeStable() {
        val base = OverlaySettings(bounds = OverlayBounds(30, 800, 1000, 180))
        val taller = overlayWithHeight(base, 360)
        assertEquals(620, taller.bounds?.y)
        assertEquals(360, taller.windowHeight)
        assertEquals(980, taller.bounds!!.y + taller.bounds.height)
        assertEquals(90, overlayWithHeight(base, -10).windowHeight)
        assertEquals(720, overlayWithHeight(base, 8000).windowHeight)
    }

    @Test fun duplicateHotkeyDeliveryCannotHideThenImmediatelyReshowTheWindow() {
        val gate = OverlayShortcutGate()
        assertTrue(gate.accept(OverlayCommand.TOGGLE, 1_000_000_000L))
        assertFalse(gate.accept(OverlayCommand.TOGGLE, 1_010_000_000L))
        assertTrue(gate.accept(OverlayCommand.TOGGLE, 1_500_000_000L))
        assertTrue(gate.accept(OverlayCommand.LOCK, 1_510_000_000L))
    }

    @Test fun hoverAreaAndToolbarPrefsRejectInvalidScales() {
        val settings = overlaySettingsFrom(mapOf("overlay-toolbar-scale" to "NaN", "overlay-height" to "-800"))
        assertEquals(1f, settings.toolbarScale)
        assertEquals(90, settings.windowHeight)
        assertEquals(90, anchoredOverlayBounds(settings, laptop).height)
    }

    @Test fun fontShortcutsStayReadableAndDoNotAlterPositionOrVisibility() {
        val base = OverlaySettings(bounds = OverlayBounds(10, 20, 800, 200))
        val large = (1..50).fold(base) { value, _ -> reduceOverlayCommand(value, OverlayCommand.BIGGER) }
        val small = (1..50).fold(large) { value, _ -> reduceOverlayCommand(value, OverlayCommand.SMALLER) }
        assertEquals(2f, large.textScale)
        assertEquals(0.65f, small.textScale)
        assertEquals(base.bounds, small.bounds)
        assertFalse(small.visible)
    }
}

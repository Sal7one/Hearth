package com.sal7one.hearth.desktop.ui.screens.overlay

import java.awt.event.KeyEvent

internal enum class OverlayCommand(val title: String, val key: Int, val macCode: Int) {
    TOGGLE("Show / hide captions", KeyEvent.VK_O, 31),
    LOCK("Toggle click-through", KeyEvent.VK_L, 37),
    BIGGER("Larger text", KeyEvent.VK_UP, 126),
    SMALLER("Smaller text", KeyEvent.VK_DOWN, 125),
    RESET("Reset window position", KeyEvent.VK_R, 15),
    ORIGINAL("Toggle original line", KeyEvent.VK_T, 17),
    STYLE("Cycle subtitle style", KeyEvent.VK_S, 1);

    fun shortcutLabel(mac: Boolean = System.getProperty("os.name").contains("Mac", true)): String {
        val name = when (this) { BIGGER -> "↑"; SMALLER -> "↓"; else -> KeyEvent.getKeyText(key) }
        return if (mac) "⌘ ⌥ $name" else "Ctrl Alt $name"
    }
}

internal fun overlayCommand(event: KeyEvent, mac: Boolean): OverlayCommand? {
    if (event.id != KeyEvent.KEY_PRESSED || event.isShiftDown || !event.isAltDown ||
        (if (mac) !event.isMetaDown || event.isControlDown else !event.isControlDown || event.isMetaDown)) return null
    return OverlayCommand.entries.firstOrNull { it.key == event.keyCode }
}

internal fun reduceOverlayCommand(settings: OverlaySettings, command: OverlayCommand): OverlaySettings = when (command) {
    OverlayCommand.TOGGLE -> settings.copy(visible = !settings.visible)
    OverlayCommand.LOCK -> settings.copy(clickThrough = !settings.clickThrough)
    OverlayCommand.BIGGER -> settings.copy(textScale = (settings.textScale + 0.1f).coerceAtMost(2f))
    OverlayCommand.SMALLER -> settings.copy(textScale = (settings.textScale - 0.1f).coerceAtLeast(0.65f))
    OverlayCommand.RESET -> settings.copy(bounds = null, pinnedDisplay = "auto", position = OverlayPosition.BOTTOM,
        widthFraction = 0.64f, clickThrough = false)
    OverlayCommand.ORIGINAL -> settings.copy(showOriginal = !settings.showOriginal)
    OverlayCommand.STYLE -> settings.copy(style = OverlayStyle.entries[(settings.style.ordinal + 1) % OverlayStyle.entries.size])
}

/** Avoid double toggles if the native and focused-app routes deliver the same press. */
internal class OverlayShortcutGate {
    private var previous: OverlayCommand? = null
    private var time = Long.MIN_VALUE
    fun accept(command: OverlayCommand, now: Long = System.nanoTime()): Boolean {
        if (command == previous && now - time in 0 until 120_000_000L) return false
        previous = command; time = now
        return true
    }
}

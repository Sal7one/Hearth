package com.sal7one.hearth.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sal7one.hearth.desktop.platform.NativeOverlayWindow
import com.sal7one.hearth.desktop.ui.screens.overlay.*
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Explicit, isolated UI harness. No production preferences, engines, audio or network are opened. */
internal fun launchOverlayPreview(directory: Path) = application {
    var settings by remember {
        val saved = directory.resolve("preview.properties")
        val prefs = if (Files.isRegularFile(saved) && Files.size(saved) <= 32_768) {
            val values = Properties().apply { Files.newInputStream(saved).use(::load) }
            overlaySettingsFrom(values.stringPropertyNames().associateWith { values.getProperty(it) }).copy(visible = true)
        } else OverlaySettings(visible = true)
        mutableStateOf(prefs.copy(shortcutsEnabled = false))
    }
    var issue by remember { mutableStateOf<String?>(null) }
    var nativeApplied by remember { mutableStateOf<Boolean?>(null) }
    // A test window must not take the real app's global shortcut registrations.
    val shortcuts = OverlayShortcutBinding(false) { settings = reduceOverlayCommand(settings, it) }
    val overlay = OverlayState(settings = settings, targetRtl = true, translationEnabled = true,
        caption = OverlayCaptionView("صوتك، بلغتك. حرّك الترجمة إلى المكان الذي يناسبك.",
            if (settings.showOriginal) "Your audio. Your language. Captions that fit your screen." else null, partial = false),
        shortcutStatus = "Preview: focused-window shortcuts only", nativeControlsAvailable = NativeOverlayWindow.supported,
        controlIssue = issue, preview = true)
    LaunchedEffect(settings, shortcuts, nativeApplied, issue) {
        delay(400)
        withContext(Dispatchers.IO) {
            Files.createDirectories(directory)
            val values = Properties().apply {
                putAll(overlayPrefs(settings)); setProperty("visible", settings.visible.toString())
                setProperty("shortcut-status", shortcuts); setProperty("native-click-through", nativeApplied.toString())
                setProperty("native-error", issue.orEmpty())
            }
            Files.newOutputStream(directory.resolve("preview.properties")).use { values.store(it, "Isolated overlay preview") }
        }
    }
    var settingsWindow by remember { mutableStateOf<androidx.compose.ui.awt.ComposeWindow?>(null) }
    Window(title = "Hearth overlay preview", icon = painterResource("hearth.png"),
        state = rememberWindowState(width = 1200.dp, height = 900.dp),
        onCloseRequest = ::exitApplication) {
        DisposableEffect(window) { settingsWindow = window; onDispose { settingsWindow = null } }
        HearthTheme {
            OverlayScreen(overlay, OverlayActions(onChange = { settings = it },
                onToggleVisible = { settings = settings.copy(visible = it) },
                onReset = { settings = reduceOverlayCommand(settings, OverlayCommand.RESET) }))
        }
    }
    if (settings.visible) FloatingCaptionWindow(overlay, dark = true, preset = AccentPreset.SKY,
        onClose = { settings = settings.copy(visible = false) }, onChange = { settings = it },
        onOpenSettings = { settingsWindow?.apply { toFront(); requestFocus() } },
        onNativeError = { issue = it; settings = settings.copy(clickThrough = false) },
        onNativeApplied = { nativeApplied = it; issue = null })
}

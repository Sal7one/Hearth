package com.sal7one.hearth.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sal7one.hearth.desktop.DesktopModelController
import com.sal7one.hearth.desktop.DesktopWorkspaceController
import com.sal7one.hearth.desktop.ui.screens.home.HomeScreen
import com.sal7one.hearth.desktop.ui.screens.home.homeState
import com.sal7one.hearth.desktop.ui.shell.AppShell
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.HearthTheme

/**
 * The application root: navigation, appearance and the mapping from controller
 * state to each screen's view state. Screens never talk to a controller directly,
 * so a screen can be rendered from a fixed state for review or testing.
 */
@Composable
internal fun HearthDesktopRoot(
    controller: DesktopWorkspaceController,
    models: DesktopModelController,
    initialUi: Map<String, String>,
    saveUi: (Map<String, String>) -> Unit,
    closing: Boolean,
) {
    var screen by remember { mutableStateOf(DesktopScreen.fromId(initialUi["screen"] ?: "home")) }
    var dark by remember { mutableStateOf(initialUi["theme"] != "light") }
    var preset by remember {
        mutableStateOf(AccentPreset.entries.firstOrNull { it.name == initialUi["palette"] } ?: AccentPreset.INK)
    }

    val config by controller.config.collectAsState()
    val workspace by controller.state.collectAsState()
    val library by models.state.collectAsState()

    val installed = remember(library.refreshing) {
        runCatching {
            models.library.catalog.count { models.library.installedSelection(it.id) != null }
        }.getOrDefault(0)
    }
    val verified = remember(library.refreshing) {
        runCatching {
            models.library.catalog.count { models.library.installedSelection(it.id) != null }
        }.getOrDefault(0)
    }

    val home = homeState(
        config = config,
        capabilities = controller.capabilities,
        installedModels = installed,
        verifiedModels = verified,
        statusText = when {
            closing -> "Closing"
            workspace.error != null -> "Needs attention"
            workspace.active -> "Listening"
            else -> if (installed > 0) "Ready" else "Setup needed"
        },
        statusLive = workspace.active && !closing,
    )

    fun navigate(destination: DesktopScreen) {
        if (screen != destination) {
            screen = destination
            saveUi(mapOf("screen" to destination.id))
        }
    }

    HearthTheme(preset = preset, dark = dark, reduceMotion = initialUi["reduce-motion"] == "true") {
        AppShell(
            current = screen,
            status = home.statusText,
            statusLive = home.statusLive,
            dark = dark,
            onToggleTheme = {
                dark = !dark
                saveUi(mapOf("theme" to if (dark) "dark" else "light"))
            },
            onNavigate = ::navigate,
        ) {
            when (screen) {
                DesktopScreen.HOME -> HomeScreen(
                    state = home,
                    onStart = { navigate(DesktopScreen.CAPTIONS) },
                    onSwapLanguages = {
                        controller.updateConfig {
                            it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
                        }
                    },
                    onOpen = ::navigate,
                    onOpenWorkspace = { navigate(DesktopScreen.CAPTIONS) },
                )
                else -> NotPortedScreen(
                    screen = screen,
                    onBack = { navigate(DesktopScreen.HOME) },
                )
            }
        }
    }
}

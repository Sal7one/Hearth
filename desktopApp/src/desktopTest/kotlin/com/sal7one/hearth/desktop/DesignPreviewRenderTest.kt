package com.sal7one.hearth.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.screens.home.HomeScreen
import com.sal7one.hearth.desktop.ui.screens.home.HomeState
import com.sal7one.hearth.desktop.ui.shell.AppShell
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Opt-in design QA: rasterises the real composables with a fixed state and writes
 * PNGs for review. It runs only when HEARTH_PREVIEW_DIR is set, never in a normal
 * test run, and it never touches a model, the microphone or a provider.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesignPreviewRenderTest {

    private val output: Path? = System.getenv("HEARTH_PREVIEW_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

    private val homeState = HomeState(
        sourceLanguage = "en",
        targetLanguage = "ar",
        sourceLabel = "English",
        targetLabel = "العربية",
        speechEngine = "Whisper Large v3",
        translationEngine = "EuroLLM 1.7B",
        voiceEngine = null,
        installedModels = 4,
        verifiedModels = 4,
        recognitionReady = true,
        statusText = "3 engines ready",
        statusLive = false,
    )

    @Test
    fun renderHome() = render("home") { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.HOME,
                    status = "3 engines ready",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    HomeScreen(
                        state = homeState,
                        onStart = {},
                        onSwapLanguages = {},
                        onOpen = {},
                        onOpenWorkspace = {},
                    )
                }
            }
        }
    }

    private fun render(name: String, width: Int = 1440, height: Int = 1500, content: (Int, Int) -> ImageComposeScene) {
        val dir = output ?: return
        Files.createDirectories(dir)
        val scene = content(width, height)
        try {
            repeat(4) { scene.render(System.nanoTime()).close() }
            scene.render(System.nanoTime()).use { image ->
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
                Files.write(dir.resolve("$name.png"), data.bytes)
            }
        } finally {
            scene.close()
        }
    }
}

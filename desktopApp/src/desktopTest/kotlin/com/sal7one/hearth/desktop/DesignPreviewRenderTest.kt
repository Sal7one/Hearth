package com.sal7one.hearth.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionLine
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsActions
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsScreen
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsState
import com.sal7one.hearth.desktop.ui.screens.home.HomeScreen
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayActions
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayCaptionView
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayScreen
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlaySettings
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayState
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

    private val captionsState = CaptionsState(
        sourceLabel = "English",
        targetLabel = "العربية",
        targetLanguage = "ar",
        speechEngine = "Whisper Large v3",
        translationEngine = "EuroLLM 1.7B",
        speechReady = true,
        translationEnabled = true,
        voiceEnabled = false,
        active = true,
        microphone = true,
        showOriginal = true,
        showPendingOriginal = true,
        overlayVisible = false,
        deviceName = "MacBook Pro Microphone",
        lines = listOf(
            CaptionLine("a", 1, "00:04", "We should start with the part everyone already agreed on.",
                "لنبدأ بالجزء الذي اتفق عليه الجميع.", false),
            CaptionLine("b", 2, "00:11", "Then we can talk about the budget, and what it costs us next year.",
                "ثم يمكننا الحديث عن الميزانية، وعن تكلفتها في العام القادم.", false),
            CaptionLine("c", 3, "00:19", "I think the number is close enough to last quarter that nobody will object.",
                "أعتقد أن الرقم قريب بما يكفي من الربع الماضي، فلن يعترض أحد.", false),
            CaptionLine("d", 4, "00:27", "And after that, we still have to—", "وبعد ذلك، ما زال علينا أن—", true),
        ),
    )

    @Test
    fun renderCaptions() = render("captions", height = 900) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.CAPTIONS,
                    status = "Listening",
                    statusLive = true,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    CaptionsScreen(
                        state = captionsState,
                        actions = CaptionsActions(),
                        onOpenModels = {},
                        fixedClock = "00:42",
                    )
                }
            }
        }
    }

    @Test
    fun renderOverlay() = render("overlay", height = 1000) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.OVERLAY,
                    status = "Overlay on",
                    statusLive = true,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    OverlayScreen(
                        state = OverlayState(
                            settings = OverlaySettings(visible = true),
                            caption = OverlayCaptionView(
                                primary = "لنبدأ بالجزء الذي اتفق عليه الجميع.",
                                original = "We should start with the part everyone agreed on.",
                                partial = false,
                            ),
                            targetRtl = true,
                            translationEnabled = true,
                        ),
                        actions = OverlayActions(),
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

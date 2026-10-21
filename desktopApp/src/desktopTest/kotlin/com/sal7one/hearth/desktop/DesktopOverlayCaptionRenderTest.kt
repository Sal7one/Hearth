package com.sal7one.hearth.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.sal7one.hearth.desktop.ui.screens.overlay.CaptionLayer
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayCaptionView
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlaySettings
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayState
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayStyle
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Headless proof that the same CaptionLayer used by the floating window draws both
 * the original line and the translated line, in both brightness modes.
 *
 * The always-on-top [com.sal7one.hearth.desktop.ui.screens.overlay.FloatingCaptionWindow]
 * itself is deliberately NOT composed here: opening a real OS window remains an
 * owner-only screen check. This test only rasterises the shared caption content, and
 * it never touches a model, microphone, provider, network or display.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopOverlayCaptionRenderTest {

    private val original = "The museum opens at nine in the morning."
    private val translation = "يبدأ المتحف في التاسعة صباحًا."
    private val otherTranslation = "الجناح الشرقي يبقى مغلقًا حتى الظهر."

    @Test
    fun floatingCaptionLayerRendersOriginalAndTranslation() {
        val empty = render(null)
        val translatedOnly = render(caption(primary = translation, original = null))
        val translatedWithOriginal = render(caption(primary = translation, original = original))
        val partial = render(caption(primary = translation, original = original, partial = true))

        assertTrue(empty.isNotEmpty(), "The empty overlay produced no PNG")
        assertFalse(
            empty.contentEquals(translatedOnly),
            "The translated caption did not change the rendered overlay",
        )
        assertFalse(
            translatedOnly.contentEquals(translatedWithOriginal),
            "The original line was not drawn above the translation",
        )
        assertFalse(
            translatedWithOriginal.contentEquals(partial),
            "The partial marker was not drawn",
        )
    }

    @Test
    fun floatingCaptionLayerRendersTranslationAndBothBrightnesses() {
        val dark = render(caption(primary = translation, original = original, partial = true), dark = true)
        val light = render(caption(primary = translation, original = original, partial = true), dark = false)
        val other = render(caption(primary = otherTranslation, original = original, partial = true))

        assertFalse(
            dark.contentEquals(other),
            "The translated line is not part of the rendered overlay",
        )
        assertFalse(
            dark.contentEquals(light),
            "The caption layer did not follow the dark/light theme",
        )
        listOf("dark" to dark, "light" to light).forEach { (name, bytes) ->
            assertTrue(bytes.size > 1_000, "The $name overlay PNG is suspiciously small (${bytes.size} bytes)")
        }
    }

    private fun caption(
        primary: String,
        original: String?,
        partial: Boolean = false,
    ) = OverlayCaptionView(primary = primary, original = original, partial = partial)

    private fun render(caption: OverlayCaptionView?, dark: Boolean = true): ByteArray {
        val state = OverlayState(
            settings = OverlaySettings(style = OverlayStyle.BAR, backdropFraction = 1f, visible = true),
            caption = caption,
            targetRtl = true,
            translationEnabled = true,
        )
        val scene = ImageComposeScene(width = 900, height = 240, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = dark) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CaptionLayer(state = state, modifier = Modifier.fillMaxWidth(), scale = 1f, maxLines = 4)
                }
            }
        }
        return try {
            repeat(3) { scene.render(System.nanoTime()).close() }
            scene.render(System.nanoTime()).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encoding failed")
            }
        } finally {
            scene.close()
        }
    }
}

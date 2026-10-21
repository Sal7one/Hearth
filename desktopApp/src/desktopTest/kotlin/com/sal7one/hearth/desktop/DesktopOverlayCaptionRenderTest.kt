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
 * Headless proof that the same CaptionLayer used by the floating window draws live
 * original/translated text. It does not open the always-on-top window; that remains
 * an owner screen check.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesktopOverlayCaptionRenderTest {
    @Test
    fun floatingCaptionLayerRendersOriginalAndTranslation() {
        val empty = render(null)
        val live = render(
            OverlayCaptionView(
                primary = "يبدأ المتحف في التاسعة صباحًا.",
                original = "The museum opens at nine in the morning.",
                partial = false,
            ),
        )
        assertFalse(empty.contentEquals(live), "The live caption did not change the rendered overlay")
        assertTrue(live.size > empty.size, "The rendered overlay did not grow with caption text")
    }

    private fun render(caption: OverlayCaptionView?): ByteArray {
        val state = OverlayState(
            settings = OverlaySettings(style = OverlayStyle.BAR, backdropFraction = 1f, visible = true),
            caption = caption,
            targetRtl = true,
            translationEnabled = true,
        )
        val scene = ImageComposeScene(width = 900, height = 240, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
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

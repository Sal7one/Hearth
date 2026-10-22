package com.sal7one.hearth.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceCard
import com.sal7one.hearth.desktop.ui.screens.settings.ChoiceOption
import com.sal7one.hearth.desktop.ui.screens.settings.SettingsChoiceGrid
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class DesktopEngineChoicesLayoutTest {
    @Test fun elevenActualNamedCardsWrapAtReadableWidthsInsteadOfShrinkingIntoOneRow() {
        val names = listOf("Whisper", "Qwen3-ASR 0.6B", "TranscribePsy 250M", "Nemotron 3.5 ASR",
            "Moonshine", "Vosk (Kaldi)", "Omnilingual CTC 300M", "Phonon 2", "MOSS-TTS Nano 100M",
            "Chatterbox Multilingual V3", "Supertonic 3")
        val options = names.mapIndexed { index, name -> ChoiceOption("engine-$index", "L", name,
            "Native runtime · CPU", "The selected model runs locally on this computer.", true) }
        for (width in listOf(220, 500, 740, 1_400)) {
            val bounds = mutableMapOf<String, Rect>()
            val scene = ImageComposeScene(width = width, height = 3_000, density = Density(1f)) {
                HearthTheme {
                    SettingsChoiceGrid(options, Modifier.fillMaxWidth()) { option, modifier ->
                        ChoiceCard(option, option.id == "engine-0", {}, modifier.onGloballyPositioned {
                            bounds[option.id] = it.boundsInRoot()
                        })
                    }
                }
            }
            try {
                repeat(3) { scene.render(System.nanoTime()).close() }
                assertEquals(options.size, bounds.size, "Every engine must remain laid out at width $width")
                val columns = bounds.values.map { it.left.toInt() }.distinct().size
                assertTrue(columns in 1..3, "At most three engine columns, found $columns at $width")
                assertTrue(bounds.values.map { it.top.toInt() }.distinct().size > 1, "Eleven engines must wrap")
                for ((id, rectangle) in bounds) {
                    assertTrue(rectangle.width >= minOf(240, width) - 1f, "$id collapsed to ${rectangle.width}px at width $width")
                    assertTrue(rectangle.left >= -1f && rectangle.right <= width + 1f, "$id escaped the viewport")
                    assertTrue(rectangle.height >= 72f, "$id lost its name/runtime/description content")
                }
                val widths = bounds.values.map { it.width }
                assertTrue(widths.max() - widths.min() <= 1f, "The final row must keep the same readable card widths")
            } finally { scene.close() }
        }
    }
}

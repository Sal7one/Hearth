package com.sal7one.transiber.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class MinimalPaletteTest {
    @Test fun minimalThemesKeepReadableTextInBothBrightnessModes() {
        for (preset in listOf(AccentPreset.CLEAN, AccentPreset.INK, AccentPreset.SKY)) {
            val p = accentPalette(preset)
            for ((foreground, background) in listOf(
                p.lightOnGround to p.lightSurface, p.lightOnGround to p.lightBackground,
                p.lightOnSurfaceVariant to p.lightSurface, p.onPrimary to p.primary,
                p.onPrimaryContainer to p.primaryContainer,
                p.darkOnGround to p.darkSurface, p.darkOnGround to p.darkBackground,
                p.darkOnSurfaceVariant to p.darkSurface,
            )) assertTrue("${preset.name}: text contrast below 4.5:1", contrast(foreground, background) >= 4.5f)
        }
        assertEquals(Color.Black, accentPalette(AccentPreset.INK).darkBackground)
    }

    @Test fun savedClassicThemesRemainAvailableAndDefaultsAreSimple() {
        assertEquals(AccentPreset.OCEAN, AccentPreset.fromStored("OCEAN"))
        assertEquals(AccentPreset.CLEAN, AccentPreset.fromStored(null))
        assertEquals(AccentPreset.CLEAN, AccentPreset.fromStored("unknown"))
        assertEquals(NavigationLayout.SIMPLE, NavigationLayout.fromStored(null))
        assertEquals(NavigationLayout.TABS, NavigationLayout.fromStored("TABS"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
    }

    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance(); val y = b.luminance()
        return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
    }
}

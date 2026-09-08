package com.sal7one.transiber.caption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Palette-math contract for the overlay window theming: the surface alpha is
 * the theme's own alpha SCALED by the user's opacity (never replaced), clamped
 * to [0,1], and every theme declares distinct tokens.
 */
class OverlayPaletteTest {

    @Test
    fun surfaceAlphaScalesAndClamps() {
        assertEquals(0.7f, effectiveSurfaceAlpha(1.0f, 70), 1e-6f)
        assertEquals(0.49f, effectiveSurfaceAlpha(0.7f, 70), 1e-6f)
        assertEquals(0f, effectiveSurfaceAlpha(0.5f, 0), 1e-6f)
        assertEquals(1f, effectiveSurfaceAlpha(1.0f, 100), 1e-6f)
        assertEquals(1f, effectiveSurfaceAlpha(0.9f, 200), 1e-6f)  // clamped, never > 1
        assertEquals(0f, effectiveSurfaceAlpha(0.9f, -50), 1e-6f)  // clamped, never < 0
    }

    @Test
    fun everyThemeHasDistinctTokenSets() {
        val labels = CaptionTheme.entries.map { it.label }
        assertEquals(4, labels.distinct().size)
        assertTrue(labels.contains("Dark"))
        assertTrue(labels.contains("High contrast"))
        assertTrue(labels.contains("Light"))
        assertTrue(labels.contains("Subtle"))
    }

    @Test
    fun presetsNeverTouchTheme() {
        // The user-owned theme must survive preset application (shape-only).
        for (theme in CaptionTheme.entries) {
            for (preset in CaptionPresets.ALL) {
                val base = CaptionOverlayConfig(theme = theme)
                assertEquals(theme, preset.applyTo(base).theme)
            }
        }
    }
}

package com.sal7one.transiber.reading

import org.junit.Assert.*
import org.junit.Test

class ReadingOverlaySettingsTest {
    @Test fun newReadersWaitForAnExplicitTapAndUseBoundedAutomaticTiming() {
        val settings = ReadingOverlaySettings.from(emptyMap<String, Any>())
        assertEquals("manual", settings.mode)
        assertEquals(900, settings.settleMs)
        assertEquals(500, settings.scanMs)
        assertEquals(4_000, settings.refreshCooldownMs)
        assertTrue(settings.passThrough)
        assertTrue(settings.translationVisible)
    }

    @Test fun legacyChoicesMigrateWithoutResettingManualOrPageMode() {
        for (mode in listOf("manual", "page", "distance", "scrolls")) {
            val settings = ReadingOverlaySettings.from(mapOf("mode" to mode, "settle" to 700L,
                "scan" to 750L, "pass-through" to false, "textSize" to 28f))
            assertEquals(if (mode == "manual") "manual" else "page", settings.mode)
            assertEquals(700, settings.settleMs); assertEquals(750, settings.scanMs)
            assertFalse(settings.passThrough); assertEquals(28f, settings.textSizeSp)
        }
    }

    @Test fun invalidPreferencesCannotCreateRefreshLoopsOrUnreadableText() {
        val settings = ReadingOverlaySettings.from(mapOf("mode" to "unknown", "settle" to -1L,
            "scan" to -1, "refresh-cooldown" to 0L, "change-sensitivity" to "unknown",
            "textSize" to Float.POSITIVE_INFINITY))
        assertEquals("manual", settings.mode)
        assertEquals(500, settings.settleMs); assertEquals(250, settings.scanMs)
        assertEquals(2_000, settings.refreshCooldownMs)
        assertEquals("balanced", settings.changeSensitivity); assertEquals(20f, settings.textSizeSp)
    }
}

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
        assertFalse(settings.replaceText)
        assertEquals(100, settings.boxWidthPercent); assertEquals(100, settings.boxHeightPercent)
        assertEquals(95, settings.boxOpacity)
    }

    @Test fun savedBoxGeometryIsBoundedAndColoursKeepTheirOwnOpaqueRgb() {
        val settings = ReadingOverlaySettings.from(mapOf("replace-text" to true, "box-width-percent" to -30,
            "box-height-percent" to 600, "box-opacity" to 0,
            "box-background-argb" to 0x123456, "box-text-argb" to 0x00112233))
        assertTrue(settings.replaceText)
        assertEquals(50, settings.boxWidthPercent); assertEquals(300, settings.boxHeightPercent)
        assertEquals(10, settings.boxOpacity)
        assertEquals(0xff123456.toInt(), settings.boxBackgroundArgb)
        assertEquals(0xff112233.toInt(), settings.boxTextArgb)
        val opposite = settings.copy(boxWidthPercent = 900, boxHeightPercent = -1, boxOpacity = 200).bounded()
        assertEquals(200, opposite.boxWidthPercent); assertEquals(50, opposite.boxHeightPercent); assertEquals(100, opposite.boxOpacity)
    }

    @Test fun presetsKeepReplacementGeometryAndCapturePreferences() {
        val original = ReadingOverlaySettings(mode = "page", replaceText = true, boxWidthPercent = 85,
            boxHeightPercent = 165, textSizeSp = 29f, passThrough = false, compactHandle = false,
            refreshCooldownMs = 9000, showOriginal = true)
        ReadingBoxPreset.entries.forEach { preset ->
            val changed = preset.applyTo(original)
            assertTrue(preset.matches(changed))
            assertEquals(original.copy(boxBackgroundArgb = preset.background, boxTextArgb = preset.foreground,
                boxOpacity = preset.opacity), changed)
        }
    }

    @Test fun anAppearanceSnapshotCannotRevertNewerCaptureAndTextSettings() {
        val current = ReadingOverlaySettings(mode = "page", passThrough = false, showOriginal = true,
            textSizeSp = 31f, settleMs = 1200, compactHandle = false)
        val oldSnapshot = ReadingBoxPreset.WARM.applyTo(ReadingOverlaySettings()).copy(replaceText = true,
            boxWidthPercent = 125, boxHeightPercent = 150)
        val result = current.withReadingAppearance(oldSnapshot)
        assertEquals(current.copy(replaceText = true, boxWidthPercent = 125, boxHeightPercent = 150,
            boxBackgroundArgb = oldSnapshot.boxBackgroundArgb, boxTextArgb = oldSnapshot.boxTextArgb,
            boxOpacity = oldSnapshot.boxOpacity), result)
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

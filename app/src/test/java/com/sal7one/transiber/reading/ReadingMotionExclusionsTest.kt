package com.sal7one.transiber.reading

import com.sal7one.transiber.ocr.PixelCrop
import org.junit.Assert.*
import org.junit.Test

class ReadingMotionExclusionsTest {
    private val ticker = PixelCrop(20, 1800, 1060, 1920)
    private fun area(crop: PixelCrop = ticker) = checkNotNull(ReadingMotionExclusion.fromCrop(crop, 1080, 2400))

    @Test fun savedNormalizedAreaRestoresExactCaptureCoordinatesAfterSerialization() {
        val saved = listOf(area())
        val restored = ReadingMotionExclusions.decode(ReadingMotionExclusions.encode(saved))
        assertEquals(saved, restored)
        assertEquals(ticker, restored.single().cropFor(1080, 2400))
        assertEquals(restored, ReadingOverlaySettings.from(mapOf("motion-exclusions" to ReadingMotionExclusions.encode(saved))).motionExclusions)
    }

    @Test fun rotationResolutionAndDifferentCaptureGeometryNeverApplyTheAreaToOtherContent() {
        val saved = area()
        assertNull(saved.cropFor(2400, 1080))
        assertNull(saved.cropFor(720, 1600))
        assertNull(saved.cropFor(1080, 2312))
        assertEquals(ticker, saved.cropFor(1080, 2400))
    }

    @Test fun invalidAndTinySelectionsDoNotCreateSavedMasks() {
        for (crop in listOf(PixelCrop(-1, 20, 50, 100), PixelCrop(20, -1, 50, 100),
            PixelCrop(20, 20, 1081, 100), PixelCrop(20, 20, 100, 2401),
            PixelCrop(20, 20, 27, 100), PixelCrop(20, 20, 100, 27), PixelCrop(100, 100, 20, 20))) {
            assertNull(ReadingMotionExclusion.fromCrop(crop, 1080, 2400))
        }
        assertNull(ReadingMotionExclusion.fromCrop(ticker, 0, 2400))
        assertFalse(area().copy(left = Float.NaN).valid())
        assertFalse(area().copy(top = Float.NEGATIVE_INFINITY).valid())
        assertFalse(area().copy(right = 1.1f).valid())
    }

    @Test fun areasAreBoundedDeduplicatedAndMalformedVersionsDoNotMaskTheScreen() {
        val areas = (0..6).map { area(PixelCrop(10, 100 + it * 100, 800, 160 + it * 100)) }
        val bounded = ReadingOverlaySettings(motionExclusions = listOf(areas.first(), areas.first()) + areas.drop(1) + area().copy(left = Float.NaN)).bounded()
        assertEquals(areas.take(MAX_MOTION_EXCLUSIONS), bounded.motionExclusions)
        for (raw in listOf("", "broken", "[]", "{\"version\":2,\"areas\":[]}", "x".repeat(4097),
            "{\"version\":1,\"areas\":[{\"left\":0,\"top\":0,\"right\":1,\"bottom\":1}]}")) {
            assertTrue(ReadingMotionExclusions.decode(raw).isEmpty())
        }
    }

    @Test fun absentLegacyPreferenceKeepsEveryOtherSettingAndStartsWithNoIgnoredAreas() {
        val settings = ReadingOverlaySettings.from(mapOf("mode" to "page", "textSize" to 29f))
        assertEquals("page", settings.mode)
        assertEquals(29f, settings.textSizeSp)
        assertTrue(settings.motionExclusions.isEmpty())
    }
}

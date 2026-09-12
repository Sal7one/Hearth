package com.sal7one.transiber.caption

import org.junit.Assert.*
import org.junit.Test

class CaptionDrainPolicyTest {
    @Test fun silentFinalOnlyRecognizerStillGetsTimeToFinish() {
        assertTrue(CaptionDrainPolicy.shouldWait(10_000, 10_000, 0, false, false, 3_000, 15_000))
        assertTrue(CaptionDrainPolicy.shouldWait(14_000, 10_000, 0, true, false, 3_000, 15_000))
    }
    @Test fun finalTranslationKeepsDrainAliveButCannotWaitForever() {
        assertTrue(CaptionDrainPolicy.shouldWait(14_000, 10_000, 0, false, true, 3_000, 15_000))
        assertFalse(CaptionDrainPolicy.shouldWait(25_000, 10_000, 24_999, true, true, 3_000, 15_000))
        assertFalse(CaptionDrainPolicy.shouldWait(14_000, 10_000, 10_000, false, false, 3_000, 15_000))
        assertTrue(CaptionDrainPolicy.shouldWait(14_000, 10_000, 13_000, false, false, 3_000, 15_000))
    }
}

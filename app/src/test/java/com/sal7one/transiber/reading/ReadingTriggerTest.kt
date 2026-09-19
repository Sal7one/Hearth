package com.sal7one.transiber.reading

import org.junit.Assert.*
import org.junit.Test

class ReadingTriggerTest {
    @Test fun movementMustSettleAndRateLimit() {
        val p=ReadingTrigger().apply{mode="page";settleMs=500;intervalMs=2000}
        p.movement(1000);p.movement(1400)
        assertFalse(p.ready(1800));assertTrue(p.ready(1900));p.accepted(1900)
        p.movement(2200);assertFalse(p.ready(2800));assertTrue(p.ready(3900))
    }
    @Test fun retiredScrollModesUseSettledPageChanges() {
        for(saved in listOf("distance", "scrolls")) {
            val p=ReadingTrigger().apply {mode=ReadingTrigger.supportedMode(saved);settleMs=700}
            p.movement(1000)
            assertFalse(p.ready(1699));assertTrue(p.ready(1700))
            p.accepted(1700);assertFalse(p.ready(9000))
        }
    }
    @Test fun unknownOrMissingModeCannotEnableAutomaticCapture() {
        for(saved in listOf(null, "", "manual", "unexpected")) {
            val p=ReadingTrigger().apply {mode=ReadingTrigger.supportedMode(saved)}
            p.movement(1000);assertFalse(p.ready(9000))
        }
    }
    @Test fun manualNeverAutoStarts() {
        val p=ReadingTrigger();p.movement(1000);assertFalse(p.ready(9000))
    }
    @Test fun resetCancelsPendingMovement() {
        val p=ReadingTrigger().apply{mode="page"};p.movement(1000);p.reset();assertFalse(p.ready(9000))
    }
    @Test fun acceptedPageDoesNotRepeat() {
        val p=ReadingTrigger().apply{mode="page"};p.movement(1000);assertTrue(p.ready(9000));p.accepted(9000);assertFalse(p.ready(15000))
    }
}

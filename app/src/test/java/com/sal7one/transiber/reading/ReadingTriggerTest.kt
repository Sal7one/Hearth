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
    @Test fun oppositeScrollDoesNotCountJitterAsDistance() {
        val p=ReadingTrigger().apply{mode="distance";screenDistance=.75f;settleMs=500}
        p.scroll(500,1000,1000);p.scroll(-300,1000,1100)
        assertFalse(p.ready(1700));p.scroll(-500,1000,1800);assertTrue(p.ready(2400))
    }
    @Test fun scrollBurstsAreNotIndividualEvents() {
        val p=ReadingTrigger().apply{mode="scrolls";scrollBursts=2;settleMs=500}
        p.scroll(40,1000,1000);p.scroll(60,1000,1100);p.scroll(50,1000,1200)
        assertFalse(p.ready(1900));p.scroll(30,1000,2000);assertTrue(p.ready(2600))
    }
    @Test fun impossibleDeltaCannotTriggerDistance() {
        val p=ReadingTrigger().apply{mode="distance"}
        p.scroll(Int.MIN_VALUE,1000,1000);assertFalse(p.ready(5000))
    }
    @Test fun manualNeverAutoStarts() {
        val p=ReadingTrigger();p.movement(1000);assertFalse(p.ready(9000))
    }
    @Test fun newReaderResetsMovement() {
        val p=ReadingTrigger().apply{mode="page"};p.movement(1000);p.reset();assertFalse(p.ready(9000))
    }
    @Test fun acceptedPageDoesNotRepeat() {
        val p=ReadingTrigger().apply{mode="page"};p.movement(1000);assertTrue(p.ready(9000));p.accepted(9000);assertFalse(p.ready(15000))
    }
    @Test fun imageChangeIgnoresTinyNoiseButFindsNewText() {
        val page=IntArray(1000){255}
        assertFalse(PageDifference.changed(page,IntArray(1000){250}))
        assertFalse(PageDifference.changed(page,page.copyOf().apply{this[2]=0}))
        assertTrue(PageDifference.changed(page,page.copyOf().apply{for(i in 100..250)this[i]=30}))
        assertTrue(PageDifference.changed(null,page))
    }
}

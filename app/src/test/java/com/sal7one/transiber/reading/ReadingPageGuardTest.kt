package com.sal7one.transiber.reading

import org.junit.Assert.*
import org.junit.Test

class ReadingPageGuardTest {
    private fun page()=IntArray(32*48) {if(it%32 in 5..25 && it/32 in 12..30)40 else 240}

    @Test fun completedCleanPageIsNotTranslatedAgainAfterOverlayTransitions() {
        val guard=ReadingPageGuard()
        val first=checkNotNull(guard.begin(page()))
        assertTrue(guard.complete(first))
        guard.cancel() // Scrolling suspicion or closing the controls resets in-flight work only.
        assertNull(guard.begin(page()))
    }

    @Test fun harmlessCaptureNoiseAndCaretDoNotStartExpensiveWork() {
        val guard=ReadingPageGuard()
        guard.complete(checkNotNull(guard.begin(page())))
        val noise=page().apply {indices.forEach {this[it]+=if(it%2==0)1 else -1};this[15]-=80}
        assertNull(guard.begin(noise))
    }

    @Test fun realTextChangeIsNotHiddenByMostlyUnchangedWhiteBackground() {
        val guard=ReadingPageGuard()
        guard.complete(checkNotNull(guard.begin(page())))
        val next=page().apply {for(x in 10..22)this[24*32+x]=240}
        assertNotNull(guard.begin(next))
    }

    @Test fun manualRefreshAlwaysBypassesCompletedPageAndSupersedesPreviousWork() {
        val guard=ReadingPageGuard()
        val first=checkNotNull(guard.begin(page()))
        assertNull(guard.begin(page()))
        val manual=checkNotNull(guard.begin(page(),manual=true))
        assertFalse(guard.isCurrent(first));assertFalse(guard.complete(first))
        assertTrue(guard.complete(manual))
        assertNotNull(guard.begin(page(),manual=true))
    }

    @Test fun cancelledWorkCannotPublishAndDoesNotPoisonRetryOfTheOriginalPage() {
        val guard=ReadingPageGuard()
        val first=checkNotNull(guard.begin(page()))
        guard.cancel()
        assertFalse(guard.isCurrent(first));assertFalse(guard.complete(first))
        val retry=checkNotNull(guard.begin(page()))
        assertTrue(guard.complete(retry))
    }

    @Test fun terminalErrorStampsTheAttemptSoAutomaticModeDoesNotLoopButRefreshCanRetry() {
        val guard=ReadingPageGuard()
        // Completion records a terminal attempt, including errors displayed by the service.
        assertTrue(guard.complete(checkNotNull(guard.begin(page()))))
        assertNull(guard.begin(page()))
        assertNotNull(guard.begin(page(),manual=true))
    }

    @Test fun settingsOrRegionChangeMakesTheSamePixelsEligibleAgain() {
        val guard=ReadingPageGuard()
        guard.complete(checkNotNull(guard.begin(page())))
        guard.reset()
        assertNotNull(guard.begin(page()))
    }

    @Test fun capturedSignaturesAreOwnedAndCannotBeChangedByCallerReuse() {
        val guard=ReadingPageGuard();val reused=page()
        val ticket=checkNotNull(guard.begin(reused));reused.fill(0)
        assertTrue(guard.complete(ticket))
        assertNull(guard.begin(page()))
        assertNotNull(guard.begin(reused))
    }

    @Test fun invalidOrDifferentSizedSignaturesAreHandledExplicitly() {
        val guard=ReadingPageGuard()
        assertThrows(IllegalArgumentException::class.java) {guard.begin(intArrayOf())}
        assertThrows(IllegalArgumentException::class.java) {guard.begin(intArrayOf(256))}
        guard.complete(checkNotNull(guard.begin(page())))
        assertNotNull(guard.begin(IntArray(100){240}))
    }
}

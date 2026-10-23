package com.sal7one.transiber.downloads

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DownloadWorkSlotsTest {
    @Test fun hashingDoesNotConsumeNetworkSlots() = runTest {
        val transfers = DownloadWorkSlots { 1 }
        val preparation = DownloadWorkSlots { 1 }
        val hashingStarted = CompletableDeferred<Unit>()
        val releaseHash = CompletableDeferred<Unit>()
        val secondTransfer = CompletableDeferred<Unit>()
        val first = launch {
            transfers.withSlot { delay(1) }
            preparation.withSlot { hashingStarted.complete(Unit); releaseHash.await() }
        }
        hashingStarted.await()
        val second = launch { transfers.withSlot { secondTransfer.complete(Unit) } }
        secondTransfer.await()
        assertFalse(first.isCompleted)
        releaseHash.complete(Unit)
        joinAll(first, second)
    }

    @Test fun changedLimitsAndCancellationReleaseCapacity() = runTest {
        var limit = 1
        val slots = DownloadWorkSlots { limit }
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { slots.withSlot { firstStarted.complete(Unit); awaitCancellation() } }
        firstStarted.await()
        val second = launch { slots.withSlot { secondStarted.complete(Unit); release.await() } }
        delay(200)
        assertFalse(secondStarted.isCompleted)
        limit = 2
        slots.refresh()
        secondStarted.await()
        // Reducing the limit does not cancel an in-flight request.
        limit = 1
        first.cancelAndJoin()
        assertTrue(second.isActive)
        val thirdStarted = CompletableDeferred<Unit>()
        val third = launch { slots.withSlot { thirdStarted.complete(Unit) } }
        delay(200)
        assertFalse(thirdStarted.isCompleted)
        release.complete(Unit)
        thirdStarted.await()
        joinAll(second, third)
    }

    @Test fun queuedHundredsOfFilesStayBounded() = runTest {
        val slots = DownloadWorkSlots { 4 }
        var active = 0
        var maximum = 0
        var finished = 0
        List(300) { launch {
            slots.withSlot {
                active++
                maximum = maxOf(maximum, active)
                delay(1)
                active--
                finished++
            }
        } }.joinAll()
        assertEquals(4, maximum)
        assertEquals(300, finished)
    }
}

package com.sal7one.transiber.ocr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OcrTranslationWorkTest {
    @Test fun olderProgressiveFinallyCannotFinishThePageWhileFinalTranslationWaits() = runTest {
        val work=OcrTranslationWork()
        val queue=Channel<OcrTranslationWork.Ticket>(Channel.CONFLATED)
        var revision=7L
        var processing=true
        var translating=true
        val old=checkNotNull(work.begin(revision){revision})
        val oldStarted=CompletableDeferred<Unit>()
        val oldFinished=CompletableDeferred<Unit>()
        val finalStarted=CompletableDeferred<Unit>()
        val finalFinished=CompletableDeferred<Unit>()
        val terminal=mutableListOf<Boolean>()
        val worker=launch {
            for(ticket in queue) {
                try {
                    if(ticket===old) {oldStarted.complete(Unit);oldFinished.await()}
                    else {finalStarted.complete(Unit);finalFinished.await()}
                } finally {
                    work.finish(ticket,{revision}) {
                        translating=false
                        terminal+=!processing && !translating
                    }
                }
            }
        }
        queue.send(old);oldStarted.await()
        // This is the controller's critical ordering: register final work first,
        // then expose processing=false, then queue final translation.
        val final=checkNotNull(work.begin(revision){revision})
        processing=false;translating=true;queue.send(final)
        oldFinished.complete(Unit);finalStarted.await()
        assertTrue(translating)
        assertTrue(terminal.isEmpty())
        finalFinished.complete(Unit);queue.close();worker.join()
        assertFalse(translating)
        assertEquals(listOf(true),terminal)
    }

    @Test fun supersededSamePageWorkCannotPublishPartialTextOrItsLateError() {
        val work=OcrTranslationWork();val revision=1L
        val first=checkNotNull(work.begin(revision){revision})
        val final=checkNotNull(work.begin(revision){revision})
        val visible=mutableListOf<String>()
        assertFalse(work.publish(first,{revision}) {visible+="Obsolete partial result"})
        assertFalse(work.publish(first,{revision}) {visible+="Obsolete translator failure"})
        assertTrue(work.publish(final,{revision}) {visible+="Final translation"})
        assertEquals(listOf("Final translation"),visible)
    }

    @Test fun invalidatingThePageBlocksLateCallbacksAndOldNativeRequests() {
        val work=OcrTranslationWork();var revision=1L
        val old=checkNotNull(work.begin(revision){revision})
        revision++;work.invalidate()
        var callbacks=0
        assertFalse(work.publish(old,{revision}) {callbacks++})
        assertFalse(work.finish(old,{revision}) {callbacks++})
        val next=checkNotNull(work.begin(revision){revision})
        assertNull(work.begin(1){revision}) // A late recognized() callback must not replace new work.
        assertTrue(work.isCurrent(next){revision})
        assertTrue(work.publish(next,{revision}) {callbacks++})
        assertEquals(1,callbacks)
    }

    @Test fun conflatedRequestsLeaveOnlyTheNewestTicketEligibleToComplete() = runTest {
        val work=OcrTranslationWork();val revision=1L
        val queue=Channel<OcrTranslationWork.Ticket>(Channel.CONFLATED)
        val tickets=List(20) {checkNotNull(work.begin(revision){revision}).also {queue.trySend(it)}}
        val latest=queue.receive()
        assertSame(tickets.last(),latest)
        for(ticket in tickets.dropLast(1))assertFalse(work.finish(ticket,{revision}) {fail("Discarded request completed the page")})
        assertTrue(work.finish(latest,{revision}) {})
        assertFalse(work.isCurrent(latest){revision})
        queue.close()
    }

    @Test fun currentErrorCanSurfaceAndCompleteExactlyOnce() {
        val work=OcrTranslationWork();val revision=1L
        val ticket=checkNotNull(work.begin(revision){revision})
        var error="";var finished=0
        assertTrue(work.publish(ticket,{revision}) {error="Actual provider error"})
        assertTrue(work.finish(ticket,{revision}) {finished++})
        assertFalse(work.finish(ticket,{revision}) {finished++})
        assertEquals("Actual provider error",error)
        assertEquals(1,finished)
    }

    @Test fun lateProducerCannotConflateAwayTheNewestQueuedPage() = runTest {
        val work=OcrTranslationWork();val revision=1L
        val queue=Channel<OcrTranslationWork.Ticket>(Channel.CONFLATED)
        val old=checkNotNull(work.begin(revision){revision})
        val latest=checkNotNull(work.begin(revision){revision})
        assertTrue(work.publish(latest,{revision}) {queue.trySend(latest)})
        assertFalse(work.publish(old,{revision}) {queue.trySend(old)})
        assertSame(latest,queue.receive())
        queue.close()
    }
}

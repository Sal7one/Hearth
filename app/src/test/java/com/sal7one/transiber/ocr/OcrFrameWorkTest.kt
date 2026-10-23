package com.sal7one.transiber.ocr

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class OcrFrameWorkTest {
    @Test fun oldNativeFailureDoesNotStopTheNewPageWaitingBehindIt() = runTest {
        val epoch=AtomicLong(1)
        val nativeStarted=CompletableDeferred<Unit>()
        val releaseNative=CompletableDeferred<Unit>()
        val visible=mutableListOf<String>()
        val worker=launch {
            for(frame in listOf(1L,2L)) {
                val result=OcrFrameWork.run(frame,epoch::get) {
                    if(frame==1L) {
                        nativeStarted.complete(Unit)
                        releaseNative.await()
                        throw IllegalStateException("Old native OCR frame failed")
                    }
                    "New page text"
                }
                if(result!=null)visible+=result
            }
        }
        nativeStarted.await();epoch.incrementAndGet();releaseNative.complete(Unit)
        worker.join()
        assertEquals(listOf("New page text"),visible)
    }

    @Test fun oldSuccessfulNativeFrameCannotOverwriteTheCurrentPage() = runTest {
        val epoch=AtomicLong(1)
        val result=OcrFrameWork.run(1,epoch::get) {
            epoch.incrementAndGet()
            "Obsolete words"
        }
        assertNull(result)
        assertEquals("Current words",OcrFrameWork.run(2,epoch::get) {"Current words"})
    }

    @Test fun currentNativeExceptionRetainsItsActualFailure() = runTest {
        val failure=IllegalStateException("OCR tensor shape mismatch")
        try {
            OcrFrameWork.run(1,{1}) {throw failure}
            fail("Current frame failure must be reported")
        } catch(actual: IllegalStateException) {assertSame(failure,actual)}
    }

    @Test fun currentNativeLinkageErrorIsReportedButAnObsoleteOneIsIgnored() = runTest {
        val failure=UnsatisfiedLinkError("Missing OCR symbol")
        try {
            OcrFrameWork.run(1,{1}) {throw failure}
            fail("Current native linkage failure must be reported")
        } catch(actual: UnsatisfiedLinkError) {assertSame(failure,actual)}
        assertNull(OcrFrameWork.run(1,{2}) {throw failure})
    }

    @Test fun cancellationIsNeverSuppressedAsAnObsoleteFrameError() = runTest {
        val cancelled=CancellationException("Reader stopped")
        try {
            OcrFrameWork.run(1,{2}) {throw cancelled}
            fail("Cancellation must propagate")
        } catch(actual: CancellationException) {assertSame(cancelled,actual)}
    }

    @Test fun cancelledWorkerCannotContinueAfterNonCancellableNativeFailure() = runTest {
        val nativeStarted=CompletableDeferred<Unit>()
        val releaseNative=CompletableDeferred<Unit>()
        var proceeded=false
        val worker=launch {
            OcrFrameWork.run(1,{2}) {
                withContext(NonCancellable) {
                    nativeStarted.complete(Unit)
                    releaseNative.await()
                    throw IllegalStateException("Failure after stop")
                }
            }
            proceeded=true
        }
        nativeStarted.await();worker.cancel();releaseNative.complete(Unit);worker.join()
        assertTrue(worker.isCancelled)
        assertFalse(proceeded)
    }

    @Test fun fatalVmErrorsAreNotConvertedIntoAnEmptyOrStaleSuccess() = runTest {
        val fatal=OutOfMemoryError("Native allocation failed")
        try {
            OcrFrameWork.run(1,{2}) {throw fatal}
            fail("Fatal VM failure must propagate")
        } catch(actual: OutOfMemoryError) {assertSame(fatal,actual)}
    }
}

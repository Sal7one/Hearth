package com.sal7one.transiber.notes

import com.sal7one.transiber.caption.CaptionOverlayConfig
import com.sal7one.transiber.caption.CaptionMode
import com.sal7one.transiber.caption.CaptionSource
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceNoteWorkTest {
    @Test fun drainCallbackFailureIsBoundedAndStillReleasesNativeOwner() = kotlinx.coroutines.test.runTest {
        var released = false
        val failure = finishVoiceNoteSpeech(drain = { /* A failed provider never invokes its callback. */ },
            release = { released = true })
        assertTrue(released)
        assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test fun immediateDrainFailureIsRetainedAndReleaseStillRuns() = kotlinx.coroutines.test.runTest {
        val actual = IllegalStateException("Provider CloseStream rejected")
        var released = false
        val failure = finishVoiceNoteSpeech(drain = { throw actual }, release = { released = true })
        assertTrue(released)
        // Coroutine stack-trace recovery may clone the exception across suspension.
        // Provider type/message and mandatory cleanup are the production contract.
        assertEquals(actual.javaClass, failure?.javaClass)
        assertEquals(actual.message, failure?.message)
    }

    @Test fun completedDrainIsSuccessfulOnlyAfterNativeReleaseFinishes() = kotlinx.coroutines.test.runTest {
        var released = false
        assertNull(finishVoiceNoteSpeech(drain = { it() }, release = { released = true }))
        assertTrue(released)
    }

    @Test fun blockingCaptureDoesNotStarveFinalTextPersistence() = runBlocking {
        val work = VoiceNoteWork()
        val capturing = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val recorder = async(work.capture) {
            capturing.countDown()
            check(stop.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue(capturing.await(2, TimeUnit.SECONDS))
            // A real blocking AudioRecord.read remains active while the final-text writer runs.
            val persisted = withTimeout(2_000) { async(work.metadata) { "saved final" }.await() }
            assertEquals("saved final", persisted)
            assertTrue(recorder.isActive)
        } finally { stop.countDown(); recorder.await() }
    }

    @Test fun noteConsentDoesNotDependOnOrMutateOverlayHistory() {
        val overlay = CaptionOverlayConfig(source = CaptionSource.PLAYBACK_CAPTURE, saveCaptionHistory = false,
            speakCaptions = true, paused = true, localTranslationEnabled = true)
        val note = overlay.forVoiceNote(translate = false)
        assertTrue(note.saveCaptionHistory)
        assertFalse(overlay.saveCaptionHistory)
        assertFalse(note.speakCaptions)
        assertFalse(note.paused)
        assertFalse(note.localTranslationEnabled)
        assertEquals(CaptionMode.CAPTIONS, note.mode)
        assertEquals(CaptionSource.MIC, note.source)
        assertTrue(overlay.localTranslationEnabled)
    }
}

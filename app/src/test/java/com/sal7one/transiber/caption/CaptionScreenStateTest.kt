package com.sal7one.transiber.caption

import com.sal7one.transiber.ui.state.ScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * captionScreenState conservative fold, per its contract: non-blank error →
 * Error first; actively-listening/recording statuses → Loading ("Listening…"
 * lives on State itself); idle/stopped with no content → Empty; anything else
 * → Ready over history+partial.
 */
class CaptionScreenStateTest {

    private fun state(
        status: CaptionEngineController.Status,
        error: String? = null,
        history: List<CaptionLine> = emptyList(),
        partial: String = "",
    ) = CaptionEngineController.State(
        status = status,
        history = history,
        partial = partial,
        error = error,
    )

    @Test
    fun nonBlankErrorBeatsLiveSession() {
        val screen = captionScreenState(
            state(CaptionEngineController.Status.RUNNING, error = "microphone lost", partial = "still flowing"),
        )
        assertTrue(screen is ScreenState.Error)
        assertEquals("microphone lost", (screen as ScreenState.Error).reason)
    }

    @Test
    fun errorStatusWithoutMessageGetsActionableFallback() {
        val screen = captionScreenState(state(CaptionEngineController.Status.ERROR))
        assertTrue(screen is ScreenState.Error)
        assertEquals("Caption engine failed", (screen as ScreenState.Error).reason)
    }

    @Test
    fun whitespaceOnlyErrorCountsAsAbsent() {
        val screen = captionScreenState(
            state(CaptionEngineController.Status.STOPPED, error = "   "),
        )
        assertEquals(ScreenState.Empty, screen)
    }

    @Test
    fun loadingRunningAndSilentCaptureAllMapToLoading() {
        val listening = listOf(
            CaptionEngineController.Status.LOADING_MODEL,
            CaptionEngineController.Status.RUNNING,
            CaptionEngineController.Status.CAPTURE_SILENT,
        )
        listening.forEach { status ->
            assertEquals(
                "$status must be shell Loading",
                ScreenState.Loading,
                captionScreenState(state(status, partial = "live words")),
            )
        }
    }

    @Test
    fun idleOrStoppedWithoutContentIsEmpty() {
        assertEquals(ScreenState.Empty, captionScreenState(state(CaptionEngineController.Status.IDLE)))
        assertEquals(ScreenState.Empty, captionScreenState(state(CaptionEngineController.Status.STOPPED)))
    }

    @Test
    fun stoppedWithResidualTranscriptStaysReadyWithSnapshot() {
        val lines = listOf(CaptionLine(original = "hello there", translation = null, id = 7L))
        val screen = captionScreenState(
            state(CaptionEngineController.Status.STOPPED, history = lines, partial = "tail…"),
        )
        assertTrue(screen is ScreenState.Ready)
        val payload = (screen as ScreenState.Ready).value
        assertEquals(lines, payload.history)
        assertEquals("tail…", payload.partial)
    }

    @Test
    fun readyPayloadKeepsTranslationCarryingLinesUntouched() {
        val lines = listOf(
            CaptionLine(original = "hi", translation = "مرحبا", id = 1L),
            CaptionLine(original = "bye", translation = null, id = 2L),
        )
        val screen = captionScreenState(
            state(CaptionEngineController.Status.IDLE, history = lines),
        )
        assertTrue(screen is ScreenState.Ready)
        assertEquals(lines, (screen as ScreenState.Ready).value.history)
    }
}

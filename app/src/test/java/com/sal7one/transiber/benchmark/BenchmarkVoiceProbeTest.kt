package com.sal7one.transiber.benchmark

import com.sal7one.hearth.tts.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BenchmarkVoiceProbeTest {
    @Test fun measuresGeneratedDurationWithoutPlaybackAndClosesTheRealSdkReader() = runBlocking {
        val engine = FixtureEngine()
        val reader = ArticleReader(engine)
        try {
            val measurement = BenchmarkVoiceProbe.measure(reader, "Hello", "en", "fixture")
            assertEquals(30.0, measurement.audioMs, .0001)
            assertTrue(measurement.firstAudioMs >= 0)
        } finally { reader.closeAndJoin() }
        assertTrue(engine.closed)
    }

    @Test fun emptyOrSilentOutputDoesNotBecomeSuccessfulSpeedResult() = runBlocking {
        for (silent in listOf(false, true)) {
            val reader = ArticleReader(FixtureEngine(empty = !silent, silent = silent))
            try {
                try { BenchmarkVoiceProbe.measure(reader, "Hello", "en", "fixture"); fail("Expected failed audio") }
                catch (error: IllegalStateException) { assertTrue(error.message.orEmpty(), "audio" in error.message.orEmpty() || "PCM" in error.message.orEmpty()) }
            } finally { reader.closeAndJoin() }
        }
    }

    @Test fun cancellationStopsSynthesisBeforeTheNextEngineCanStart() = runBlocking {
        val engine = FixtureEngine(infinite = true)
        val reader = ArticleReader(engine)
        val task = launch(Dispatchers.Default) { BenchmarkVoiceProbe.measure(reader, "Hello", "en", "fixture") }
        withTimeout(5000) { while (engine.frames == 0) yield() }
        task.cancelAndJoin()
        reader.closeAndJoin()
        assertTrue(engine.cancelled)
        assertTrue(engine.closed)
    }

    private class FixtureEngine(val empty: Boolean = false, val silent: Boolean = false, val infinite: Boolean = false) : SpeechEngine {
        override val id = "fixture"
        override val voices = listOf(SpeechVoice("fixture", "Fixture", setOf("en")))
        @Volatile var frames = 0
        @Volatile var cancelled = false
        @Volatile var closed = false
        override fun synthesize(text: String, language: String, voice: SpeechVoice, options: SpeechOptions,
            cancellation: SpeechCancellation, emit: (PcmAudio) -> Unit) {
            if (empty) return
            do {
                cancellation.throwIfCancelled()
                emit(PcmAudio(FloatArray(441) { if (silent) 0f else .1f }, 44100)); frames++
                if (!infinite) emit(PcmAudio(FloatArray(882) { if (silent) 0f else .1f }, 44100))
            } while (infinite)
        }
        override fun cancel() { cancelled = true }
        override fun close() { closed = true }
    }
}

package com.sal7one.transiber.caption

import android.app.Application
import com.sal7one.common_jni.core.RealtimeSessionState
import com.sal7one.common_jni.core.RealtimeSttSession
import com.sal7one.common_jni.engine.CancellableSttEngine
import com.sal7one.common_jni.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Emulated recorder only; never opens a device, model or native runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class RealtimeSttSessionContractTest {
    private class Engine : CancellableSttEngine {
        override val engineType = SttEngineType.ONNX
        override val isInitialized = true
        override val currentModel: ModelInfo? = null
        var resetResult = Result.success(Unit)
        var finalResult = Result.success(TranscriptResult.EMPTY)
        var pushResult = Result.success(Unit)
        var partialFailure: Exception? = null
        val releases = AtomicInteger()
        val cancels = AtomicInteger()
        val pushes = AtomicInteger()
        val finalizations = AtomicInteger()
        override suspend fun initialize(modelPath: String, config: SttConfig) = Result.success(Unit)
        override suspend fun pushAudioChunk(chunk: AudioChunk): Result<Unit> { pushes.incrementAndGet(); return pushResult }
        override suspend fun getPartialTranscript(): PartialTranscript? { partialFailure?.let { throw it }; return null }
        override suspend fun finalize(): Result<TranscriptResult> { finalizations.incrementAndGet(); return finalResult }
        override suspend fun reset() = resetResult
        override suspend fun release() { releases.incrementAndGet() }
        override fun cancel() { cancels.incrementAndGet() }
        override suspend fun transcribeBatch(samples: ShortArray, sampleRate: Int, onProgress: ((Float) -> Unit)?) =
            Result.success(TranscriptResult.EMPTY)
    }

    @Before fun silentEmulatedRecorder() {
        val source = object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean) = 0
        }
        ShadowAudioRecord.setSourceProvider { source }
    }
    @After fun clearRecorder() { ShadowAudioRecord.clearSource() }

    private fun feedWhenEnabled(enabled: AtomicBoolean) {
        val source = object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean) =
                if (enabled.get()) sizeInShorts else 0
        }
        ShadowAudioRecord.setSourceProvider { source }
    }

    @Test fun resetFailurePreventsListeningAndRetainsTheEngineError() = runBlocking {
        val engine = Engine()
        val failure = IllegalStateException("recognizer could not reset its context")
        engine.resetResult = Result.failure(failure)
        val session = RealtimeSttSession(engine)
        try {
            session.initialize("fixture").getOrThrow()
            try {
                session.start()
                fail("A failed reset started capture")
            } catch (actual: IllegalStateException) {
                // Coroutine stack recovery can copy exceptions across dispatchers.
                assertEquals(failure.message, actual.message)
            }
            assertEquals(RealtimeSessionState.ERROR, session.state.value)
        } finally { session.release() }
        assertEquals(1, engine.releases.get())
    }

    @Test fun finalizationFailureCannotPublishReadyState() = runBlocking {
        val engine = Engine()
        val failure = IllegalStateException("decoder failed while finalizing")
        engine.finalResult = Result.failure(failure)
        val session = RealtimeSttSession(engine)
        try {
            session.initialize("fixture").getOrThrow()
            session.start()
            val result = session.stop()
            assertSame(failure, result.exceptionOrNull())
            assertEquals(RealtimeSessionState.ERROR, session.state.value)
            assertEquals(0, engine.cancels.get()) // A source-end flush keeps the finalization path.
        } finally { session.release() }
        assertEquals(1, engine.releases.get())
    }

    @Test fun releaseStillClosesItsEngineIfCancellationResetFails() = runBlocking {
        val engine = Engine()
        val unrelated = Engine()
        val failure = IllegalStateException("reset rejected after inference cancellation")
        val session = RealtimeSttSession(engine)
        session.initialize("fixture").getOrThrow()
        session.start()
        engine.resetResult = Result.failure(failure)
        try {
            session.release()
            fail("The reset failure was hidden")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(1, engine.cancels.get())
        assertEquals(1, engine.releases.get())
        assertEquals(RealtimeSessionState.IDLE, session.state.value)
        assertEquals(0, unrelated.cancels.get())
        assertEquals(0, unrelated.releases.get())
    }

    @Test fun rejectedAudioIsTerminalUntilANewStartAndNeverFlushesAnIncompleteTail() = runBlocking {
        val engine = Engine()
        val failure = IllegalStateException("native push rejected frame: decoder cache exhausted")
        engine.pushResult = Result.failure(failure)
        val feeding = AtomicBoolean(false)
        feedWhenEnabled(feeding)
        val session = RealtimeSttSession(engine)
        try {
            session.initialize("fixture").getOrThrow()
            session.start()
            feeding.set(true)
            withTimeout(3000) { session.state.first { it == RealtimeSessionState.ERROR } }
            assertSame(failure, session.stop().exceptionOrNull())
            assertSame(failure, session.stop().exceptionOrNull())
            assertEquals(1, engine.pushes.get())
            assertEquals(0, engine.finalizations.get())
            assertEquals(1, engine.cancels.get())
            assertEquals(RealtimeSessionState.ERROR, session.state.value)

            // Only an explicit new timeline may clear the retained failure.
            feeding.set(false)
            engine.pushResult = Result.success(Unit)
            session.start()
            assertEquals(RealtimeSessionState.LISTENING, session.state.value)
            assertTrue(session.stop().isSuccess)
            assertEquals(1, engine.finalizations.get())
        } finally { session.release() }
        assertEquals(1, engine.releases.get())
    }

    @Test fun failedPartialPollAlsoRetiresCaptureWithoutASuccessfulTail() = runBlocking {
        val engine = Engine()
        val failure = IllegalStateException("native partial poll failed: decoder worker crashed")
        engine.partialFailure = failure
        val feeding = AtomicBoolean(false)
        feedWhenEnabled(feeding)
        val session = RealtimeSttSession(engine)
        try {
            session.initialize("fixture").getOrThrow()
            session.start()
            feeding.set(true)
            withTimeout(3000) { session.state.first { it == RealtimeSessionState.ERROR } }
            assertSame(failure, session.stop().exceptionOrNull())
            assertEquals(1, engine.pushes.get())
            assertEquals(0, engine.finalizations.get())
            assertEquals(1, engine.cancels.get())
        } finally { session.release() }
        assertEquals(1, engine.releases.get())
    }
}

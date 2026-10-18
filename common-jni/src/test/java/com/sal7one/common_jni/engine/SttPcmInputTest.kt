package com.sal7one.common_jni.engine

import com.sal7one.common_jni.engine.onnx.OnnxEngine
import com.sal7one.common_jni.engine.vosk.VoskEngine
import com.sal7one.common_jni.engine.whisper.WhisperEngine
import com.sal7one.common_jni.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SttPcmInputTest {
    private open class ArrayEngine : SttEngine {
        override val engineType = SttEngineType.ONNX
        override val isInitialized = true
        override val currentModel: ModelInfo? = null
        val chunks = mutableListOf<AudioChunk>()
        var pushResult = Result.success(Unit)
        override suspend fun initialize(modelPath: String, config: SttConfig) = Result.success(Unit)
        override suspend fun pushAudioChunk(chunk: AudioChunk): Result<Unit> {
            chunks += chunk
            return pushResult
        }
        override suspend fun getPartialTranscript(): PartialTranscript? = null
        override suspend fun finalize() = Result.success(TranscriptResult.EMPTY)
        override suspend fun reset() = Result.success(Unit)
        override suspend fun release() {}
        override suspend fun transcribeBatch(samples: ShortArray, sampleRate: Int, onProgress: ((Float) -> Unit)?) =
            Result.success(TranscriptResult.EMPTY)
    }

    // Deliberately uses the same engine identity as the array-only fake. Dispatch
    // must depend on the input contract, not Whisper/Vosk/ONNX enum values.
    private class DirectEngine : ArrayEngine(), DirectPcmSttEngine {
        val buffers = mutableListOf<ByteBuffer>()
        val frames = mutableListOf<ShortArray>()
        val rates = mutableListOf<Int>()
        var cancelled: CancellationException? = null
        override suspend fun pushAudioDirect(buffer: ByteBuffer, byteOffset: Int, byteCount: Int, sampleRate: Int): Result<Unit> {
            assertTrue(buffer.isDirect)
            assertEquals(ByteOrder.nativeOrder(), buffer.order())
            assertEquals(0, byteOffset)
            assertEquals(byteCount, buffer.limit())
            val view = buffer.duplicate().order(ByteOrder.nativeOrder())
            val frame = ShortArray(byteCount / Short.SIZE_BYTES)
            view.position(byteOffset)
            view.asShortBuffer().get(frame)
            buffers += buffer
            frames += frame
            rates += sampleRate
            cancelled?.let { throw it }
            return pushResult
        }
    }

    @Test fun optionalDirectInputPreservesSignedSamplesAndReusesOnlySufficientBuffers() = runBlocking {
        val engine = DirectEngine()
        val input = SttPcmInput(engine, 48_000)
        val frames = listOf(shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE), shortArrayOf(-123),
            ShortArray(40) { (it * 713 - 18_000).toShort() })
        frames.forEach { input.push(it).getOrThrow() }
        assertTrue(engine.chunks.isEmpty())
        assertEquals(listOf(48_000, 48_000, 48_000), engine.rates)
        frames.zip(engine.frames).forEach { (expected, actual) -> assertArrayEquals(expected, actual) }
        assertSame(engine.buffers[0], engine.buffers[1])
        assertNotSame(engine.buffers[1], engine.buffers[2])
    }

    @Test fun arrayOnlyEngineRetainsSamplesAndAudioMetadata() = runBlocking {
        val engine = ArrayEngine()
        val samples = ShortArray(800) { (it - 400).toShort() }
        SttPcmInput(engine, 16_000).push(samples, timestampMs = 917).getOrThrow()
        val chunk = engine.chunks.single()
        assertSame(samples, chunk.samples)
        assertEquals(16_000, chunk.sampleRate)
        assertEquals(917L, chunk.timestampMs)
        assertEquals(50L, chunk.durationMs)
    }

    @Test fun neitherInputPathTurnsAnEngineFailureIntoSuccessOrRetries() = runBlocking {
        listOf(ArrayEngine(), DirectEngine()).forEach { engine ->
            val failure = IllegalStateException("native decoder failed: capacity exhausted")
            engine.pushResult = Result.failure(failure)
            val result = SttPcmInput(engine, 16_000).push(shortArrayOf(7))
            assertSame(failure, result.exceptionOrNull())
            assertEquals(1, if (engine is DirectEngine) engine.frames.size else engine.chunks.size)
        }
    }

    @Test fun suspendedInputCancellationEscapesUnchanged() = runBlocking {
        val engine = DirectEngine()
        val cancellation = CancellationException("caption generation retired")
        engine.cancelled = cancellation
        try {
            SttPcmInput(engine, 16_000).push(shortArrayOf(2))
            fail("Cancellation was swallowed")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals(1, engine.frames.size)
        assertTrue(engine.chunks.isEmpty())
    }

    @Test fun nativeAdaptersDeclareOnlyTheirImplementedInputAndCancellationContracts() {
        listOf(WhisperEngine::class.java, VoskEngine::class.java).forEach {
            assertTrue(DirectPcmSttEngine::class.java.isAssignableFrom(it))
            assertTrue(CancellableSttEngine::class.java.isAssignableFrom(it))
        }
        assertFalse(DirectPcmSttEngine::class.java.isAssignableFrom(OnnxEngine::class.java))
        assertTrue(CancellableSttEngine::class.java.isAssignableFrom(OnnxEngine::class.java))
    }
}

package com.sal7one.hearth.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HearthPipelineTest {
    private class Engines : PipelineEngines {
        val calls = mutableListOf<String>()
        var speech: suspend (suspend (SpeechSegment) -> Unit) -> Unit = { emit ->
            emit(SpeechSegment("one", "hello", false))
            emit(SpeechSegment("one", "hello", true, language = "en"))
            emit(SpeechSegment("one", "hello", true, language = "en"))
        }
        var translation: suspend (String) -> String = { "مرحبا" }
        override fun capabilities() = listOf(
            EngineDescriptor("s", "Speech", PipelineStage.STT, EngineKind.LOCAL),
            EngineDescriptor("m", "Translation", PipelineStage.TRANSLATION, EngineKind.LOCAL),
            EngineDescriptor("v", "Voice", PipelineStage.TTS, EngineKind.SYSTEM),
        )
        override suspend fun recognize(input: PipelineInput, selection: EngineSelection, sourceLanguage: String, emit: suspend (SpeechSegment) -> Unit) = speech(emit)
        override suspend fun translate(text: String, selection: EngineSelection, sourceLanguage: String, targetLanguage: String): String {
            calls += "translate:$text:$sourceLanguage:$targetLanguage"; return translation(text)
        }
        override suspend fun synthesize(text: String, selection: EngineSelection, language: String, voiceId: String): GeneratedAudio {
            calls += "speak:$text:$language:$voiceId"; return GeneratedAudio("out.wav")
        }
    }
    private val config = PipelineConfig(EngineSelection("s"), EngineSelection("m"), EngineSelection("v"), targetLanguage = "ar", voiceId = "female")

    @Test fun finalizedIdentityRunsTranslationAndVoiceExactlyOnce() = runTest {
        val engines = Engines()
        val job = HearthPipeline(engines, this).start(PipelineRequest(PipelineInput.AudioFile("test.wav"), config))
        val result = job.state.first { it.terminal }
        assertEquals(PipelineStatus.COMPLETED, result.status)
        assertEquals("hello", result.transcript)
        assertEquals("مرحبا", result.translatedText)
        assertEquals(listOf("translate:hello:en:ar", "speak:مرحبا:ar:female"), engines.calls)
        assertEquals(1, result.audio.size)
    }
    @Test fun translationFailureKeepsOriginalAndNeverSpeaks() = runTest {
        val engines = Engines().apply { translation = { error("model failed") } }
        val result = HearthPipeline(engines, this).start(PipelineRequest(PipelineInput.AudioFile("test.wav"), config)).state.first { it.terminal }
        assertEquals(PipelineStatus.FAILED, result.status)
        assertEquals("hello", result.transcript)
        assertEquals("model failed", result.error)
        assertFalse(engines.calls.any { it.startsWith("speak") })
    }
    @Test fun cancellingJoinsOwnerBeforeAdmittingNextWork() = runTest {
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        val engines = Engines().apply { speech = { try { entered.complete(Unit); awaitCancellation() } finally { released.complete(Unit) } } }
        val pipeline = HearthPipeline(engines, this, maxQueuedJobs = 1)
        val job = pipeline.start(PipelineRequest(PipelineInput.AudioFile("a.wav"), config))
        entered.await()
        assertFailsWith<IllegalStateException> { pipeline.start(PipelineRequest(PipelineInput.Text("queued"), config)) }
        job.cancel()
        assertTrue(released.isCompleted)
        assertEquals(PipelineStatus.CANCELLED, job.state.value.status)
        val next = pipeline.start(PipelineRequest(PipelineInput.Text("next"), config.copy(speech = null)))
        assertEquals(PipelineStatus.COMPLETED, next.state.first { it.terminal }.status)
    }
    @Test fun incompletePartialCannotBecomeSuccessfulFinalText() = runTest {
        val engines = Engines().apply { speech = { it(SpeechSegment("one", "unfinished", false)) } }
        val result = HearthPipeline(engines, this).start(PipelineRequest(PipelineInput.AudioFile("a.wav"), config)).state.first { it.terminal }
        assertEquals(PipelineStatus.FAILED, result.status)
        assertEquals("", result.transcript)
        assertTrue(engines.calls.isEmpty())
    }
    @Test fun blankTranslationIsFailure() = runTest {
        val engines = Engines().apply { translation = { " " } }
        val result = HearthPipeline(engines, this).start(PipelineRequest(PipelineInput.Text("hello"), config)).state.first { it.terminal }
        assertEquals(PipelineStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("empty text"))
    }
    @Test fun routeOptionsAreSnapshottedBeforeQueuedWork() = runTest {
        val options = mutableMapOf("threads" to "2")
        var observed = ""
        val engines = object : PipelineEngines by Engines() {
            override suspend fun translate(text: String, selection: EngineSelection, sourceLanguage: String, targetLanguage: String): String {
                observed = selection.options.getValue("threads"); return "ok"
            }
        }
        val job = HearthPipeline(engines, this).start(PipelineRequest(PipelineInput.Text("hello"), PipelineConfig(translation = EngineSelection("m", options = options))))
        options["threads"] = "8"
        job.state.first { it.terminal }
        assertEquals("2", observed)
    }
    @Test fun unicodeChunksPreserveInputAndSurrogatePairs() {
        val text = "A😀B😀C مرحبا بالعالم. Next sentence!"
        val chunks = TextChunks.split(text, 4)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }
    @Test fun slowTranslationDoesNotBlockSpeechAndFinalsDrainInOrder() = runTest {
        val entered = CompletableDeferred<Unit>(); val allow = CompletableDeferred<Unit>(); val speechDone = CompletableDeferred<Unit>()
        val engines = Engines().apply {
            speech = { emit ->
                emit(SpeechSegment("1", "first", true, language = "en"))
                entered.await()
                emit(SpeechSegment("2", "second", true, language = "en"))
                speechDone.complete(Unit)
            }
            translation = { text -> entered.complete(Unit); allow.await(); "translated:$text" }
        }
        val job = HearthPipeline(engines, this).start(PipelineRequest(PipelineInput.AudioStream(kotlinx.coroutines.flow.emptyFlow()), config.copy(voice = null)))
        speechDone.await()
        assertEquals(2, job.state.value.segments.size)
        assertFalse(job.state.value.terminal)
        allow.complete(Unit)
        val completed = job.state.first { it.terminal }
        assertEquals(PipelineStatus.COMPLETED, completed.status)
        assertEquals(listOf("translated:first", "translated:second"), completed.segments.map { it.translation })
    }
    @Test fun liveDownstreamOverflowFailsAndJoinsSpeechOwner() = runTest {
        val released = CompletableDeferred<Unit>()
        val engines = Engines().apply {
            speech = { emit -> try { repeat(20) { emit(SpeechSegment("s$it", "word$it", true)) } } finally { released.complete(Unit) } }
            translation = { awaitCancellation() }
        }
        val job = HearthPipeline(engines, this).start(PipelineRequest(PipelineInput.AudioStream(kotlinx.coroutines.flow.emptyFlow()), config.copy(voice = null)))
        val failed = job.state.first { it.terminal }
        assertEquals(PipelineStatus.FAILED, failed.status)
        assertTrue(failed.error!!.contains("queue is full"))
        assertTrue(released.isCompleted)
        assertTrue(failed.segments.isNotEmpty())
    }

    @Test fun platformRuntimeErrorsAreFailedRatherThanCancelled() = runTest {
        val engines = Engines().apply { speech = { throw Error("native symbol unavailable") } }
        val result = HearthPipeline(engines, this).start(PipelineRequest(PipelineInput.AudioFile("x.wav"), config)).state.first { it.terminal }
        assertEquals(PipelineStatus.FAILED, result.status)
        assertEquals("native symbol unavailable", result.error)
    }

}

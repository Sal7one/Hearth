package com.sal7one.hearth.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Shared execution policy. Platform engines continue to own their handles, transports and playback. */
class HearthPipeline(
    private val engines: PipelineEngines,
    private val scope: CoroutineScope,
    private val maxQueuedJobs: Int = 4,
) : PipelineService {
    private val work = Mutex()
    private val admitted = MutableStateFlow(0)
    init { require(maxQueuedJobs in 1..16) }
    override fun capabilities(): List<EngineDescriptor> = engines.capabilities()

    override fun start(request: PipelineRequest): PipelineSession {
        val config = request.config.snapshot()
        require(config.sourceLanguage.length in 2..32 && config.targetLanguage.length in 2..32) { "Invalid language code" }
        when (val input = request.input) {
            is PipelineInput.Text -> require(input.text.isNotBlank() && input.text.length <= MAX_TEXT) { "Text must contain 1..$MAX_TEXT characters" }
            is PipelineInput.AudioFile -> require(input.path.isNotBlank() && config.speech != null) { "Choose a speech engine for audio input" }
            is PipelineInput.AudioStream -> require(config.speech != null) { "Choose a speech engine for audio input" }
        }
        listOf(PipelineStage.STT to config.speech, PipelineStage.TRANSLATION to config.translation, PipelineStage.TTS to config.voice)
            .filter { it.second != null && (it.first != PipelineStage.STT || request.input !is PipelineInput.Text) }
            .forEach { (stage, route) ->
                val engine = capabilities().singleOrNull { it.id == route!!.engineId && it.stage == stage }
                    ?: throw IllegalArgumentException("Unknown $stage engine: ${route!!.engineId}")
                require(engine.available) { engine.unavailableReason ?: "${engine.name} is unavailable" }
            }
        while (true) {
            val count = admitted.value
            check(count < maxQueuedJobs) { "Pipeline queue is full ($maxQueuedJobs jobs). Finish or cancel a job first." }
            if (admitted.compareAndSet(count, count + 1)) break
        }
        val session = OwnedSession("job-${Random.nextLong().toULong().toString(16)}")
        session.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                work.withLock {
                    session.update { it.copy(status = PipelineStatus.RUNNING) }
                    execute(request.input, config, session)
                    session.update { it.copy(status = PipelineStatus.COMPLETED, stage = null) }
                }
            } catch (cancelled: CancellationException) {
                session.update { it.copy(status = PipelineStatus.CANCELLED, stage = null) }
                throw cancelled
            } catch (failure: Throwable) {
                session.update { it.copy(status = PipelineStatus.FAILED, error = failure.message ?: failure::class.simpleName ?: "Pipeline failed") }
            }
        }
        session.job.invokeOnCompletion {
            session.cancelledIfNotTerminal()
            while (true) {
                val count = admitted.value
                if (admitted.compareAndSet(count, count - 1)) break
            }
        }
        session.job.start()
        return session
    }

    private suspend fun execute(input: PipelineInput, config: PipelineConfig, session: OwnedSession) = coroutineScope {
        val finalized = mutableMapOf<String, String>()
        val captionsLock = Mutex()
        // Translation/voice cannot block capture: one active final plus eight queued finals.
        // Overflow fails visibly and cancels/joins both stage owners; accepted speech is never dropped.
        val finals = Channel<SpeechSegment>(8)
        suspend fun processFinal(segment: SpeechSegment) {
            var spokenText = segment.text
            var spokenLanguage = segment.language ?: config.sourceLanguage
            config.translation?.let { route ->
                session.update { it.copy(stage = PipelineStage.TRANSLATION) }
                val translated = engines.translate(segment.text, route, segment.language ?: config.sourceLanguage, config.targetLanguage)
                check(translated.isNotBlank()) { "Translation engine returned empty text" }
                check(translated.length <= MAX_TEXT) { "Translation exceeds text limit" }
                session.update { current ->
                    val updated = current.segments.map { if (it.id == segment.id) it.copy(translation = translated) else it }
                    check(updated.sumOf { it.original.length + (it.translation?.length ?: 0) } <= MAX_SESSION_TEXT) { "Session text limit reached" }
                    current.copy(segments = updated)
                }
                if (config.speakTranslatedText) { spokenText = translated; spokenLanguage = config.targetLanguage }
            }
            config.voice?.let { route ->
                session.update { it.copy(stage = PipelineStage.TTS) }
                val audio = engines.synthesize(spokenText, route, spokenLanguage, config.voiceId)
                check(audio.path.isNotBlank()) { "Voice engine returned no audio artifact" }
                session.update { it.copy(audio = it.audio + audio.copy(text = spokenText)) }
            }
            if (input !is PipelineInput.Text) session.update { it.copy(stage = PipelineStage.STT) }
        }
        val downstream = launch { for (segment in finals) processFinal(segment) }
        suspend fun accept(segment: SpeechSegment) = captionsLock.withLock {
            require(segment.id.isNotBlank() && segment.id.length <= 256) { "Invalid speech segment identity" }
            require(segment.text.length <= MAX_TEXT) { "Speech segment exceeds text limit" }
            val existing = finalized[segment.id]
            if (existing != null) {
                require(!segment.final || existing == segment.text) { "Speech engine changed an already finalized segment" }
                return@withLock
            }
            session.update { current ->
                val captions = current.segments.toMutableList()
                val index = captions.indexOfFirst { it.id == segment.id }
                val caption = CaptionSegment(segment.id, segment.text, final = segment.final, startMs = segment.startMs, endMs = segment.endMs)
                if (index >= 0) captions[index] = caption else {
                    check(captions.size < MAX_SEGMENTS) { "Caption limit reached. Start a new session." }
                    captions.add(caption)
                }
                check(captions.sumOf { it.original.length + (it.translation?.length ?: 0) } <= MAX_SESSION_TEXT) { "Session text limit reached" }
                current.copy(segments = captions.toList())
            }
            if (segment.final) {
                finalized[segment.id] = segment.text
                if (segment.text.isNotBlank() && (config.translation != null || config.voice != null)) {
                    if (input is PipelineInput.AudioStream) check(finals.trySend(segment).isSuccess) {
                        "Translation/voice queue is full. Use faster models or disable a downstream stage."
                    } else finals.send(segment) // File/text sources may pause safely; capture must remain bounded.
                }
            }
        }
        try {
            if (input is PipelineInput.Text) accept(SpeechSegment("text-1", input.text.trim(), true, language = config.sourceLanguage))
            else {
                session.update { it.copy(stage = PipelineStage.STT) }
                engines.recognize(input, requireNotNull(config.speech), config.sourceLanguage, ::accept)
                check(session.state.value.segments.all { it.final }) { "Speech engine ended with unfinished text" }
            }
            finals.close()
            downstream.join()
        } finally { finals.cancel() }
    }

    private class OwnedSession(override val id: String) : PipelineSession {
        private val mutableState = MutableStateFlow(PipelineSnapshot(id))
        private val mutableEvents = MutableSharedFlow<PipelineEvent>(replay = 1, extraBufferCapacity = 31,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
        override val state = mutableState.asStateFlow()
        override val events = mutableEvents.asSharedFlow()
        lateinit var job: Job
        private val updates = Mutex()
        suspend fun update(transform: (PipelineSnapshot) -> PipelineSnapshot) = updates.withLock {
            val next = transform(mutableState.value)
            mutableState.value = next
            mutableEvents.tryEmit(PipelineEvent.Changed(next))
            Unit
        }
        // Job completion runs after every child/native/transport owner joined; no concurrent writer remains.
        fun cancelledIfNotTerminal() {
            val current = mutableState.value
            if (!current.terminal) {
                val next = current.copy(status = PipelineStatus.CANCELLED, stage = null)
                mutableState.value = next; mutableEvents.tryEmit(PipelineEvent.Changed(next))
            }
        }
        override suspend fun cancel() { job.cancelAndJoin() }
    }
    companion object {
        const val MAX_TEXT = 100_000
        const val MAX_SESSION_TEXT = 1_000_000
        const val MAX_SEGMENTS = 2_000
    }
}

private fun PipelineConfig.snapshot() = copy(
    speech = speech?.copy(options = speech.options.toMap()),
    translation = translation?.copy(options = translation.options.toMap()),
    voice = voice?.copy(options = voice.options.toMap()),
)

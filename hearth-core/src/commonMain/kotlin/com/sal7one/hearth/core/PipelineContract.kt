package com.sal7one.hearth.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class PipelineStage { STT, TRANSLATION, TTS }
enum class EngineKind { LOCAL, CLOUD, SYSTEM }

data class EngineDescriptor(
    val id: String,
    val name: String,
    val stage: PipelineStage,
    val kind: EngineKind,
    val languages: Set<String> = emptySet(),
    val streaming: Boolean = false,
    val acceleration: Set<String> = setOf("cpu"),
    val available: Boolean = true,
    val unavailableReason: String? = null,
    val requiresModel: Boolean = false,
    val requiresCredential: Boolean = false,
    val defaultEndpoint: String = "",
    val defaultModel: String = "",
)

/** A saved route contains a credential reference, never a provider secret. */
data class EngineSelection(
    val engineId: String,
    val modelId: String = "",
    val modelPath: String = "",
    val endpoint: String = "",
    val credentialId: String = "",
    val options: Map<String, String> = emptyMap(),
)

data class PipelineConfig(
    val speech: EngineSelection? = null,
    val translation: EngineSelection? = null,
    val voice: EngineSelection? = null,
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "en",
    val voiceId: String = "",
    val speakTranslatedText: Boolean = true,
)

/** PCM frames are little-endian, mono PCM16 at 16 kHz. Producers retain bounded queues. */
data class PcmFrame(val bytes: ByteArray) {
    init { require(bytes.isNotEmpty() && bytes.size % 2 == 0 && bytes.size <= 64_000) { "Invalid PCM16 frame size" } }
}
sealed interface PipelineInput {
    data class Text(val text: String) : PipelineInput
    data class AudioFile(val path: String) : PipelineInput
    data class AudioStream(val frames: Flow<PcmFrame>) : PipelineInput
}
data class PipelineRequest(val input: PipelineInput, val config: PipelineConfig)

data class SpeechSegment(
    val id: String,
    val text: String,
    val final: Boolean,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val language: String? = null,
)
data class CaptionSegment(
    val id: String,
    val original: String,
    val translation: String? = null,
    val final: Boolean = true,
    val startMs: Long = 0,
    val endMs: Long = 0,
)
data class GeneratedAudio(val path: String, val mimeType: String = "audio/wav", val text: String = "")
enum class PipelineStatus { QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED }
data class PipelineSnapshot(
    val id: String,
    val status: PipelineStatus = PipelineStatus.QUEUED,
    val stage: PipelineStage? = null,
    val segments: List<CaptionSegment> = emptyList(),
    val audio: List<GeneratedAudio> = emptyList(),
    val error: String? = null,
) {
    val terminal: Boolean get() = status in setOf(PipelineStatus.COMPLETED, PipelineStatus.CANCELLED, PipelineStatus.FAILED)
    val transcript: String get() = segments.filter { it.final }.joinToString("\n") { it.original }
    val translatedText: String get() = segments.mapNotNull { it.translation }.joinToString("\n")
}
sealed interface PipelineEvent {
    data class Changed(val snapshot: PipelineSnapshot) : PipelineEvent
}
interface PipelineSession {
    val id: String
    val state: StateFlow<PipelineSnapshot>
    val events: SharedFlow<PipelineEvent>
    /** Cancellation joins all native/transport work before returning. */
    suspend fun cancel()
}
interface PipelineService {
    fun capabilities(): List<EngineDescriptor>
    fun start(request: PipelineRequest): PipelineSession
}

/** Platform adapters own native handles and must release/join them before a call returns. */
interface PipelineEngines {
    fun capabilities(): List<EngineDescriptor>
    suspend fun recognize(input: PipelineInput, selection: EngineSelection, sourceLanguage: String, emit: suspend (SpeechSegment) -> Unit)
    suspend fun translate(text: String, selection: EngineSelection, sourceLanguage: String, targetLanguage: String): String
    suspend fun synthesize(text: String, selection: EngineSelection, language: String, voiceId: String): GeneratedAudio
}
fun interface CredentialResolver { suspend fun resolve(credentialId: String): String? }

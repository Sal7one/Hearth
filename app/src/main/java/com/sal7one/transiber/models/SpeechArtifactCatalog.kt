package com.sal7one.transiber.models

import com.sal7one.transiber.caption.CaptionEngineChoice

/** Runtime compatibility and downloadable identity are separate: a quant is not a new backend. */
internal enum class SpeechArtifactKind { PUBLISHER, WHISPER, VOSK }

internal data class SpeechArtifact(
    val id: String,
    val family: String,
    val checkpoint: String,
    val quant: String,
    val label: String,
    val kind: SpeechArtifactKind,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val sourceRevision: String,
    val modelReleaseDate: String?,
    /** Publisher upload/conversion date, never presented as the model's release date. */
    val artifactDate: String?,
    val languages: Set<String>,
    val license: String,
    val publisherUrl: String,
    val installedBytes: Long? = null,
    val advanced: Boolean = false,
    val archiveRoot: String? = null,
    val publisher: SpeechDownload? = null,
) {
    val fileName: String get() = url.substringAfterLast('/')
    val engine: CaptionEngineChoice get() = when (kind) {
        SpeechArtifactKind.WHISPER -> CaptionEngineChoice.WHISPER
        SpeechArtifactKind.VOSK -> CaptionEngineChoice.VOSK
        SpeechArtifactKind.PUBLISHER -> when (family) {
            "moonshine" -> CaptionEngineChoice.MOONSHINE
            "qwen3-asr" -> CaptionEngineChoice.QWEN
            "omnilingual" -> CaptionEngineChoice.OMNILINGUAL
            "nemotron" -> CaptionEngineChoice.NEMOTRON
            else -> error("Unknown speech artifact family: $family")
        }
    }
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Invalid speech artifact ID" }
        require(url.startsWith("https://") && bytes > 0 && sha256.matches(Regex("[a-f0-9]{64}"))) { "Speech downloads require pinned HTTPS artifacts" }
        require(sourceRevision.isNotBlank() && languages.isNotEmpty()) { "Missing speech artifact provenance/capabilities" }
        require(installedBytes == null || installedBytes > 0)
        require((kind == SpeechArtifactKind.PUBLISHER) == (publisher != null))
        require(kind != SpeechArtifactKind.VOSK || !archiveRoot.isNullOrBlank())
    }
}

/** Only installable artifacts belong here; research candidates never become fake download buttons. */
internal object SpeechArtifactCatalog {
    private data class ArtifactInfo(
        val quantization: String, val revision: String, val artifactDate: String,
        val license: String, val publisherUrl: String, val label: String,
    )
    // Vosk remains importable, but Hearth does not publish a pinned Vosk package today.
    // Never turn an unverified third-party ZIP into a download button.
    val all: List<SpeechArtifact> = SpeechDownloads.all.map { it.artifact() } + WhisperDownloads.all
    fun find(id: String): SpeechArtifact? = all.firstOrNull { it.id == id }
    fun forEngine(engine: CaptionEngineChoice): List<SpeechArtifact> = all.filter { it.engine == engine }

    private fun SpeechDownload.artifact(): SpeechArtifact {
        val family = when (profile.backend) {
            com.sal7one.common_jni.speech.SpeechBackend.MOONSHINE -> "moonshine"
            com.sal7one.common_jni.speech.SpeechBackend.QWEN3_ASR -> "qwen3-asr"
            com.sal7one.common_jni.speech.SpeechBackend.OMNILINGUAL_CTC -> "omnilingual"
            com.sal7one.common_jni.speech.SpeechBackend.NEMOTRON_3_5 -> "nemotron"
        }
        val info = when (profile) {
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_TINY_EN -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "MIT", "https://github.com/moonshine-ai/moonshine", "Moonshine Tiny · English · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_BASE_EN -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "MIT", "https://github.com/moonshine-ai/moonshine", "Moonshine Base · English · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.QWEN3_ASR_0_6B -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-03-25", "2026-03-25", "Apache-2.0", "https://huggingface.co/Qwen/Qwen3-ASR-0.6B", "Qwen3-ASR 0.6B · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.OMNILINGUAL_CTC_300M_V2 -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-05", "2026-02-05", "Apache-2.0", "https://huggingface.co/facebook/omniASR-CTC-300M", "Omnilingual CTC 300M v2 · INT8 · experimental")
            com.sal7one.common_jni.speech.SpeechProfile.NEMOTRON_3_5_ASR_0_6B -> ArtifactInfo("Q8_0", "1c8deaecc64b91f034d73e08dd8b64625eb3395d", "2026-06-04", "OpenMDW-1.1", "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b", "Nemotron 3.5 ASR 0.6B · Q8_0")
            else -> error("No published artifact metadata for ${profile.id}")
        }
        return SpeechArtifact(profile.id, family, profile.id, info.quantization, info.label, SpeechArtifactKind.PUBLISHER,
            url, bytes, sha256, info.revision,
            info.artifactDate.takeIf { profile == com.sal7one.common_jni.speech.SpeechProfile.NEMOTRON_3_5_ASR_0_6B }, info.artifactDate,
            profile.capabilities.sourceLanguages, info.license, info.publisherUrl,
            installedBytes = if (archiveRoot == null) bytes else null, archiveRoot = archiveRoot, publisher = this)
    }
}

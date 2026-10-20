package com.sal7one.transiber.models

import com.sal7one.transiber.caption.CaptionEngineChoice

/** Runtime compatibility and downloadable identity are separate: a quant is not a new backend. */
internal enum class SpeechArtifactKind { PUBLISHER, COMPONENTS, WHISPER, VOSK }

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
    val components: SpeechComponentGroup? = null,
    /** New native runtimes are published only for ARM64 phones. */
    val supportedAbis: Set<String> = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"),
) {
    val profile get() = publisher?.profile ?: components?.profile
    val fileName: String get() = url.substringAfterLast('/')
    /** Browsable artifact provenance, never a multi-gigabyte raw download. */
    val filesUrl: String get() = when {
        components != null -> "$publisherUrl/tree/$sourceRevision"
        url.startsWith("https://huggingface.co/") -> url.substringBefore("/resolve/") +
            "/tree/" + url.substringAfter("/resolve/").substringBefore('/')
        url.startsWith("https://github.com/") && "/releases/download/" in url ->
            url.substringBefore("/releases/download/") + "/releases/tag/" +
                url.substringAfter("/releases/download/").substringBefore('/')
        else -> publisherUrl
    }
    /** The upstream model card explains the model license, distinct from runtime licenses. */
    val licenseUrl: String get() = when {
        family == "audar" -> "https://www.audarai.com/license/audarai-open-license-v1.0/"
        family == "funasr-mlt" -> url.substringBefore("/resolve/") + "/blob/" + sourceRevision + "/LICENSE"
        publisherUrl == "https://github.com/moonshine-ai/moonshine" -> "$publisherUrl/blob/main/LICENSE"
        publisherUrl.startsWith("https://github.com/") -> "$publisherUrl#license"
        else -> publisherUrl
    }
    val engine: CaptionEngineChoice get() = when (kind) {
        SpeechArtifactKind.WHISPER -> CaptionEngineChoice.WHISPER
        SpeechArtifactKind.VOSK -> CaptionEngineChoice.VOSK
        SpeechArtifactKind.PUBLISHER, SpeechArtifactKind.COMPONENTS -> when (family) {
            "audar" -> CaptionEngineChoice.AUDAR
            "owsm" -> CaptionEngineChoice.OWSM
            "cohere" -> CaptionEngineChoice.COHERE
            "funasr-mlt" -> CaptionEngineChoice.FUNASR_MLT
            "voxtral-realtime" -> CaptionEngineChoice.VOXTRAL
            "moonshine" -> CaptionEngineChoice.MOONSHINE
            "qwen3-asr" -> CaptionEngineChoice.QWEN
            "omnilingual" -> CaptionEngineChoice.OMNILINGUAL
            "phonon" -> CaptionEngineChoice.PHONON
            "nemotron" -> CaptionEngineChoice.NEMOTRON
            "transcribepsy" -> CaptionEngineChoice.TRANSCRIBEPSY
            else -> error("Unknown speech artifact family: $family")
        }
    }
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Invalid speech artifact ID" }
        require(url.startsWith("https://") && bytes > 0 && sha256.matches(Regex("[a-f0-9]{64}"))) { "Speech downloads require pinned HTTPS artifacts" }
        require(sourceRevision.isNotBlank() && languages.isNotEmpty()) { "Missing speech artifact provenance/capabilities" }
        require(installedBytes == null || installedBytes > 0)
        require((kind == SpeechArtifactKind.PUBLISHER) == (publisher != null))
        require((kind == SpeechArtifactKind.COMPONENTS) == (components != null))
        require(components == null || (id == components.id && bytes == components.bytes && sha256 == components.componentSetSha256))
        require(kind != SpeechArtifactKind.VOSK || !archiveRoot.isNullOrBlank())
    }
}

/** Only installable artifacts belong here; research candidates never become fake download buttons. */
internal object SpeechArtifactCatalog {
    private data class ArtifactInfo(
        val quantization: String, val revision: String, val artifactDate: String?,
        val license: String, val publisherUrl: String, val label: String,
    )
    // Publisher archives are pinned before exposing direct installation; arbitrary imports stay separate.
    val all: List<SpeechArtifact> = SpeechDownloads.all.map { it.artifact() } + SpeechComponents.groups.map { group ->
        SpeechArtifact(group.id, "audar", group.profile.id, "Q4_K_M + BF16 encoder", "Audar-ASR V1 Flash · phone test pending",
            SpeechArtifactKind.COMPONENTS, "https://huggingface.co/audarai/Audar-ASR-V1-Flash", group.bytes,
            group.componentSetSha256, group.revision, null, null, setOf("ar", "en"),
            "AudarAI Open License v1.0", "https://huggingface.co/audarai/Audar-ASR-V1-Flash",
            installedBytes = group.bytes, advanced = true, components = group, supportedAbis = setOf("arm64-v8a"))
    } + WhisperDownloads.all + VoskDownloads.all
    fun find(id: String): SpeechArtifact? = all.firstOrNull { it.id == id }
    fun forEngine(engine: CaptionEngineChoice): List<SpeechArtifact> = all.filter { it.engine == engine }

    private fun SpeechDownload.artifact(): SpeechArtifact {
        val family = when (profile.backend) {
            com.sal7one.common_jni.speech.SpeechBackend.AUDAR -> "audar"
            com.sal7one.common_jni.speech.SpeechBackend.OWSM -> "owsm"
            com.sal7one.common_jni.speech.SpeechBackend.COHERE -> "cohere"
            com.sal7one.common_jni.speech.SpeechBackend.FUNASR_MLT -> "funasr-mlt"
            com.sal7one.common_jni.speech.SpeechBackend.VOXTRAL_REALTIME -> "voxtral-realtime"
            com.sal7one.common_jni.speech.SpeechBackend.MOONSHINE -> "moonshine"
            com.sal7one.common_jni.speech.SpeechBackend.QWEN3_ASR -> "qwen3-asr"
            com.sal7one.common_jni.speech.SpeechBackend.OMNILINGUAL_CTC -> "omnilingual"
            com.sal7one.common_jni.speech.SpeechBackend.PHONON -> "phonon"
            com.sal7one.common_jni.speech.SpeechBackend.NEMOTRON_3_5 -> "nemotron"
            com.sal7one.common_jni.speech.SpeechBackend.TRANSCRIBEPSY -> "transcribepsy"
        }
        val info = when (profile) {
            com.sal7one.common_jni.speech.SpeechProfile.OWSM_V4_BASE_102M -> ArtifactInfo("Q8_0", "661a224e9d9adbd49c01626f85e426f5f425d771", null, "CC-BY-4.0", "https://huggingface.co/espnet/owsm_v4_base_102M", "OWSM v4 Base 102M · Q8_0 · experimental")
            com.sal7one.common_jni.speech.SpeechProfile.COHERE_ARABIC_07_2026 -> ArtifactInfo("Q4_K_M", "11a54d6895f94cce78d0ece142b8e552e7822539", null, "Apache-2.0", "https://huggingface.co/CohereLabs/cohere-transcribe-arabic-07-2026", "Cohere Transcribe Arabic 07-2026 \u00b7 Q4_K_M \u00b7 phone test pending")
            com.sal7one.common_jni.speech.SpeechProfile.VOXTRAL_MINI_REALTIME_2602 -> ArtifactInfo("Q4_K_M", "7fd3d424fc558edca2c53808578b0541ef52a81a", null, "Apache-2.0", "https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602", "Voxtral Mini Realtime 2602 \u00b7 Q4_K_M \u00b7 phone test pending")
            com.sal7one.common_jni.speech.SpeechProfile.FUNASR_MLT_NANO_2512 -> ArtifactInfo("Q8_0", "5db7c5bcebbc70d6c372e825399c3019f6089af7", null, "FunASR Model Open Source License Agreement v1.1 (selected GGUF); upstream HF metadata currently says Apache-2.0", "https://huggingface.co/FunAudioLLM/Fun-ASR-MLT-Nano-2512", "Fun-ASR MLT Nano 2512 \u00b7 Q8_0 \u00b7 phone test pending")
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_TINY_EN -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "MIT", "https://github.com/moonshine-ai/moonshine", "Moonshine Tiny · English · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_BASE_EN -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "MIT", "https://github.com/moonshine-ai/moonshine", "Moonshine Base · English · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_BASE_AR -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "Moonshine Community License", "https://github.com/moonshine-ai/moonshine-v2", "Moonshine Base · Arabic · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_BASE_ES -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "Moonshine Community License", "https://github.com/moonshine-ai/moonshine-v2", "Moonshine Base · Spanish · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_TINY_JA -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "Moonshine Community License", "https://github.com/moonshine-ai/moonshine-v2", "Moonshine Tiny · Japanese · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_TINY_KO -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "Moonshine Community License", "https://github.com/moonshine-ai/moonshine-v2", "Moonshine Tiny · Korean · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_BASE_JA -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "Moonshine Community License", "https://github.com/moonshine-ai/moonshine-v2", "Moonshine Base · Japanese · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_BASE_UK -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "Moonshine Community License", "https://github.com/moonshine-ai/moonshine-v2", "Moonshine Base · Ukrainian · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_BASE_VI -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "Moonshine Community License", "https://github.com/moonshine-ai/moonshine-v2", "Moonshine Base · Vietnamese · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.MOONSHINE_BASE_ZH -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-27", "2026-02-27", "Moonshine Community License", "https://github.com/moonshine-ai/moonshine-v2", "Moonshine Base · Chinese · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.QWEN3_ASR_0_6B -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-03-25", "2026-03-25", "Apache-2.0", "https://huggingface.co/Qwen/Qwen3-ASR-0.6B", "Qwen3-ASR 0.6B · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.OMNILINGUAL_CTC_300M_V2 -> ArtifactInfo("INT8", "sherpa-onnx/asr-models/2026-02-05", "2026-02-05", "Apache-2.0", "https://huggingface.co/facebook/omniASR-CTC-300M", "Omnilingual CTC 300M v2 · INT8 · experimental")
            com.sal7one.common_jni.speech.SpeechProfile.PHONON_2 -> ArtifactInfo("INT8", "FermionResearch/Phonon-2 bps sha256/98125795b6dda72f5c6eee9ba33d19815df65dcb18b50a357bf9f73c9935309e", "2026-09-25", "CC-BY-4.0", "https://huggingface.co/FermionResearch/Phonon-2", "Phonon 2 · English · INT8")
            com.sal7one.common_jni.speech.SpeechProfile.TRANSCRIBEPSY_250M_EU_SUB -> ArtifactInfo("Q8_0", "92460e0a8e7dc20f2fb126198bdd9eebb9ba2dc7", "2026-10-13", "Apache-2.0", "https://huggingface.co/qvac/TranscribePsy-250M-EU-sub-gguf", "TranscribePsy 250M · 8 European languages · Q8_0")
            com.sal7one.common_jni.speech.SpeechProfile.NEMOTRON_3_5_ASR_0_6B -> ArtifactInfo("Q8_0", "1c8deaecc64b91f034d73e08dd8b64625eb3395d", "2026-06-04", "OpenMDW-1.1", "https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b", "Nemotron 3.5 ASR 0.6B · Q8_0")
            else -> error("No published artifact metadata for ${profile.id}")
        }
        return SpeechArtifact(profile.id, family, profile.id, info.quantization, info.label, SpeechArtifactKind.PUBLISHER,
            url, bytes, sha256, info.revision,
            when (profile) {
                com.sal7one.common_jni.speech.SpeechProfile.TRANSCRIBEPSY_250M_EU_SUB -> "2026-10-14"
                com.sal7one.common_jni.speech.SpeechProfile.NEMOTRON_3_5_ASR_0_6B -> info.artifactDate
                else -> null
            }, info.artifactDate,
            profile.capabilities.sourceLanguages, info.license, info.publisherUrl,
            installedBytes = installationBytes, archiveRoot = archiveRoot, publisher = this,
            advanced = family in setOf("cohere", "funasr-mlt", "voxtral-realtime", "owsm"),
            supportedAbis = if (family in setOf("cohere", "funasr-mlt", "voxtral-realtime", "owsm")) setOf("arm64-v8a") else setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
    }
}

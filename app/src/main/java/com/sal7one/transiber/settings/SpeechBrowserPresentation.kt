package com.sal7one.transiber.settings

import com.sal7one.transiber.models.SpeechArtifact
import com.sal7one.transiber.models.SpeechArtifactKind
import com.sal7one.common_jni.speech.SpeechStreamingKind

internal enum class SpeechDecodeMode { STREAMING_PARTIALS, WINDOWED_PARTIALS, UTTERANCE_FINALS }

/** Describes the adapter used by Hearth, independently of publisher speed claims. */
internal fun speechDecodeMode(item: SpeechArtifact): SpeechDecodeMode = when (item.kind) {
    SpeechArtifactKind.WHISPER -> SpeechDecodeMode.WINDOWED_PARTIALS
    SpeechArtifactKind.VOSK -> SpeechDecodeMode.STREAMING_PARTIALS
    SpeechArtifactKind.PUBLISHER -> checkNotNull(item.publisher).profile.capabilities.let { capabilities ->
        when {
            capabilities.partialResults && capabilities.streaming == SpeechStreamingKind.CACHE_AWARE -> SpeechDecodeMode.STREAMING_PARTIALS
            capabilities.partialResults -> SpeechDecodeMode.WINDOWED_PARTIALS
            else -> SpeechDecodeMode.UTTERANCE_FINALS
        }
    }
}

internal fun speechFamilyTitle(id: String): String = when (id) {
    "nemotron" -> "Nemotron"
    "qwen3-asr" -> "Qwen3-ASR"
    "omnilingual" -> "Omnilingual CTC"
    "whisper" -> "Whisper"
    "moonshine" -> "Moonshine"
    "phonon" -> "Phonon"
    "vosk" -> "Vosk"
    "transcribepsy" -> "TranscribePsy"
    else -> id
}

/** A precision is an option of one checkpoint, not another model card. */
internal fun speechCheckpointTitle(item: SpeechArtifact): String = when (item.family) {
    "whisper" -> "Whisper " + item.checkpoint.removeSuffix(".en").split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    else -> item.label.substringBefore(" · INT8").substringBefore(" · Q8_0").substringBefore(" · F16")
}

internal fun preferredSpeechVariant(variants: List<SpeechArtifact>, installedIds: Set<String>, currentId: String): SpeechArtifact =
    variants.firstOrNull { it.id == currentId }
        ?: variants.firstOrNull { it.id in installedIds }
        ?: variants.minBy { it.bytes }

internal fun speechCheckpointGroups(artifacts: List<SpeechArtifact>, family: String, language: String,
    query: String, installedOnly: Boolean, installedIds: Set<String>): List<List<SpeechArtifact>> = artifacts.asSequence()
    .filter { family.isBlank() || it.family == family }
    .filter { language.isBlank() || language in it.languages }
    .filter { query.isBlank() || (it.label + " " + it.family + " " + it.checkpoint).contains(query.trim(), ignoreCase = true) }
    .groupBy { it.family to it.checkpoint }.values
    .filter { !installedOnly || it.any { variant -> variant.id in installedIds } }
    .sortedWith(compareByDescending<List<SpeechArtifact>> { group -> group.any { it.id in installedIds } }
        .thenByDescending { group -> group.mapNotNull { it.modelReleaseDate ?: it.artifactDate }.maxOrNull().orEmpty() }
        .thenBy { group -> group.minOf { it.bytes } })

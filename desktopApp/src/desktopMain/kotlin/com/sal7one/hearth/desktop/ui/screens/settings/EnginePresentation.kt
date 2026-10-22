package com.sal7one.hearth.desktop.ui.screens.settings

import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import java.nio.file.Path
import java.net.URI

/** Runtime labels identify only implementations actually wired by DesktopEngines. */
internal fun engineRuntimeLabel(engine: EngineDescriptor): String {
    val runtime = when (engine.id) {
        "whisper-local" -> "whisper.cpp"
        "qwen3-asr-local", "moonshine-local", "omnilingual-local", "phonon-local" -> "sherpa-onnx"
        "vosk-local" -> "Vosk / Kaldi"
        "transcribepsy-local" -> "parakeet.cpp"
        "nemotron-asr-local", "magpie-tts-local" -> "NeMo-Speech.cpp"
        "llama-translation-local" -> "llama.cpp"
        "marian-local" -> "CTranslate2"
        "supertonic-local", "moss-tts-local", "chatterbox-tts-local" -> "ONNX Runtime"
        "omnivoice-tts-local" -> "omnivoice.cpp"
        "ming-local" -> "Ming native"
        "ming-tts" -> "Self-hosted Ming endpoint"
        else -> when (engine.kind) {
            EngineKind.LOCAL -> "Local runtime"
            EngineKind.CLOUD -> "Provider endpoint"
            EngineKind.SYSTEM -> "Installed OS voices"
        }
    }
    return if (engine.kind == EngineKind.LOCAL) "$runtime · ${engine.acceleration.joinToString(" / ") { it.uppercase() }}" else runtime
}

internal fun verifiedSelectionMatches(selection: EngineSelection?, card: DesktopModelCard?, status: DesktopModelStatus?): Boolean {
    val verifiedPath = status?.path ?: return false
    if (selection == null || card == null || status.ready != true ||
        selection.engineId != card.engineId || selection.modelId != card.id || selection.modelPath.isBlank()) return false
    return runCatching { Path.of(selection.modelPath).toAbsolutePath().normalize() == verifiedPath.toAbsolutePath().normalize() }.getOrDefault(false)
}

/** This is connection configuration, not a probe of the provider or a decrypted-key claim. */
internal fun connectionConfigured(engine: EngineDescriptor?, selection: EngineSelection?): Boolean {
    if (engine == null || selection == null || !engine.available) return false
    val endpoint = selection.endpoint.ifBlank { engine.defaultEndpoint }
    val local = runCatching { URI(endpoint).host in setOf("localhost", "127.0.0.1", "::1", "[::1]") }.getOrDefault(false)
    return (!engine.requiresCredential || local || selection.credentialId.isNotBlank()) &&
        endpoint.isNotBlank() && selection.modelId.ifBlank { engine.defaultModel }.isNotBlank()
}

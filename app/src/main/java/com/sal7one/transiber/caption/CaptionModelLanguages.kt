package com.sal7one.transiber.caption

import android.content.Context
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.byok.CloudConfigStore
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.models.ModelRegistry
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Eight header bytes distinguish actual Whisper weights, even when the user renames the file. */
internal fun whisperVocabulary(input: InputStream): Int? {
    val bytes = ByteArray(8)
    var read = 0
    while (read < bytes.size) {
        val count = input.read(bytes, read, bytes.size - read)
        if (count <= 0) return null
        read += count
    }
    val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (header.int != 0x67676d6c) return null
    return header.int.takeIf { it in 51864..51866 }
}

internal fun captionLanguageModel(context: Context, config: CaptionOverlayConfig): CaptionLanguageModel {
    if (config.engine == CaptionEngineChoice.CLOUD) return CaptionLanguageModel(CloudConfigStore.sttModel(context))
    if (config.engine.speechBackend != null) {
        val selected = runCatching { LocalSpeechModels(java.io.File(context.filesDir, "speech-models")).select(config.engine, config.modelId) }.getOrNull()
        return CaptionLanguageModel(selected?.profile?.id ?: config.modelId, selected?.profile?.label ?: config.engine.label)
    }
    val type = if (config.engine == CaptionEngineChoice.VOSK) ModelEngineType.VOSK else ModelEngineType.WHISPER
    val candidates = ModelRegistry.getInstance(context).getModelsForEngine(type)
    val model = selectCaptionSpeechModel(candidates, config.modelId)
        ?: return if (config.modelId.isBlank()) CaptionLanguageModel("", "${config.engine.label} · no model selected")
        else CaptionLanguageModel(config.modelId, "${config.engine.label} · selected model unavailable")
    val vocab = if (type == ModelEngineType.WHISPER) runCatching {
        java.io.File(model.path).inputStream().use(::whisperVocabulary)
    }.getOrNull() else null
    val fixedLanguage = if (type == ModelEngineType.VOSK)
        com.sal7one.transiber.models.VoskDownloads.findByTreeSha256(model.digest?.hex)?.language else null
    return CaptionLanguageModel(model.id, model.name, vocab, fixedLanguage)
}

/** File headers/manifests are read off Main; returning from model setup refreshes the capability view. */
@androidx.compose.runtime.Composable
internal fun rememberCaptionLanguageModel(config: CaptionOverlayConfig): CaptionLanguageModel {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resumed = rememberCaptionSetupResumeRevision()
    val bindings = com.sal7one.transiber.preferences.ModelSourceBindings.checks.collectAsStateWithLifecycle().value
    val registered = ModelRegistry.getInstance(context).registeredModels.collectAsStateWithLifecycle().value
    val request = listOf(config.engine, config.modelId, config.speechSelectionRevision, resumed,
        bindings, registered, CloudConfigStore.sttModel(context))
    val state = androidx.compose.runtime.remember(request) {
        androidx.compose.runtime.mutableStateOf(CaptionLanguageModel(config.modelId))
    }
    androidx.compose.runtime.LaunchedEffect(request) {
        state.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { captionLanguageModel(context, config) }
    }
    return state.value
}

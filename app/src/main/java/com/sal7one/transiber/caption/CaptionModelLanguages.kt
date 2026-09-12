package com.sal7one.transiber.caption

import android.content.Context
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
    val model = candidates.firstOrNull { it.id == config.modelId } ?: candidates.firstOrNull()
        ?: return CaptionLanguageModel("", "${config.engine.label} · no model selected")
    val vocab = if (type == ModelEngineType.WHISPER) runCatching {
        java.io.File(model.path).inputStream().use(::whisperVocabulary)
    }.getOrNull() else null
    return CaptionLanguageModel(model.id, model.name, vocab)
}

/** File headers/manifests are read off Main; returning from model setup refreshes the capability view. */
@androidx.compose.runtime.Composable
internal fun rememberCaptionLanguageModel(config: CaptionOverlayConfig): CaptionLanguageModel {
    val context = androidx.compose.ui.platform.LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val revision = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) revision.intValue++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return androidx.compose.runtime.produceState(
        initialValue = CaptionLanguageModel(config.modelId),
        config.engine, config.modelId, revision.intValue, CloudConfigStore.sttModel(context),
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { captionLanguageModel(context, config) }
    }.value
}

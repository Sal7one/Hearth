package com.sal7one.transiber.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.models.LegacyModelSetup
import com.sal7one.transiber.models.ModelEngineType

@Composable
internal fun SettingsSpeechLocalUi(
    config: CaptionOverlayConfig,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit,
) {
    var browsing by rememberSaveable { mutableStateOf(config.engine.takeUnless { it == CaptionEngineChoice.CLOUD } ?: CaptionEngineChoice.NEMOTRON) }
    Text("Local speech recognition", style = MaterialTheme.typography.titleLarge)
    Text("Choose a speech engine, then select an installed model or get its files. Translation is a separate choice.", style = MaterialTheme.typography.bodyMedium)
    Text("Active speech: ${config.engine.label}", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            CaptionEngineChoice.NEMOTRON to "Nemotron",
            CaptionEngineChoice.QWEN to "Qwen3-ASR",
            CaptionEngineChoice.MOONSHINE to "Moonshine",
            CaptionEngineChoice.WHISPER to "Whisper",
            CaptionEngineChoice.VOSK to "Vosk",
        ).forEach { (engine, label) ->
            FilterChip(selected = browsing == engine, onClick = { browsing = engine },
                label = { Text(label) })
        }
    }
    val shown = config.copy(engine = browsing, modelId = config.modelId.takeIf { config.engine == browsing }.orEmpty())
    key(browsing) {
        val select: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit = { transform ->
            val next = transform(shown)
            browsing = next.engine
            update { it.copy(engine = next.engine, modelId = next.modelId, mode = next.mode, streamLanguage = next.streamLanguage) }
        }
        if (browsing.speechBackend != null) LocalSpeechSetup(shown, select, includeTranslation = false, onModelsChanged = {})
        else LegacyModelSetup(if (browsing == CaptionEngineChoice.WHISPER) ModelEngineType.WHISPER else ModelEngineType.VOSK, shown, select)
    }
}

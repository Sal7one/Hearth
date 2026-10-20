package com.sal7one.transiber.settings

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.preferences.*
import com.sal7one.transiber.setup.*
import com.sal7one.transiber.models.LegacyModelSetup
import com.sal7one.transiber.models.ModelEngineType
import com.sal7one.transiber.ui.components.ModelFilterPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSpeechLocalUi(config: CaptionOverlayConfig,
    repairRevision: Int = 0, repairEngine: CaptionEngineChoice? = null,
    showFullLibrary: Boolean = false,
    update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit) {
    val uiText = rememberUiText()
    if (!com.sal7one.transiber.runtime.LocalModelPolicy.allowed(androidx.compose.ui.platform.LocalContext.current)) {
        com.sal7one.transiber.ui.components.LocalModelPolicyNotice()
        return
    }
    if (!showFullLibrary && rememberSetupMode() == SetupMode.BASIC) {
        Text(uiText(UiR.string.setup_basic_selected_speech, uiText.label(config.engine)), style = MaterialTheme.typography.labelLarge)
        LocalBundleUi(config)
        AdvancedSetupLink()
        return
    }
    var managing by rememberSaveable { mutableStateOf(false) }
    var browsing by rememberSaveable { mutableStateOf(config.engine.takeUnless { it == CaptionEngineChoice.CLOUD } ?: CaptionEngineChoice.NEMOTRON) }
    LaunchedEffect(repairRevision) {
        if (repairRevision > 0) { managing = true; repairEngine?.let { browsing = it } }
    }
    SpeechArtifactBrowser(config, onManage = { engine ->
        engine?.takeUnless { it == CaptionEngineChoice.CLOUD }?.let { browsing = it }
        managing = true
    })
    if (!managing) return
    ModalBottomSheet(onDismissRequest = { managing = false }) {
    Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(uiText(UiR.string.library_model_options), style = MaterialTheme.typography.titleLarge)
    Text(uiText(UiR.string.model_browser_manage_hint), style = MaterialTheme.typography.bodySmall)
    val engines = listOf(
        CaptionEngineChoice.AUDAR to "Audar Flash",
        CaptionEngineChoice.OWSM to "OWSM v4 Base",
        CaptionEngineChoice.COHERE to "Cohere Arabic",
        CaptionEngineChoice.FUNASR_MLT to "Fun-ASR MLT Nano",
        CaptionEngineChoice.VOXTRAL to "Voxtral Realtime",
        CaptionEngineChoice.TRANSCRIBEPSY to "TranscribePsy 250M",
        CaptionEngineChoice.NEMOTRON to "Nemotron",
        CaptionEngineChoice.QWEN to "Qwen3-ASR",
        CaptionEngineChoice.OMNILINGUAL to "Omnilingual CTC",
        CaptionEngineChoice.PHONON to "Phonon 2",
        CaptionEngineChoice.MOONSHINE to "Moonshine",
        CaptionEngineChoice.WHISPER to "Whisper",
        CaptionEngineChoice.VOSK to "Vosk",
    )
    ModelFilterPicker(uiText(UiR.string.model_browser_engine), browsing.name, engines.map { it.first.name to it.second },
        { name -> browsing = engines.first { it.first.name == name }.first })
    var tuning by rememberSaveable(browsing) { mutableStateOf(false) }
    com.sal7one.transiber.ui.components.ModelLibraryDisclosure(uiText(UiR.string.library_advanced), tuning, { tuning = !tuning }) {
        SpeechAccelerationSetup(browsing, onApplied = {
            update { if (it.effectiveEngine == browsing) it.copy(speechSelectionRevision = it.speechSelectionRevision + 1) else it }
        })
    }
    val shown = config.copy(engine = browsing, modelId = config.modelId.takeIf { config.engine == browsing }.orEmpty())
    key(browsing) {
        val select: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit = { transform ->
            val next = transform(shown)
            browsing = next.engine
            update { it.copy(engine = next.engine, modelId = next.modelId, mode = next.mode, streamLanguage = next.streamLanguage) }
        }
        if (browsing.speechBackend != null) LocalSpeechSetup(shown, select, includeTranslation = false,
            showDownloadSources = false,
            onTuningApplied = { update { if (it.effectiveEngine == browsing) it.copy(speechSelectionRevision = it.speechSelectionRevision + 1) else it } }, onModelsChanged = {})
        else LegacyModelSetup(if (browsing == CaptionEngineChoice.WHISPER) ModelEngineType.WHISPER else ModelEngineType.VOSK,
            shown, select, showDownloadSources = false)
    }
    }
    }
}

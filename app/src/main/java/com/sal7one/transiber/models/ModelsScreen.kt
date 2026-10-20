package com.sal7one.transiber.models

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.preferences.*
import com.sal7one.transiber.setup.*
import kotlinx.coroutines.*

@Composable
fun ModelsScreen(onDownloads: () -> Unit = {}, onVoices: () -> Unit = {}, initialSection: String = "Speech") {
    val uiText = rememberUiText()

    val context = LocalContext.current
    if (!com.sal7one.transiber.runtime.LocalModelPolicy.allowed(context)) {
        TvCloudModelsUi(initialSection)
        return
    }
    val setupMode = rememberSetupMode()
    val scope = rememberCoroutineScope()
    val registry = remember { ModelRegistry.getInstance(context) }
    val modelScroll = rememberScrollState()
    var repairRevision by rememberSaveable { mutableIntStateOf(0) }
    var repairEngine by rememberSaveable { mutableStateOf<CaptionEngineChoice?>(null) }
    var repairModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var config by remember { mutableStateOf(CaptionOverlayConfig()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    LaunchedEffect(Unit) {
        try { CaptionConfigStore.config(context).collect {
            config = it
            if (!loaded) { loaded = true }
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit = { transform ->
        scope.launch { try { CaptionConfigStore.update(context, transform); error = null }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() } }
    }
    LaunchedEffect(Unit) {
        val browse = UserPreferences.store(context, "translation-browser")
        if (browse.getBoolean("open",false)) { section = "Translation"; browse.edit().remove("open").apply() }
    }
    if (setupMode == SetupMode.BASIC && section !in setOf("Camera", "Voices")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(uiText(UiR.string.model_library_title), style = MaterialTheme.typography.headlineSmall)
                if (TvControls.isTvDevice(context)) SetupModePicker()
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TabRow(if (section == "Translation") 1 else 0) {
                Tab(section == "Speech", onClick = { section = "Speech" }, text = { Text(uiText.modelSection("Speech")) })
                Tab(section == "Translation", onClick = { section = "Translation" }, text = { Text(uiText.modelSection("Translation")) })
            }
            if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            else key(section) { LocalBundleUi(config, onDownloads = onDownloads, translationOnly = section == "Translation") }
            HorizontalDivider()
            PortableSetupUi(
                onSpeechModels = { SetupModeStore.setMode(context, SetupMode.ADVANCED); section = "Speech" },
                onTranslationModels = { SetupModeStore.setMode(context, SetupMode.ADVANCED); section = "Translation" },
                onVoices = onVoices, onDownloads = onDownloads)
            AdvancedSetupLink()
        }
        return
    }
    val pages = rememberSaveableStateHolder()
    val sections = listOf("Speech", "Translation", "Camera", "Voices")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(uiText(UiR.string.model_explorer_title), style = MaterialTheme.typography.headlineSmall)
                Text(uiText(UiR.string.model_explorer_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ByokPolicy.FEATURE_BYOK) TextButton(onClick = onDownloads) { Text(uiText(UiR.string.model_download_queue)) }
        }
        ScrollableTabRow(selectedTabIndex = sections.indexOf(section).coerceAtLeast(0), edgePadding = 16.dp) {
            sections.forEach { group ->
                Tab(selected = section == group, onClick = { section = group }, text = { Text(uiText.modelSection(group)) })
            }
        }
        pages.SaveableStateProvider(section) {
            Column(Modifier.weight(1f).verticalScroll(modelScroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loaded) { if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
                when (section) {
                    "Voices" -> {
                        com.sal7one.transiber.settings.SettingsSpeechAloudUi(config, update, onOpenVoices = onVoices)
                    }
                    "Speech" -> {
                        com.sal7one.transiber.settings.SettingsSpeechLocalUi(config, repairRevision, repairEngine, update)
                    }
                    "Translation" -> {
                        Text(uiText(UiR.string.ui_local_translation_010cc), style = MaterialTheme.typography.titleLarge)
                        Text(uiText(UiR.string.ui_language_packs_or_text_models_for_on_device_translation_0d656), style = MaterialTheme.typography.bodySmall)
                        com.sal7one.transiber.settings.SettingsTranslateLocalUi(config, { change -> update { current -> change(current).copy(mode = current.mode, localTranslationEnabled = current.localTranslationEnabled) } }, includeLegacy = true, showCaptionControls = false, repairRevision = repairRevision, repairModelId = repairModelId)
                    }
                    "Camera" -> com.sal7one.transiber.settings.SettingsOcrLocalUi(onDownloads)

                }
                if (setupMode == SetupMode.ADVANCED) {
                    HorizontalDivider()
                    ManagedModelSourcesUi(onImport = { id -> scope.launch {
                        val registered = registry.getModel(id)
                        val translator = com.sal7one.common_jni.translation.TranslationCatalog.models.firstOrNull { it.id == id }
                        val pair = com.sal7one.transiber.translation.MarianPackage.pairs.firstOrNull { it.id == id || it.treeSha256 == registered?.digest?.hex }
                        if (translator != null || pair != null || registered?.engineType == ModelEngineType.TRANSLATE) {
                            section = "Translation"; repairModelId = translator?.id ?: pair?.id ?: id
                        } else {
                            section = "Speech"
                            repairEngine = when (registered?.engineType) {
                                ModelEngineType.WHISPER -> CaptionEngineChoice.WHISPER
                                ModelEngineType.VOSK -> CaptionEngineChoice.VOSK
                                else -> SpeechArtifactCatalog.find(id)?.engine ?: withContext(Dispatchers.IO) {
                                    runCatching { LocalSpeechModels(java.io.File(context.filesDir, "speech-models")).list()
                                        .firstOrNull { it.id == id }?.profile?.captionEngine }.getOrNull()
                                } ?: config.engine.takeIf { config.modelId == id }
                            }
                            if (repairEngine == null) error = "Choose the model family for $id, then import a verified replacement."
                        }
                        repairRevision++
                        modelScroll.scrollTo(0)
                    } })
                }
            }
        }
    }
}

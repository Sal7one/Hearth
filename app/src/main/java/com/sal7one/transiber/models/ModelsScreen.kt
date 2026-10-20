package com.sal7one.transiber.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.i18n.*
import com.sal7one.transiber.preferences.*
import com.sal7one.transiber.setup.*
import kotlinx.coroutines.*

/** One library: bundles are the simple entry, the same model cards serve both setup modes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(onDownloads: () -> Unit = {}, onVoices: () -> Unit = {}, initialSection: String = "Speech") {
    val text = rememberUiText()
    val context = LocalContext.current
    if (!com.sal7one.transiber.runtime.LocalModelPolicy.allowed(context)) {
        TvCloudModelsUi(initialSection)
        return
    }
    val setupMode = rememberSetupMode()
    val scope = rememberCoroutineScope()
    val registry = remember { ModelRegistry.getInstance(context) }
    var repairRevision by rememberSaveable { mutableIntStateOf(0) }
    var repairEngine by rememberSaveable { mutableStateOf<CaptionEngineChoice?>(null) }
    var repairModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var config by remember { mutableStateOf(CaptionOverlayConfig()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val sections = listOf("Speech", "Translation", "Camera", "Voices")
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection.takeIf { it in sections } ?: "Speech") }
    var browseAll by rememberModelBrowseBoolean("browse-all", false)
    var tools by remember { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { CaptionConfigStore.config(context).collect { config = it; loaded = true } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val update: ((CaptionOverlayConfig) -> CaptionOverlayConfig) -> Unit = { transform -> scope.launch {
        try { CaptionConfigStore.update(context, transform); error = null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.toString() }
    } }
    LaunchedEffect(Unit) {
        val browse = UserPreferences.store(context, "translation-browser")
        if (browse.getBoolean("open", false)) {
            section = "Translation"; browseAll = true; browse.edit().remove("open").apply()
        }
    }
    val pages = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(text(UiR.string.model_library_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            if (ByokPolicy.FEATURE_BYOK) TextButton(onClick = onDownloads) { Text(text(UiR.string.model_download_queue)) }
            Box {
                IconButton(onClick = { tools = true }) { Icon(Icons.Default.MoreVert, text(UiR.string.library_tools)) }
                DropdownMenu(tools, onDismissRequest = { tools = false }) {
                    DropdownMenuItem(text = { Text(text(UiR.string.library_recommended)) }, onClick = { tools = false; sheet = "bundles" })
                    DropdownMenuItem(text = { Text(text(UiR.string.library_portable)) }, onClick = { tools = false; sheet = "portable" })
                    DropdownMenuItem(text = { Text(text(UiR.string.library_integrity)) }, onClick = { tools = false; sheet = "integrity" })
                }
            }
        }
        ScrollableTabRow(sections.indexOf(section), edgePadding = 12.dp) {
            sections.forEach { name -> Tab(section == name, onClick = { if (name == "Voices") onVoices() else section = name }, text = { Text(text.modelSection(name)) }) }
        }
        pages.SaveableStateProvider(section) {
            val scroll = rememberScrollState()
            LaunchedEffect(repairRevision) { if (repairRevision > 0) scroll.scrollTo(0) }
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loaded) { if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
                val simpleTask = section == "Speech" || section == "Translation"
                if (setupMode == SetupMode.BASIC && simpleTask) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!browseAll, onClick = { browseAll = false }, label = { Text(text(UiR.string.library_recommended)) })
                    FilterChip(browseAll, onClick = { browseAll = true }, label = { Text(text(UiR.string.library_all_models)) })
                }
                if (setupMode == SetupMode.BASIC && simpleTask && !browseAll) {
                    LocalBundleUi(config, onDownloads = onDownloads, translationOnly = section == "Translation", showHeading = false)
                } else when (section) {
                    "Speech" -> com.sal7one.transiber.settings.SettingsSpeechLocalUi(config, repairRevision, repairEngine, showFullLibrary = true, update = update)
                    "Translation" -> com.sal7one.transiber.settings.SettingsTranslateLocalUi(config,
                        { change -> update { current -> change(current).copy(mode = current.mode, localTranslationEnabled = current.localTranslationEnabled) } },
                        includeLegacy = true, showCaptionControls = false, repairRevision = repairRevision, repairModelId = repairModelId, showFullLibrary = true)
                    "Camera" -> com.sal7one.transiber.settings.SettingsOcrLocalUi(onDownloads)
                    "Voices" -> com.sal7one.transiber.settings.SettingsSpeechAloudUi(config, update, onOpenVoices = onVoices)
                }
            }
        }
    }
    if (sheet.isNotEmpty()) ModalBottomSheet(onDismissRequest = { sheet = "" }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (sheet) {
                "bundles" -> LocalBundleUi(config, onDownloads = onDownloads, translationOnly = section == "Translation")
                "portable" -> PortableSetupUi(
                    onSpeechModels = { browseAll = true; section = "Speech"; sheet = "" },
                    onTranslationModels = { browseAll = true; section = "Translation"; sheet = "" },
                    onVoices = { sheet = ""; onVoices() }, onDownloads = { sheet = ""; onDownloads() })
                "integrity" -> ManagedModelSourcesUi(initialExpanded = true, onImport = { id -> scope.launch {
                    try {
                    if (com.sal7one.transiber.voice.VoiceCatalog.find(id) != null || id.startsWith("voice-magpie-tokenizer")) {
                        sheet = ""; onVoices(); return@launch
                    }
                    val registered = registry.getModel(id)
                    val translator = com.sal7one.common_jni.translation.TranslationCatalog.models.firstOrNull { it.id == id }
                    val ct2 = com.sal7one.common_jni.translation.Ct2Catalog.find(id)
                    val pair = com.sal7one.transiber.translation.MarianPackage.pairs.firstOrNull { it.id == id || it.treeSha256 == registered?.digest?.hex }
                    if (translator != null || ct2 != null || pair != null || registered?.engineType == ModelEngineType.TRANSLATE) {
                        section = "Translation"; repairModelId = translator?.id ?: ct2?.id ?: pair?.id ?: id
                    } else {
                        section = "Speech"
                        repairEngine = when (registered?.engineType) {
                            ModelEngineType.WHISPER -> CaptionEngineChoice.WHISPER
                            ModelEngineType.VOSK -> CaptionEngineChoice.VOSK
                            else -> SpeechArtifactCatalog.find(id)?.engine ?: withContext(Dispatchers.IO) {
                                LocalSpeechModels(java.io.File(context.filesDir, "speech-models")).inspect().models.firstOrNull { it.id == id }?.profile?.captionEngine
                            } ?: config.engine.takeIf { config.modelId == id }
                        }
                        if (repairEngine == null) error = "Choose the model family for $id, then import a verified replacement."
                    }
                    browseAll = true; sheet = ""; repairRevision++
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: e.toString(); sheet = "" }
                } })
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

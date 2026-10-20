package com.sal7one.hearth.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.core.*
import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import java.awt.Component
import java.nio.file.Path

internal fun PipelineConfig.selection(stage: PipelineStage): EngineSelection? = when (stage) {
    PipelineStage.STT -> speech
    PipelineStage.TRANSLATION -> translation
    PipelineStage.TTS -> voice
}

internal fun PipelineConfig.select(stage: PipelineStage, selection: EngineSelection?): PipelineConfig = when (stage) {
    PipelineStage.STT -> copy(speech = selection)
    PipelineStage.TRANSLATION -> copy(translation = selection)
    PipelineStage.TTS -> copy(voice = selection, voiceId = if (voice?.engineId == selection?.engineId) voiceId else "")
}

internal fun PipelineConfig.useVerifiedModel(stage: PipelineStage, verified: EngineSelection): PipelineConfig {
    val previous = selection(stage)?.takeIf { it.engineId == verified.engineId }
    return select(stage, verified.copy(options = previous?.options.orEmpty() + verified.options))
}

internal fun engineSelection(engine: EngineDescriptor) = EngineSelection(engine.id,
    modelId = engine.defaultModel, endpoint = engine.defaultEndpoint,
    credentialId = if (engine.requiresCredential) "${engine.stage.name.lowercase()}-${engine.id}" else "")

@Composable
internal fun HearthDesktopApp(
    controller: DesktopWorkspaceController,
    parent: Component?,
    initialUi: Map<String, String>,
    saveUi: (Map<String, String>) -> Unit,
    credentialStorageDescription: String,
    models: DesktopModelController,
    closing: Boolean,
) {
    val config by controller.config.collectAsState()
    val state by controller.state.collectAsState()
    var page by remember { mutableStateOf(DesktopPage.WORKSPACE) }
    var modelTab by remember { mutableIntStateOf(0) }
    var workspaceDraft by remember { mutableStateOf(WorkspaceDraft()) }
    var modelFilters by remember { mutableStateOf(ModelFilters(
        stage = PipelineStage.entries.firstOrNull { it.name == initialUi["model-stage"] },
        language = initialUi["model-language"]?.takeIf(String::isNotBlank),
        onlyInstalled = initialUi["model-installed"] == "true",
    )) }
    var uiPreferences by remember { mutableStateOf(initialUi) }
    fun rememberPreference(values: Map<String, String>) {
        uiPreferences = uiPreferences + values
        saveUi(uiPreferences)
    }
    var overlay by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(initialUi["overlay-original"] != "false") }
    var showPendingOriginal by remember { mutableStateOf(initialUi["overlay-pending-original"] != "false") }
    var input by remember { mutableStateOf(WorkspaceInput.entries.firstOrNull { it.name == initialUi["input"] } ?: WorkspaceInput.AUDIO_FILE) }
    val engines = controller.capabilities
    HearthDesktopTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(196.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Image(painterResource("hearth.png"), "Hearth", Modifier.size(44.dp))
                        Text("Hearth", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Text("Speech. Translation. Voice.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    DesktopPage.entries.forEach { destination ->
                        if (page == destination) Button(onClick = { page = destination }, modifier = Modifier.fillMaxWidth()) { Text(destination.title) }
                        else TextButton(onClick = { page = destination }, modifier = Modifier.fillMaxWidth()) { Text(destination.title) }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Your stages stay independent.", style = MaterialTheme.typography.bodySmall)
                    Text("${System.getProperty("os.name")} · ${System.getProperty("os.arch")}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(page.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                            Text(when (page) {
                                DesktopPage.WORKSPACE -> "Choose an input, then run the stages you need."
                                DesktopPage.MODELS -> "Use your own models and provider connections."
                                DesktopPage.INTEGRATION -> "Share captions with OBS and connect other desktop apps."
                            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (closing) { CircularProgressIndicator(Modifier.size(20.dp)); Text("Closing safely…", Modifier.padding(start = 8.dp)) }
                    }
                    val error = listOfNotNull(state.error, state.snapshot?.error).distinct().joinToString("\n")
                    if (error.isNotBlank()) MessageCard(error, failure = true,
                        onDismiss = if (state.snapshot?.error == null) controller::clearNotice else null)
                    else state.notice?.let { MessageCard(it, failure = false, onDismiss = controller::clearNotice) }
                    if (state.importingSetup) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Verifying installed models for the imported setup…", style = MaterialTheme.typography.bodySmall)
                    }
                    when (page) {
                        DesktopPage.WORKSPACE -> WorkspaceUi(controller, config, state, engines, parent,
                            draft = workspaceDraft, onDraft = { workspaceDraft = it },
                            input = input, onInput = {
                                input = it
                                rememberPreference(mapOf("input" to it.name))
                            }, onConfigure = { modelTab = 1; page = DesktopPage.MODELS },
                            onBrowseModels = { stage ->
                                modelFilters = modelFilters.copy(stage = stage, query = "", onlyInstalled = false)
                                modelTab = 0; page = DesktopPage.MODELS
                            },
                            overlay = overlay, onOverlay = { overlay = it }, showOriginal = showOriginal, onOriginal = {
                                showOriginal = it
                                rememberPreference(mapOf("overlay-original" to it.toString()))
                            }, showPendingOriginal = showPendingOriginal, onPendingOriginal = {
                                showPendingOriginal = it
                                rememberPreference(mapOf("overlay-pending-original" to it.toString()))
                            })
                        DesktopPage.MODELS -> ModelProviderUi(controller, config, engines, parent, credentialStorageDescription,
                            models, modelTab, onTab = { modelTab = it }, filters = modelFilters, onFilters = {
                                val previous = modelFilters
                                modelFilters = it
                                if (previous.stage != it.stage || previous.language != it.language || previous.onlyInstalled != it.onlyInstalled) {
                                    rememberPreference(mapOf("model-stage" to it.stage?.name.orEmpty(),
                                        "model-language" to it.language.orEmpty(), "model-installed" to it.onlyInstalled.toString()))
                                }
                            })
                        DesktopPage.INTEGRATION -> IntegrationUi(controller, state, parent)
                    }
                }
            }
        }
    }
    if (overlay) CaptionOverlayWindow(state.snapshot, showOriginal, config.translation != null, showPendingOriginal,
        onClose = { overlay = false })
}

@Composable
internal fun MessageCard(message: String, failure: Boolean, onDismiss: (() -> Unit)?) {
    Surface(color = if (failure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) { Text(message) }
            onDismiss?.let { TextButton(onClick = it) { Text("Dismiss") } }
        }
    }
}

@Composable
internal fun SectionCard(title: String, detail: String? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
internal fun EnginePicker(
    stage: PipelineStage, selection: EngineSelection?, engines: List<EngineDescriptor>,
    enabled: Boolean = true, onSelect: (EngineSelection?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = engines.firstOrNull { it.id == selection?.engineId }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.name ?: selection?.engineId ?: if (stage == PipelineStage.STT) "Choose speech engine" else "Off")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(if (stage == PipelineStage.STT) "Not configured" else "Off") },
                onClick = { expanded = false; onSelect(null) })
            engines.filter { it.stage == stage }.forEach { engine ->
                DropdownMenuItem(text = {
                    Column {
                        Text(engine.name)
                        Text(if (engine.available) engine.kind.name.lowercase().replaceFirstChar(Char::uppercase)
                            else engine.unavailableReason ?: "Unavailable on this computer", style = MaterialTheme.typography.bodySmall)
                    }
                }, onClick = { expanded = false; onSelect(if (engine.id == selection?.engineId) selection else engineSelection(engine)) })
            }
        }
    }
}

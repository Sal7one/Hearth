package com.sal7one.hearth.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.core.*
import com.sal7one.hearth.desktop.data.DesktopModelLibrary
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.runtime.DesktopSystemSpeech
import com.sal7one.hearth.desktop.runtime.DesktopSystemVoice
import kotlinx.coroutines.*
import java.awt.Component
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.util.Locale

@Composable
internal fun ModelProviderUi(controller: DesktopWorkspaceController, config: PipelineConfig,
    engines: List<EngineDescriptor>, parent: Component?, storage: String, models: DesktopModelController,
    tab: Int, onTab: (Int) -> Unit, filters: ModelFilters, onFilters: (ModelFilters) -> Unit) {
    val workspaceState by controller.state.collectAsState()
    val modelState by models.state.collectAsState()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { onTab(0) }, label = { Text("Model library") })
            FilterChip(tab == 1, { onTab(1) }, label = { Text("Connections & paths") })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { chooseFile(parent, "Import a Hearth setup", listOf("json"))?.let { controller.importSetup(it) } },
                enabled = !workspaceState.importingSetup && !modelState.refreshing && modelState.transfer == null) { Text("Import setup…") }
            TextButton(onClick = { chooseSaveFile(parent, "Export a portable setup", "hearth-setup.json")?.let { controller.exportSetup(it) } }) { Text("Export setup…") }
        }
        if (tab == 0) ModelLibraryUi(controller, config, engines, parent, models, filters, onFilters,
            enabled = !workspaceState.importingSetup)
        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Provider keys use $storage. Portable setups contain no keys or machine-specific paths.", style = MaterialTheme.typography.bodySmall)
            PipelineStage.entries.forEach { stage ->
                StageConnectionUi(controller, config, stage, engines, parent)
            }
            SectionCard("Local runtime status") {
                engines.filter { it.kind != EngineKind.CLOUD }.forEach { engine ->
                    Text("${engine.name}: ${if (engine.available) "available" else engine.unavailableReason ?: "unavailable"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (engine.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                }
                Text("Models and platform runtime libraries are separate. An engine can require a platform adapter as well as a verified model.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { try { openPath(controller.dataDirectory) } catch(e: Exception) { controller.showError(e.message ?: e.toString()) } }) {
                    Text("Open Hearth data folder")
                }
            }
        }
    }
}

@Composable
private fun StageConnectionUi(controller: DesktopWorkspaceController, config: PipelineConfig, stage: PipelineStage,
    engines: List<EngineDescriptor>, parent: Component?) {
    val selected = config.selection(stage)
    var draft by remember(selected) { mutableStateOf(selected) }
    val descriptor = engines.firstOrNull { it.id == draft?.engineId }
    var secret by remember(draft?.credentialId) { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var options by remember(draft?.engineId, selected?.options) { mutableStateOf(draft?.options.orEmpty().entries.joinToString("\n") { "${it.key}=${it.value}" }) }
    var voiceId by remember(config.voiceId) { mutableStateOf(config.voiceId) }
    var speakTranslation by remember(config.speakTranslatedText) { mutableStateOf(config.speakTranslatedText) }
    var systemVoices by remember { mutableStateOf<List<DesktopSystemVoice>>(emptyList()) }
    var voicesLoading by remember { mutableStateOf(false) }
    var voicesIssue by remember { mutableStateOf<String?>(null) }
    var voicesRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(stage, draft?.engineId, voicesRefresh) {
        if (stage == PipelineStage.TTS && draft?.engineId == "system-tts") {
            voicesLoading = true; voicesIssue = null
            try { systemVoices = DesktopSystemSpeech.voices() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { voicesIssue = e.message ?: e.toString() }
            finally { voicesLoading = false }
        } else systemVoices = emptyList()
    }
    SectionCard(when (stage) { PipelineStage.STT -> "Speech recognition"; PipelineStage.TRANSLATION -> "Translation · optional"; PipelineStage.TTS -> "Voice output · optional" }) {
        EnginePicker(stage, draft, engines) {
            if (stage == PipelineStage.TTS && it?.engineId != draft?.engineId) voiceId = ""
            draft = it; options = it?.options.orEmpty().entries.joinToString("\n") { "${it.key}=${it.value}" }
        }
        descriptor?.takeIf { !it.available }?.let { Text(it.unavailableReason ?: "This adapter is not available", color = MaterialTheme.colorScheme.error) }
        val route = draft
        if (route != null) {
            if (descriptor?.requiresModel == true || route.modelPath.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(route.modelPath, { draft = route.copy(modelPath = it) }, label = { Text("Model file or folder") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { chooseFile(parent, "Choose model file or folder", directories = true)?.let { draft = route.copy(modelPath = it.toString()) } }) { Text("Browse…") }
                }
                Text("The model library supplies verified paths and checksums. Manual paths are validated by the runtime before use.", style = MaterialTheme.typography.bodySmall)
            }
            if (descriptor?.kind == EngineKind.CLOUD) {
                OutlinedTextField(route.endpoint, { draft = route.copy(endpoint = it) }, label = { Text("Provider endpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(route.modelId, { draft = route.copy(modelId = it) }, label = { Text("Provider model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(route.credentialId, { draft = route.copy(credentialId = it) }, label = { Text("Credential reference") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text("Provider API key") }, visualTransformation = PasswordVisualTransformation(),
                    supportingText = { Text("Saved separately. Existing keys are never displayed here.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val submitted = secret
                        controller.saveCredential(route.credentialId, submitted) { if (secret == submitted) secret = "" }
                    }, enabled = secret.isNotBlank() && route.credentialId.isNotBlank()) { Text("Save key") }
                    TextButton(onClick = { controller.forgetCredential(route.credentialId) }, enabled = route.credentialId.isNotBlank()) { Text("Forget key") }
                }
            }
            if (stage == PipelineStage.TTS) {
                when (route.engineId) {
                    "supertonic-local" -> VoiceChoicePicker(voiceId, listOf(VoiceChoice("", "Default · Female 1 (F1)")) +
                        (1..5).map { VoiceChoice("F$it", "Female $it (F$it)") } +
                        (1..5).map { VoiceChoice("M$it", "Male $it (M$it)") }, onSelect = { voiceId = it })
                    "system-tts" -> {
                        if (voicesLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading installed voices…") }
                        VoiceChoicePicker(voiceId, listOf(VoiceChoice("", "Automatic for the spoken language")) + systemVoices
                            .sortedWith(compareBy({ it.language }, { it.name })).map {
                                val gender = when (it.gender.lowercase()) { "female" -> " · Female"; "male" -> " · Male"; else -> "" }
                                VoiceChoice(it.id, "${it.name} · ${languageLabel(it.language)}$gender")
                            }, enabled = !voicesLoading, onSelect = { voiceId = it })
                        voicesIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { voicesRefresh++ }, enabled = !voicesLoading) { Text(if (voicesIssue == null) "Refresh installed voices" else "Retry loading voices") }
                    }
                    else -> Text(if (voiceId.isBlank()) "Uses the provider's default voice. Choose a custom ID in Advanced engine options."
                        else "Voice: $voiceId", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Read the translation when available", Modifier.weight(1f))
                    Switch(speakTranslation, { speakTranslation = it })
                }
                Text(if (speakTranslation) "Without translation, voice output reads the original text."
                    else "Voice output reads the original text while translation still appears in captions.", style = MaterialTheme.typography.bodySmall)
            }
            if (descriptor?.acceleration?.contains("metal") == true) {
                val acceleration = runCatching { parseEngineOptions(options)["acceleration"] }.getOrNull() ?: "cpu"
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Run on", style = MaterialTheme.typography.bodySmall)
                    listOf("cpu" to "CPU", "metal" to "Metal GPU").forEach { (id, label) ->
                        FilterChip(acceleration == id, onClick = {
                            try { options = (parseEngineOptions(options) + ("acceleration" to id)).entries.joinToString("\n") { "${it.key}=${it.value}" } }
                            catch (e: Exception) { controller.showError(e.message ?: e.toString()) }
                        }, label = { Text(label) })
                    }
                }
                Text("CPU and Metal use the same selected model. Speed depends on the model and this computer.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide engine options" else "Advanced engine options") }
            if (advanced) {
                if (stage == PipelineStage.TTS) OutlinedTextField(voiceId, { voiceId = it }, label = { Text("Custom voice ID") },
                    singleLine = true, supportingText = { Text("Use an exact ID supported by this engine or provider.") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(options, { options = it }, label = { Text("Options · one name=value per line") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("Examples: threads=2, promptMode=riva, sha256=<publisher checksum>. Never put provider keys in engine options.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = {
            try {
                val next = draft?.copy(options = parseEngineOptions(options))
                controller.updateConfig { previous -> previous.select(stage, next).let {
                    if (stage == PipelineStage.TTS) it.copy(voiceId = voiceId, speakTranslatedText = speakTranslation) else it
                } }
            } catch(e: Exception) { controller.showError(e.message ?: e.toString()) }
        }) { Text(if (draft == null) "Turn this stage off" else "Use this ${when(stage) { PipelineStage.STT -> "speech engine"; PipelineStage.TRANSLATION -> "translator"; PipelineStage.TTS -> "voice engine" }}") }
    }
}

@Composable
private fun ModelLibraryUi(controller: DesktopWorkspaceController, config: PipelineConfig, engines: List<EngineDescriptor>,
    parent: Component?, models: DesktopModelController, filters: ModelFilters, onFilters: (ModelFilters) -> Unit,
    enabled: Boolean) {
    val library = models.library
    val installed by library.installations.collectAsState()
    val modelState by models.state.collectAsState()
    val query = filters.query
    val stage = filters.stage
    val onlyInstalled = filters.onlyInstalled
    val languages = remember(library.catalog) { library.catalog.flatMap { it.languages }.filter { it != "multilingual" }.distinct().sortedBy(::languageLabel) }
    var languageMenu by remember { mutableStateOf(false) }
    var languageQuery by remember { mutableStateOf("") }
    val transfer = modelState.transfer
    val verifiedImports = modelState.verifiedImports
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(query, { onFilters(filters.copy(query = it)) }, label = { Text("Search models, families or languages") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(stage == null, { onFilters(filters.copy(stage = null)) }, label = { Text("All stages") })
            PipelineStage.entries.forEach { item -> FilterChip(stage == item, { onFilters(filters.copy(stage = item)) }, label = { Text(when(item) {
                PipelineStage.STT -> "Speech"; PipelineStage.TRANSLATION -> "Translation"; PipelineStage.TTS -> "Voice"
            }) }) }
            FilterChip(onlyInstalled, { onFilters(filters.copy(onlyInstalled = !onlyInstalled)) }, label = { Text("Installed / verified") })
            Box {
                OutlinedButton(onClick = { languageMenu = true }) { Text(filters.language?.let(::languageLabel) ?: "All languages") }
                DropdownMenu(languageMenu, { languageMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                    OutlinedTextField(languageQuery, { languageQuery = it }, label = { Text("Find language") },
                        singleLine = true, modifier = Modifier.width(260.dp).padding(horizontal = 12.dp))
                    DropdownMenuItem(text = { Text("All languages") }, onClick = { onFilters(filters.copy(language = null)); languageMenu = false })
                    languages.filter { languageLabel(it).contains(languageQuery, true) }.forEach { language -> DropdownMenuItem(text = { Text(languageLabel(language)) },
                        onClick = { onFilters(filters.copy(language = language)); languageMenu = false }) }
                }
            }
        }
        if (modelState.refreshing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Checking saved model files…", style = MaterialTheme.typography.bodySmall)
        }
        val cards = library.catalog.filter { card ->
            (stage == null || card.stage == stage) && (!onlyInstalled || installed[card.id]?.ready == true || card.id in verifiedImports) &&
                modelSupportsLanguage(card.languages, filters.language) &&
                (query.isBlank() || listOf(card.name, card.family, card.id, card.languages.joinToString(" ", transform = ::languageLabel)).any { it.contains(query, ignoreCase = true) })
        }
        // Filters must never strand an in-flight operation without its cancellation control.
        if (transfer != null && cards.none { it.id == transfer.id }) {
            SectionCard(library.find(transfer.id).name) {
                ModelTransferUi(transfer, models::cancel)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (cards.isEmpty()) Text("No models match these filters.")
            cards.groupBy { it.family }.forEach { (_, variants) ->
                var choice by remember(variants.map { it.id }) { mutableStateOf(
                    variants.firstOrNull { it.id == transfer?.id }?.id
                        ?: variants.firstOrNull { it.id == config.selection(it.stage)?.modelId }?.id ?: variants.first().id) }
                val card = variants.firstOrNull { it.id == choice } ?: variants.first()
                val currentTransfer = transfer?.takeIf { it.id == card.id }
                val verified = verifiedImports[card.id] ?: library.installedSelection(card.id)
                val current = config.selection(card.stage)
                val active = verified != null && isCurrentModelSelection(current, verified)
                var menu by remember { mutableStateOf(false) }
                var details by remember { mutableStateOf(false) }
                val languageSummary = when {
                    card.languages.isEmpty() -> "Model-dependent languages"
                    "multilingual" in card.languages -> "Multiple languages"
                    card.languages.size > 4 -> "${card.languages.size} languages"
                    else -> card.languages.joinToString(", ", transform = ::languageLabel)
                }
                SectionCard(card.family.replaceFirstChar(Char::uppercase), "${when(card.stage) { PipelineStage.STT -> "Speech recognition"; PipelineStage.TRANSLATION -> "Translation"; PipelineStage.TTS -> "Voice" }} · $languageSummary") {
                    if (variants.size > 1) Box {
                        OutlinedButton(onClick = { menu = true }, enabled = currentTransfer == null, modifier = Modifier.fillMaxWidth()) { Text("${card.name} · ${formatBytes(card.bytes)}") }
                        DropdownMenu(menu, { menu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                            variants.forEach { variant -> DropdownMenuItem(text = { Text("${variant.name} · ${formatBytes(variant.bytes)}") }, onClick = { choice = variant.id; menu = false }) }
                        }
                    } else Text("${card.name} · ${formatBytes(card.bytes)}")
                    Text(card.note, style = MaterialTheme.typography.bodySmall)
                    engines.firstOrNull { it.id == card.engineId }?.takeIf { !it.available }?.let {
                        Text(it.unavailableReason ?: "Runtime adapter unavailable", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    val issue = modelState.issues[card.id] ?: installed[card.id]?.issue
                    issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (currentTransfer != null) {
                        ModelTransferUi(currentTransfer, models::cancel)
                    } else if (verified != null) {
                        Text(if (active) "Selected for this stage" else "Verified and ready", color = MaterialTheme.colorScheme.secondary)
                        Button(onClick = { controller.updateConfig { it.useVerifiedModel(card.stage, verified) } }, enabled = !active && enabled) { Text(if (active) "In use" else "Use for ${when(card.stage) { PipelineStage.STT -> "speech"; PipelineStage.TRANSLATION -> "translation"; PipelineStage.TTS -> "voice" }}") }
                    } else Button(onClick = { models.download(card.id) }, enabled = enabled && transfer == null && !modelState.refreshing) {
                        Text("${if (issue == null) "Download" else "Retry download"} · ${formatBytes(card.bytes)}")
                    }
                    TextButton(onClick = { details = !details }) { Text(if (details) "Hide model details" else "Model details & existing files") }
                    if (details) {
                        SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("License: ${card.license}", style = MaterialTheme.typography.bodySmall)
                            Text("Languages: ${card.languages.joinToString(", ", transform = ::languageLabel).ifEmpty { "Model-dependent" }}", style = MaterialTheme.typography.bodySmall)
                            Text(card.source, style = MaterialTheme.typography.bodySmall)
                            card.artifacts.forEach { Text("${it.filename} · ${formatBytes(it.bytes)}\nSHA-256 ${it.sha256}", style = MaterialTheme.typography.bodySmall) }
                        } }
                        TextButton(onClick = { try { Desktop.getDesktop().browse(URI(card.source)) } catch(e: Exception) { controller.showError(e.message ?: e.toString()) } }) { Text("Open publisher source") }
                        OutlinedButton(onClick = {
                            chooseFile(parent, if (card.artifacts.size == 1) "Locate ${card.artifacts.single().filename}" else "Locate complete ${card.name} folder", directories = card.artifacts.size != 1)?.let { path ->
                                models.verify(card.id, path)
                            }
                        }, enabled = enabled && transfer == null && !modelState.refreshing) { Text("Verify existing files…") }
                    }
                }
            }
        }
    }
}

private data class VoiceChoice(val id: String, val label: String)

@Composable
private fun VoiceChoicePicker(value: String, choices: List<VoiceChoice>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(choices.firstOrNull { it.id == value }?.label ?: "Custom voice: $value")
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            if (choices.size > 12) OutlinedTextField(search, { search = it }, label = { Text("Find voice or language") },
                singleLine = true, modifier = Modifier.width(320.dp).padding(horizontal = 12.dp))
            choices.filter { it.label.contains(search, true) || it.id.contains(search, true) }.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, onClick = { onSelect(choice.id); expanded = false })
            }
        }
    }
}

@Composable
private fun ModelTransferUi(transfer: ModelTransfer, onCancel: () -> Unit) {
    if (transfer.total > 0 && !transfer.checking) {
        LinearProgressIndicator(progress = { (transfer.downloaded.toDouble() / transfer.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("${formatBytes(transfer.downloaded)} of ${formatBytes(transfer.total)}", style = MaterialTheme.typography.bodySmall)
    } else LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(if (transfer.checking || transfer.downloaded >= transfer.total && transfer.total > 0) "Verifying model files…" else "Downloading model…")
    OutlinedButton(onClick = onCancel, enabled = !transfer.cancelling) { Text(if (transfer.cancelling) "Cancelling…" else "Cancel") }
}

internal fun formatBytes(bytes: Long): String = if (bytes >= 1_000_000_000) String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)
    else String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)

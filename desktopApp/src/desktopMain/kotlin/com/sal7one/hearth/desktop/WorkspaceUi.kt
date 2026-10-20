package com.sal7one.hearth.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sal7one.hearth.core.*
import java.awt.Component

@Composable
internal fun ColumnScope.WorkspaceUi(
    controller: DesktopWorkspaceController, config: PipelineConfig, state: DesktopUiState,
    engines: List<EngineDescriptor>, parent: Component?, input: WorkspaceInput, onInput: (WorkspaceInput) -> Unit,
    draft: WorkspaceDraft, onDraft: (WorkspaceDraft) -> Unit,
    onConfigure: () -> Unit, onBrowseModels: (PipelineStage) -> Unit,
    overlay: Boolean, onOverlay: (Boolean) -> Unit,
    showOriginal: Boolean, onOriginal: (Boolean) -> Unit,
    showPendingOriginal: Boolean, onPendingOriginal: (Boolean) -> Unit,
) {
    val text = draft.text
    val audioPath = draft.audioPath
    val deviceId = draft.deviceId
    var deviceMenu by remember { mutableStateOf(false) }
    var source by remember(config.sourceLanguage) { mutableStateOf(config.sourceLanguage) }
    var target by remember(config.targetLanguage) { mutableStateOf(config.targetLanguage) }
    LaunchedEffect(Unit) { controller.refreshDevices() }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PipelineStage.entries.forEach { stage ->
            val selection = config.selection(stage)
            val engine = engines.firstOrNull { it.id == selection?.engineId }
            OutlinedCard(Modifier.weight(1f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when(stage) { PipelineStage.STT -> "1  Recognize"; PipelineStage.TRANSLATION -> "2  Translate"; PipelineStage.TTS -> "3  Speak" },
                        style = MaterialTheme.typography.labelLarge)
                    EnginePicker(stage, selection, engines, enabled = !state.active) { next -> controller.updateConfig { it.select(stage, next) } }
                    Text(when {
                        engine?.available == false -> engine.unavailableReason ?: "Runtime unavailable"
                        selection == null && stage != PipelineStage.STT -> "Optional · currently off"
                        selection == null -> "Needed for audio input"
                        engine?.requiresModel == true && selection.modelPath.isBlank() -> "Choose a verified model to run locally"
                        engine?.kind == EngineKind.CLOUD -> "Uses your provider connection"
                        else -> "Runs on this computer"
                    }, style = MaterialTheme.typography.bodySmall, color = if (engine?.available == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (engine?.requiresModel == true && selection?.modelPath.isNullOrBlank()) {
                        TextButton(onClick = { onBrowseModels(stage) }, enabled = !state.active) { Text("Choose model") }
                    }
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(source, { source = it }, label = { Text("Source language") }, supportingText = { Text("auto, en, ar, …") },
            singleLine = true, enabled = !state.active, modifier = Modifier.width(180.dp))
        OutlinedTextField(target, { target = it }, label = { Text("Translate to") }, supportingText = { Text("Language code") },
            singleLine = true, enabled = !state.active, modifier = Modifier.width(180.dp))
        TextButton(onClick = { controller.updateConfig { it.copy(sourceLanguage = source.trim(), targetLanguage = target.trim()) } },
            enabled = !state.active && (source != config.sourceLanguage || target != config.targetLanguage)) { Text("Set languages") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onConfigure) { Text("Configure stages") }
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.widthIn(min = 280.dp, max = 380.dp).weight(.8f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard("Input") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WorkspaceInput.entries.forEach { mode -> FilterChip(selected = input == mode, onClick = { onInput(mode) },
                        enabled = !state.active, label = { Text(mode.title) }) }
                }
                when (input) {
                    WorkspaceInput.TEXT -> {
                        OutlinedTextField(text, { onDraft(draft.copy(text = it)) }, label = { Text("Text to translate or read") }, minLines = 7,
                            enabled = !state.active, modifier = Modifier.fillMaxWidth())
                        Text("Text bypasses speech recognition. Translation and voice remain independent.", style = MaterialTheme.typography.bodySmall)
                    }
                    WorkspaceInput.AUDIO_FILE -> {
                        OutlinedTextField(audioPath, { onDraft(draft.copy(audioPath = it)) }, label = { Text("Audio or video file") }, singleLine = true,
                            enabled = !state.active, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { chooseFile(parent, "Choose audio or video", listOf("wav", "mp4", "mov", "m4a", "mp3", "aac"))?.let { onDraft(draft.copy(audioPath = it.toAbsolutePath().toString())) } },
                            enabled = !state.active, modifier = Modifier.fillMaxWidth()) { Text("Choose audio file…") }
                        Text("PCM16 WAV works directly. Other audio/video uses the installed platform decoder; codec or adapter failures stay visible.", style = MaterialTheme.typography.bodySmall)
                    }
                    WorkspaceInput.MICROPHONE -> {
                        Box {
                            OutlinedButton(onClick = { deviceMenu = true }, enabled = !state.active, modifier = Modifier.fillMaxWidth()) {
                                Text(state.devices.firstOrNull { it.id == deviceId }?.name ?: "Default microphone")
                            }
                            DropdownMenu(deviceMenu, { deviceMenu = false }) {
                                DropdownMenuItem(text = { Text("Default microphone") }, onClick = { onDraft(draft.copy(deviceId = null)); deviceMenu = false })
                                state.devices.forEach { device -> DropdownMenuItem(text = { Text(device.name) },
                                    onClick = { onDraft(draft.copy(deviceId = device.id)); deviceMenu = false }) }
                            }
                        }
                        TextButton(onClick = { controller.refreshDevices() }, enabled = !state.active) { Text("Refresh devices") }
                        Text("Start records only this microphone. Finish drains accepted audio; Cancel discards the active request.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.active) {
                    if (state.microphone) Button(onClick = { controller.finishMicrophone() }, enabled = !state.finishing && !state.cancelling,
                        modifier = Modifier.fillMaxWidth()) { Text(if (state.finishing) "Finishing…" else "Finish recording") }
                    OutlinedButton(onClick = { controller.cancel() }, enabled = !state.cancelling, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.cancelling) "Cancelling…" else "Cancel session")
                    }
                } else {
                    val ready = if (input == WorkspaceInput.TEXT) config.translation != null || config.voice != null else config.speech != null
                    Button(onClick = {
                        if (!ready) onBrowseModels(if (input == WorkspaceInput.TEXT) PipelineStage.TRANSLATION else PipelineStage.STT)
                        else controller.start(input, text, audioPath, deviceId)
                    }, enabled = !state.starting && !state.importingSetup, modifier = Modifier.fillMaxWidth()) {
                        Text(if (!ready) "Set up ${if (input == WorkspaceInput.TEXT) "translation or voice" else "speech recognition"}" else when(input) {
                            WorkspaceInput.TEXT -> if (config.translation == null) "Generate speech" else "Translate text"
                            WorkspaceInput.AUDIO_FILE -> "Transcribe audio"
                            WorkspaceInput.MICROPHONE -> "Start microphone"
                        })
                    }
                }
            }
            SectionCard("Caption overlay", "Uses real text from the current session.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show original with translation", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(showOriginal, onOriginal)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show original while translation is pending", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(showPendingOriginal, onPendingOriginal, enabled = config.translation != null)
                }
                val caption = overlayCaption(state.snapshot?.segments.orEmpty(), showOriginal,
                    config.translation != null, showPendingOriginal)
                Text(caption?.text ?: if (config.translation != null && !showPendingOriginal) "Waiting for translation…" else "Waiting for speech or text…",
                    style = MaterialTheme.typography.titleMedium)
                caption?.original?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedButton(onClick = { onOverlay(!overlay) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (overlay) "Close floating captions" else "Float captions above other apps")
                }
            }
        }
        OutlinedCard(Modifier.weight(1.2f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Output", style = MaterialTheme.typography.titleLarge)
                        Text(state.snapshot?.let { "${it.status.name.lowercase().replaceFirstChar(Char::uppercase)}${it.stage?.let { stage -> " · ${stage.name.lowercase()}" }.orEmpty()}" }
                            ?: if (state.starting) "Starting…" else "Ready when you are", style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.active) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
                if (state.snapshot?.segments.isNullOrEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Your transcript and translation will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.snapshot!!.segments, key = { it.id }) { segment ->
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!segment.final) Text("Listening · partial", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(segment.original, style = MaterialTheme.typography.bodyLarge)
                                segment.translation?.takeIf(String::isNotBlank)?.let { Text(it, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge) }
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = 12.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { try { copyToClipboard(finalTranscript(state.snapshot, false)) } catch(e: Exception) { controller.showError(e.message ?: e.toString()) } },
                        enabled = finalTranscript(state.snapshot, false).isNotBlank()) { Text("Copy transcript") }
                    TextButton(onClick = { chooseSaveFile(parent, "Export transcript", "hearth-transcript.txt")?.let { controller.exportTranscript(it, false) } },
                        enabled = finalTranscript(state.snapshot, false).isNotBlank()) { Text("Save…") }
                }
                if (finalTranscript(state.snapshot, true).isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { try { copyToClipboard(finalTranscript(state.snapshot, true)) } catch(e: Exception) { controller.showError(e.message ?: e.toString()) } }) { Text("Copy translation") }
                    TextButton(onClick = { chooseSaveFile(parent, "Export translation", "hearth-translation.txt")?.let { controller.exportTranscript(it, true) } }) { Text("Save…") }
                }
                state.snapshot?.audio?.lastOrNull()?.let { audio ->
                    if (audio.path.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.playingAudio) Button(onClick = controller::stopPlayback) { Text("Stop speech") }
                        else Button(onClick = { controller.play(audio) }) { Text("Play generated speech") }
                        Text(audio.path.substringAfterLast('/').substringAfterLast('\\'), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

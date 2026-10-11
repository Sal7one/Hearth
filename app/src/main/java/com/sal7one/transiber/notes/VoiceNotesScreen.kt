package com.sal7one.transiber.notes

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sal7one.transiber.R
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.i18n.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VoiceNotesScreen(onSpeechSettings: () -> Unit, onTranslationSettings: () -> Unit) {
    val context = LocalContext.current
    val text = rememberUiText()
    val model: VoiceNotesViewModel = viewModel()
    val controller = model.controller
    val state by controller.state.collectAsState()
    val base by remember { CaptionConfigStore.config(context) }.collectAsState(initial = CaptionOverlayConfig())
    val prefs = remember { context.getSharedPreferences("voice-notes-options", Context.MODE_PRIVATE) }
    var transcribe by rememberSaveable { mutableStateOf(prefs.getBoolean("transcribe", false)) }
    var translate by rememberSaveable { mutableStateOf(prefs.getBoolean("translate", false)) }
    var consent by rememberSaveable { mutableStateOf(prefs.getBoolean("recording-consent-v1", false)) }
    var profile by rememberSaveable { mutableStateOf(prefs.getString("local-name", "").orEmpty()) }
    var source by rememberSaveable { mutableStateOf(prefs.getString("source", "auto").orEmpty()) }
    var target by rememberSaveable { mutableStateOf(prefs.getString("target", "en").orEmpty()) }
    var protect by rememberSaveable { mutableStateOf(prefs.getBoolean("device-credential-v1", false)) }
    val access = remember { VoiceNoteAccess { prefs.getBoolean("device-credential-v1", false) } }
    var locked by remember { mutableStateOf(access.locked) }
    var lockError by remember { mutableStateOf<String?>(null) }
    val credential = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        access.authenticationResult(result.resultCode == Activity.RESULT_OK)
        locked = access.locked
    }
    fun unlock() {
        val manager = context.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val prompt = manager?.createConfirmDeviceCredentialIntent(text(R.string.notes_unlock), text(R.string.notes_unlock_detail))
        lockError = null
        if (prompt == null) lockError = text(R.string.notes_secure_device_required)
        else try { credential.launch(prompt) }
        catch (e: Exception) { lockError = e.message ?: e.javaClass.simpleName }
    }
    var title by rememberSaveable { mutableStateOf("") }
    var privacy by rememberSaveable { mutableStateOf(!consent) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<VoiceNote?>(null) }
    var rename by remember { mutableStateOf<VoiceNote?>(null) }
    var newTitle by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    val audioOwner = remember { Any() }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    fun stopAudio() { player?.release(); player = null; playing = null; com.sal7one.transiber.voice.VoicePlaybackOwner.release(audioOwner) }
    val config = base.copy(source = CaptionSource.MIC, mode = if (translate) CaptionMode.TRANSLATE else CaptionMode.CAPTIONS,
        streamLanguage = source, target = TranslationTarget.fromStored(target))
    fun start() {
        stopAudio()
        controller.start(title.ifBlank { text(R.string.notes_default) }, config, transcribe, translate)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && consent) start() else controller.report(text(R.string.notes_permission))
    }
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingKind by rememberSaveable { mutableStateOf("txt") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val id = pendingId; pendingId = null
        val kind = pendingKind
        if (uri != null && id != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                val complete = controller.complete(VoiceNote(id = id, title = "Export"))
                val output = requireNotNull(context.contentResolver.openOutputStream(uri)) { "Cannot open export destination" }
                output.use { stream -> when (kind) {
                    "wav" -> controller.audio(complete).inputStream().use { it.copyTo(stream) }
                    "json" -> stream.write(complete.json().toByteArray(Charsets.UTF_8))
                    else -> stream.write(complete.text().toByteArray(Charsets.UTF_8))
                } }
            } } catch (e: Exception) { controller.report(e.message ?: e.javaClass.simpleName) }
        }
    }
    fun save(note: VoiceNote, kind: String) { pendingId = note.id; pendingKind = kind; export.launch("hearth-note-${note.id.take(8)}.$kind") }
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { controller.stop(); stopAudio(); access.backgrounded(); locked = access.locked } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stopAudio(); controller.stop() }
    }
    // System/navigation Back must finish the recording, with teardown retaining its own scope.
    BackHandler(enabled = state.busy) { controller.stop() }
    if (locked) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text(R.string.notes_title), style = MaterialTheme.typography.headlineMedium)
            Text(text(R.string.notes_locked))
            Button(onClick = ::unlock, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(text(R.string.notes_unlock)) }
            lockError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text(R.string.notes_title), style = MaterialTheme.typography.headlineMedium)
                    OutlinedTextField(title, { title = it.take(200) }, label = { Text(text(R.string.notes_name)) },
                        enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text(R.string.notes_transcribe), Modifier.weight(1f))
                        Switch(transcribe, { transcribe = it; prefs.edit().putBoolean("transcribe", it).apply() }, enabled = !state.busy)
                    }
                    if (transcribe) {
                        Text("${text.label(base.engine)} · ${base.modelId.ifBlank { text(R.string.notes_setup) }}", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text(R.string.notes_translate), Modifier.weight(1f))
                            Switch(translate, { translate = it; prefs.edit().putBoolean("translate", it).apply() }, enabled = !state.busy)
                        }
                        CaptionLanguageFields(config, onChange = { update ->
                            val next = update(config); source = next.streamLanguage; target = next.target.languageTag
                            prefs.edit().putString("source", source).putString("target", target).apply()
                        }, enabled = !state.busy, showTarget = translate)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onSpeechSettings, enabled = !state.busy) { Text(text(R.string.nav_speech_settings)) }
                            if (translate) OutlinedButton(onTranslationSettings, enabled = !state.busy) { Text(text(R.string.label_settingsfeature_translation_label)) }
                        }
                    }
                    Button(onClick = {
                        if (state.busy) controller.stop()
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }, enabled = consent, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(text(if (state.busy) R.string.notes_stop else R.string.notes_record))
                    }
                    if (state.busy) {
                        val seconds = (state.active?.samples ?: 0) / 16000
                        Text(if (state.recording) text(R.string.notes_duration, seconds / 60, seconds % 60) else text(R.string.notes_preparing))
                        state.active?.lines?.lastOrNull()?.let { SelectionContainer { Text(listOf(it.original, it.translation).filter(String::isNotBlank).joinToString("\n")) } }
                        if (state.partial.isNotBlank()) Text(state.partial, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text(R.string.notes_storage, state.usedBytes / (1024 * 1024)), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton({ privacy = true }) { Text(text(R.string.notes_privacy_button)) }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.notes.isEmpty() && !state.busy) Text(text(R.string.notes_empty))
        }
        state.notes.forEach { note ->
          item(key = note.id) {
            ElevatedCard(onClick = { expanded = if (expanded == note.id) null else note.id; if (expanded != null) controller.open(note) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(note.title, style = MaterialTheme.typography.titleLarge)
                    val seconds = note.samples / note.sampleRate
                    Text(text(R.string.notes_duration, seconds / 60, seconds % 60), style = MaterialTheme.typography.labelMedium)
                    if (note.status == NoteStatus.INTERRUPTED) Text(text(R.string.notes_interrupted), color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({
                            if (playing == note.id) stopAudio() else {
                                stopAudio()
                                try {
                                    com.sal7one.transiber.voice.VoicePlaybackOwner.claim(audioOwner, ::stopAudio)
                                    player = MediaPlayer().also { p ->
                                        p.setDataSource(controller.audio(note).absolutePath)
                                        p.setOnCompletionListener { stopAudio() }
                                        p.setOnErrorListener { _, what, extra -> controller.report("MediaPlayer error $what/$extra"); stopAudio(); true }
                                        p.setOnPreparedListener { it.start() }; p.prepareAsync()
                                    }; playing = note.id
                                } catch (e: Exception) { stopAudio(); controller.report(e.message ?: e.javaClass.simpleName) }
                            }
                        }, enabled = !state.busy && note.samples > 0) { Text(text(if (playing == note.id) R.string.notes_pause_audio else R.string.notes_play)) }
                        OutlinedButton({ save(note, "wav") }, enabled = !state.busy && note.samples > 0) { Text(text(R.string.notes_export_audio)) }
                    }
                    if (expanded == note.id) {
                        note.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ save(note, "txt") }, enabled = note.lines.isNotEmpty()) { Text(text(R.string.notes_export_text)) }
                            TextButton({ save(note, "json") }) { Text(text(R.string.notes_export_json)) }
                        }
                        Row {
                            TextButton({ rename = note; newTitle = note.title }, enabled = !state.busy) { Text(text(R.string.notes_rename)) }
                            TextButton({ delete = note }, enabled = !state.busy) { Text(text(R.string.notes_delete)) }
                        }
                    } else note.lines.firstOrNull()?.let { Text(it.original.ifBlank { it.translation }, maxLines = 2) }
                }
            }
          }
          if (expanded == note.id) items(state.detail?.takeIf { it.id == note.id }?.lines.orEmpty(), key = { "${note.id}:${it.id}" }) { line ->
              OutlinedCard(Modifier.fillMaxWidth()) {
                  SelectionContainer { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                      if (line.original.isNotBlank()) { Text(text(R.string.notes_original), style = MaterialTheme.typography.labelSmall); Text(line.original) }
                      if (line.translation.isNotBlank()) { Text(text(R.string.notes_translation), style = MaterialTheme.typography.labelSmall); Text(line.translation) }
                  } }
              }
          }
        }
    }
    if (privacy) AlertDialog(onDismissRequest = { privacy = false }, title = { Text(text(R.string.notes_terms)) },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text(R.string.notes_terms_body))
            OutlinedTextField(profile, { profile = it.take(80); prefs.edit().putString("local-name", profile).apply() },
                label = { Text(text(R.string.notes_profile_name)) }, supportingText = { Text(text(R.string.notes_local_profile)) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text(R.string.notes_device_lock), Modifier.weight(1f))
                Switch(protect, { enabled ->
                    if (enabled && context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) {
                        controller.report(text(R.string.notes_secure_device_required))
                    } else {
                        protect = enabled; prefs.edit().putBoolean("device-credential-v1", enabled).apply()
                        access.backgrounded(); locked = access.locked
                        if (enabled) { privacy = false; unlock() }
                    }
                }, enabled = !state.busy)
            }
            Text(text(R.string.notes_device_lock_detail), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(consent, { consent = it; prefs.edit().putBoolean("recording-consent-v1", it).apply() })
                Text(text(R.string.notes_accept))
            }
        } }, confirmButton = { TextButton({ privacy = false }) { Text(text(R.string.notes_back)) } })
    delete?.let { note -> AlertDialog(onDismissRequest = { delete = null }, title = { Text(text(R.string.notes_delete_question)) },
        confirmButton = { TextButton({ stopAudio(); controller.delete(note); delete = null }) { Text(text(R.string.notes_delete)) } },
        dismissButton = { TextButton({ delete = null }) { Text(text(R.string.notes_cancel)) } }) }
    rename?.let { note -> AlertDialog(onDismissRequest = { rename = null }, title = { Text(text(R.string.notes_rename)) },
        text = { OutlinedTextField(newTitle, { newTitle = it.take(200) }) },
        confirmButton = { TextButton({ controller.rename(note, newTitle); rename = null }) { Text(text(R.string.notes_save)) } },
        dismissButton = { TextButton({ rename = null }) { Text(text(R.string.notes_cancel)) } }) }
}

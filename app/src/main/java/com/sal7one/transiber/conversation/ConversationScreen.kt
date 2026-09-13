package com.sal7one.transiber.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.platform.LocalView
import com.sal7one.transiber.translation.ConversationTranslationSettings
import com.sal7one.transiber.translation.ConversationTranslationSetup
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.common_jni.translation.TranslationCatalog
import com.sal7one.transiber.byok.CloudConfigStore
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.translation.TranslationOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(onModels: () -> Unit = {}, onCloud: () -> Unit = {}, onLayoutChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val controller = remember { ConversationController(context.applicationContext) }
    val state by controller.state.collectAsState()
    val config by remember { CaptionConfigStore.config(context) }.collectAsState(initial = CaptionOverlayConfig())
    val model = rememberCaptionLanguageModel(config)
    val sourceChoices = CaptionLanguages.source(config.copy(mode = CaptionMode.CAPTIONS), CloudConfigStore.sttMode(context), model)
    val recognitionCodes = sourceChoices.codes - setOf("auto", "model")
    val translationRevision by ConversationTranslationSettings.revision.collectAsState()
    val translationCodes = remember(translationRevision, config.localTranslationModelId) { ConversationTranslationSettings.languages(context, config.localTranslationModelId) }
    val translatorLabel = remember(translationRevision, config.localTranslationModelId) { ConversationTranslationSettings.label(context, config.localTranslationModelId) }
    val codes = (translationCodes + recognitionCodes).ifEmpty { setOf("ar", "en") }
    val languageChoices = CaptionLanguageChoices(codes,
        "Choose the languages you and the other person use. Speak is available only when the selected speech model supports that language and the translator supports the direction. ${sourceChoices.note}")
    var picker by rememberSaveable { mutableStateOf<Int?>(null) }
    var options by rememberSaveable { mutableStateOf(false) }
    val optionsScroll = rememberScrollState()
    val translationScroll = rememberScrollState()
    var history by rememberSaveable { mutableStateOf(false) }
    var faceToFace by rememberSaveable { mutableStateOf(false) }
    var faceHistory by rememberSaveable { mutableStateOf<Int?>(null) }
    var translationSettings by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    var typedSpeaker by rememberSaveable { mutableIntStateOf(0) }
    var presentation by remember { mutableStateOf<ConversationTurn?>(null) }
    var flipped by rememberSaveable { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("conversation-options", 0) }
    var showOriginal by rememberSaveable { mutableStateOf(prefs.getBoolean("face_original", false)) }
    var keepAwake by rememberSaveable { mutableStateOf(prefs.getBoolean("keep_awake", true)) }
    val view = LocalView.current
    DisposableEffect(view, keepAwake) {
        val old = view.keepScreenOn; view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = old }
    }
    LaunchedEffect(faceToFace) { onLayoutChanged(faceToFace) }
    BackHandler(faceToFace && !options && faceHistory == null && !translationSettings && picker == null) { faceToFace = false }
    var automaticSpeech by rememberSaveable { mutableStateOf(prefs.getBoolean("auto_speech", false)) }
    var textSize by rememberSaveable { mutableFloatStateOf(prefs.getFloat("text_size", 20f)) }
    var speaking by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var pendingSpeaker by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingConfig by remember { mutableStateOf<CaptionOverlayConfig?>(null) }
    val voice = remember { ConversationVoice(context) { active, error -> speaking = active; if (error != null) voiceError = error } }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var followLatest by remember { mutableStateOf(true) }
    var autoPlayed by remember { mutableStateOf<String?>(null) }
    val openedAt = remember { System.currentTimeMillis() }
    var rename by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }

    fun directionAllowed(a: String, b: String): Boolean = ConversationTranslationSettings.supports(context, config.localTranslationModelId, a, b)
    fun canSpeak(side: Int): Boolean {
        val from = if (side == 0) state.session.first else state.session.second
        val to = if (side == 0) state.session.second else state.session.first
        return from in recognitionCodes && directionAllowed(from, to)
    }
    fun share(session: ConversationSession) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, session.exportText()), "Share conversation"))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val speaker = pendingSpeaker; val snapshot = pendingConfig
        pendingSpeaker = null; pendingConfig = null
        if (granted && speaker != null && snapshot != null && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.speak(speaker, snapshot)
        else if (!granted) controller.reportError("Microphone permission was not granted. Type instead remains available.")
    }
    fun speak(side: Int) {
        voice.stop(); voiceError = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) controller.speak(side, config)
        else { pendingSpeaker = side; pendingConfig = config; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    DisposableEffect(owner, controller, voice) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { controller.cancel(); voice.stop() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); voice.close(); controller.close() }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, more) ->
            if (scrolling) followLatest = !more
        }
    }
    LaunchedEffect(state.session.id) { followLatest = true }
    LaunchedEffect(state.session.id, state.session.turns.size) {
        if (state.session.turns.isNotEmpty() && followLatest) list.animateScrollToItem(state.session.turns.lastIndex)
    }
    LaunchedEffect(state.busy, state.session.turns.lastOrNull()?.status) {
        val last = state.session.turns.lastOrNull()
        if (!state.busy && last?.status == TurnStatus.COMPLETE && last.id != autoPlayed) {
            // Existing history is never spoken merely because the page opened.
            if (automaticSpeech && last.created >= openedAt) voice.speak(last)
            autoPlayed = last.id
        }
    }

    if (faceToFace) Column(Modifier.fillMaxSize()) {
        FaceToFacePanel(state = state.copy(error = voiceError ?: state.error, status = if (speaking) "Speaking" else state.status),
            textSize = textSize, showOriginal = showOriginal, canSpeak = ::canSpeak,
            onSpeak = ::speak, onFinish = controller::finish, onLanguage = { picker = it },
            onHistory = { faceHistory = it }, onOptions = { options = true },
            onCancel = controller::cancel, onSwap = { controller.languages(state.session.second, state.session.first) })
    } else Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { history = true }, enabled = !state.busy) { Text("History") }
            TextButton(onClick = { options = true }, enabled = !state.busy) { Text("Options") }
            TextButton(onClick = { controller.newSession() }, enabled = !state.busy) { Text("New conversation") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker = 0 }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Text("Me · ${LanguageCatalog.option(state.session.first).nativeName}")
            }
            IconButton(onClick = { controller.languages(state.session.second, state.session.first) }, enabled = !state.busy,
                modifier = Modifier.size(48.dp).align(androidx.compose.ui.Alignment.CenterVertically)) {
                Icon(Icons.Default.SwapHoriz, "Swap my language and their language")
            }
            OutlinedButton(onClick = { picker = 1 }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Text("Them · ${LanguageCatalog.option(state.session.second).nativeName}")
            }
        }
        Text("${model.label} → $translatorLabel", style = MaterialTheme.typography.bodySmall)
        if (config.engine == CaptionEngineChoice.CLOUD) Text("Cloud speech uses your selected speech connection. The conversation translator is selected separately in Options. Both original and translated text are kept.", style = MaterialTheme.typography.bodySmall)
        if (translationCodes.isEmpty()) TextButton(onClick = { translationSettings = true }) { Text("Set up translation to begin") }
        if (state.session.turns.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Text("A conversation, in both languages", style = MaterialTheme.typography.headlineSmall)
                Text("Tap your language, speak, then tap Finish. Pass the phone for the reply. Each turn ends after one minute. Microphone only; no screen recording.", Modifier.padding(top = 12.dp))
                Text(if (state.saveHistory) "Text is saved on this device. Audio is never saved." else "History is off. New turns last only for this session.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.session.turns, key = { it.id }) { turn ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${if (turn.speaker == 0) "Me" else "Them"} · ${LanguageCatalog.option(turn.source).englishName} → ${LanguageCatalog.option(turn.target).englishName}", style = MaterialTheme.typography.labelMedium)
                            if (turn.original.isNotBlank()) LanguageText(turn.original, turn.source, textSize - 2)
                            if (state.activeSpeaker == turn.speaker && turn.id == state.session.turns.last().id && state.partial.isNotBlank()) LanguageText(state.partial, turn.source, textSize - 2)
                            if (turn.translation.isNotBlank()) LanguageText(turn.translation, turn.target, textSize)
                            else Text(when (turn.status) { TurnStatus.LISTENING -> "Listening…"; TurnStatus.TRANSLATING -> "Translating…"; else -> "Translation unavailable" }, style = MaterialTheme.typography.bodySmall)
                            turn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (turn.translation.isNotBlank()) {
                                    TextButton(onClick = { voiceError = null; voice.speak(turn) }, enabled = !state.busy) { Text("Play") }
                                    TextButton(onClick = { flipped = false; presentation = turn }) { Text("Show large") }
                                } else if (turn.original.isNotBlank() && !state.busy) TextButton(onClick = { voice.stop(); controller.retry(turn, config) }, enabled = directionAllowed(turn.source, turn.target)) { Text("Retry translation") }
                            }
                        }
                    }
                }
            }
            if (list.canScrollForward) TextButton(onClick = { followLatest = true; scope.launch { list.animateScrollToItem(state.session.turns.lastIndex) } }) { Text("Latest messages ↓") }
        }
        (voiceError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (speaking) "Speaking" else state.status, Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
            if (speaking) TextButton(onClick = { voice.stop() }) { Text("Stop speech") }
            else if (state.busy) TextButton(onClick = controller::cancel) { Text("Cancel") }
            else TextButton(onClick = { typing = true; voice.stop() }) { Text("Type instead") }
        }
        val missingSpeech = listOf(state.session.first, state.session.second).filterNot { it in recognitionCodes }
        if (missingSpeech.isNotEmpty()) Text("${missingSpeech.joinToString { LanguageCatalog.option(it).englishName }} speech is unavailable with this model's declared language support. Type instead, or change the speech model.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(2) { side ->
                val language = if (side == 0) state.session.first else state.session.second
                val target = if (side == 0) state.session.second else state.session.first
                val selected = state.activeSpeaker == side
                val finishable = selected && state.listening
                Button(onClick = { if (finishable) controller.finish() else speak(side) },
                    enabled = finishable || (!state.busy && language in recognitionCodes && directionAllowed(language, target)),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { contentDescription = "${if (finishable) "Finish" else "Speak"} ${LanguageCatalog.option(language).englishName}" }) {
                    Text(if (finishable) "Finish" else "Speak ${LanguageCatalog.option(language).nativeName}")
                }
            }
        }
    }

    picker?.let { side -> Dialog(onDismissRequest = { picker = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { LanguagePickerContent(
            title = if (side == 0) "My language" else "Their language", choices = languageChoices,
            selected = if (side == 0) state.session.first else state.session.second,
            onSelect = { code -> controller.languages(if (side == 0) code else state.session.first, if (side == 1) code else state.session.second); picker = null }, onDismiss = { picker = null }) }
    } }
    if (typing) AlertDialog(onDismissRequest = { typing = false }, title = { Text("Type instead") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(2) { side -> FilterChip(selected = typedSpeaker == side, onClick = { typedSpeaker = side }, label = { Text(if (side == 0) "Me" else "Them") }) } }
            Text("${LanguageCatalog.option(if (typedSpeaker == 0) state.session.first else state.session.second).nativeName}")
            OutlinedTextField(typed, { typed = it }, label = { Text("What would you like to say?") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        }
    }, confirmButton = { TextButton(onClick = { controller.type(typedSpeaker, typed, config); typed = ""; typing = false }, enabled = typed.isNotBlank() && directionAllowed(if (typedSpeaker == 0) state.session.first else state.session.second, if (typedSpeaker == 0) state.session.second else state.session.first)) { Text("Translate") } }, dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } })
    if (options) ModalBottomSheet(onDismissRequest = { options = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(optionsScroll).padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (faceToFace) "Face-to-face settings" else "Conversation options", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { faceToFace = !faceToFace; options = false }, modifier = Modifier.fillMaxWidth()) {
                Text(if (faceToFace) "Open conversation view" else "Open face-to-face view")
            }
            TextButton(onClick = { options = false; translationSettings = true }) { Text("Translation · $translatorLabel") }
            if (faceToFace) {
                TextButton(onClick = { voice.stop(); options = false; typing = true }) { Text("Type instead") }
                TextButton(onClick = { options = false; history = true }) { Text("Saved conversations") }
                TextButton(onClick = { controller.newSession(); options = false }) { Text("New conversation") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Show original below translation", Modifier.weight(1f)); Switch(showOriginal, { showOriginal = it; prefs.edit().putBoolean("face_original", it).apply() }, modifier = Modifier.semantics { contentDescription = "Show original below translation" }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Keep screen awake", Modifier.weight(1f)); Switch(keepAwake, { keepAwake = it; prefs.edit().putBoolean("keep_awake", it).apply() }, modifier = Modifier.semantics { contentDescription = "Keep conversation screen awake" }) }
            if (speaking) TextButton(onClick = { voice.stop() }) { Text("Stop speech") }
            Text(sourceChoices.note, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { options = false; onModels() }) { Text("Models") }
                TextButton(onClick = { options = false; onCloud() }) { Text("Cloud connection") }
                TextButton(onClick = { controller.languages(state.session.second, state.session.first) }) { Text("Swap languages") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Read translations aloud", Modifier.weight(1f)); Switch(automaticSpeech, { automaticSpeech = it; prefs.edit().putBoolean("auto_speech", it).apply() }, modifier = Modifier.semantics { contentDescription = "Read translations aloud" }) }
            Text("Uses an installed offline Android voice. Listening always stops before speech playback.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Save history on this device", Modifier.weight(1f)); Switch(state.saveHistory, controller::saveHistory, modifier = Modifier.semantics { contentDescription = "Save history on this device" }) }
            Text("Turning history off starts a temporary conversation; earlier saved conversations remain in History.", style = MaterialTheme.typography.bodySmall)
            Text("Translation text size: ${textSize.toInt()}")
            Slider(textSize, { textSize = it }, modifier = Modifier.semantics { contentDescription = "Translation text size"; stateDescription = "${textSize.toInt()}" }, valueRange = 18f..40f, onValueChangeFinished = { prefs.edit().putFloat("text_size", textSize).apply() })
            TextButton(onClick = { title = state.session.title; rename = true; options = false }) { Text("Rename conversation") }
            TextButton(onClick = { share(state.session) }, enabled = state.session.turns.isNotEmpty()) { Text("Share conversation text") }
        }
    }
    if (translationSettings) ModalBottomSheet(onDismissRequest = { translationSettings = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(translationScroll).padding(20.dp).navigationBarsPadding()) {
            ConversationTranslationSetup(onModels = { translationSettings = false; onModels() })
        }
    }
    faceHistory?.let { side -> Dialog(onDismissRequest = { faceHistory = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val language = if (side == 0) state.session.first else state.session.second
        val turns = state.session.turns.filter { it.source == language || it.target == language }
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp).rotate(if (side == 1) 180f else 0f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(LanguageCatalog.option(language).nativeName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { faceHistory = null }) { Text("Close") }
                }
                Text("This conversation", style = MaterialTheme.typography.labelLarge)
                if (turns.isEmpty()) Text("No messages in this language yet.", Modifier.padding(top = 20.dp))
                LazyColumn(Modifier.weight(1f), state = rememberLazyListState(initialFirstVisibleItemIndex = (turns.size - 1).coerceAtLeast(0)),
                    verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                    items(turns, key = { it.id }) { turn -> OutlinedCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (turn.speaker == side) "You said" else "They said", style = MaterialTheme.typography.labelSmall)
                            val text = if (turn.source == language) turn.original else turn.translation
                            if (text.isNotBlank()) LanguageText(text, language, textSize)
                            else Text(if (turn.status == TurnStatus.TRANSLATING) "Translating…" else "Translation unavailable")
                            if (showOriginal && turn.source != language && turn.original.isNotBlank()) LanguageText(turn.original, turn.source, textSize - 2)
                            turn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (turn.target == language && turn.translation.isNotBlank()) TextButton(enabled = !state.busy, onClick = { voice.speak(turn) }) { Text("Play") }
                            if (turn.translation.isBlank() && turn.original.isNotBlank()) TextButton(enabled = !state.busy && directionAllowed(turn.source, turn.target), onClick = { voice.stop(); controller.retry(turn, config) }) { Text("Retry translation") }
                        }
                    } }
                }
                if (speaking) TextButton(onClick = { voice.stop() }) { Text("Stop speech") }
            }
        }
    } }
    if (history) Dialog(onDismissRequest = { history = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.padding(16.dp)) {
                Text("Saved conversations", style = MaterialTheme.typography.headlineSmall)
                Text("Stored on this device only. No audio is saved.")
                TextButton(onClick = { history = false }) { Text("Done") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.history.isEmpty()) item { Text("No saved conversations yet.") }
                    items(state.history, key = { it.id }) { session -> OutlinedCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium)
                            Text("${LanguageCatalog.option(session.first).nativeName} / ${LanguageCatalog.option(session.second).nativeName} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(session.created))}")
                            Text(session.turns.lastOrNull()?.original.orEmpty(), maxLines = 2)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { controller.openSession(session); history = false }) { Text("Continue") }
                                TextButton(onClick = { share(session) }) { Text("Share") }
                                TextButton(onClick = { controller.deleteSession(session.id) }) { Text("Delete") }
                            }
                        }
                    } }
                }
            }
        }
    }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("Conversation name") }, text = { OutlinedTextField(title, { title = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { controller.rename(title); rename = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    presentation?.let { turn -> Dialog(onDismissRequest = { presentation = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(24.dp).rotate(if (flipped) 180f else 0f)) {
                LazyColumn(Modifier.weight(1f)) { item { LanguageText(turn.translation, turn.target, 36f) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { if (speaking) voice.stop() else voice.speak(turn) }, enabled = !state.busy) { Text(if (speaking) "Stop speech" else "Play") }
                    TextButton(onClick = { flipped = !flipped }) { Text("Flip") }
                    TextButton(onClick = { presentation = null }) { Text("Close") }
                }
            }
        }
    } }
}

@Composable
private fun LanguageText(text: String, language: String, size: Float) {
    Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp, lineHeight = (size * 1.4f).sp,
        textAlign = if (LanguageCatalog.option(language).rtl) TextAlign.Right else TextAlign.Left,
        textDirection = if (LanguageCatalog.option(language).rtl) TextDirection.Rtl else TextDirection.Ltr))
}

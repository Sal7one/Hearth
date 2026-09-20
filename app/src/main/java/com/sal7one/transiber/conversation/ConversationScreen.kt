package com.sal7one.transiber.conversation

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import com.sal7one.transiber.R
import com.sal7one.transiber.ui.theme.glassPanel
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Forum
import com.sal7one.transiber.ui.components.FeatureOptionsSheet
import androidx.compose.ui.platform.LocalView
import com.sal7one.transiber.translation.ConversationTranslationSettings
import com.sal7one.transiber.translation.TranslatorChooser
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
fun ConversationScreen(onModels: () -> Unit = {}, onCloud: () -> Unit = {}, onLayoutChanged: (Boolean) -> Unit = {}, initialFaceToFace: Boolean = false, onVoices: () -> Unit = {}) {
    val uiText = rememberUiText()

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
    val codes = (translationCodes + recognitionCodes)
    val languageChoices = CaptionLanguageChoices(codes,
        uiText(UiR.string.ui_choose_the_languages_you_and_the_other_person_use_speak_is_availa_164dc, uiText.note(sourceChoices)))
    var picker by rememberSaveable { mutableStateOf<Int?>(null) }
    var options by rememberSaveable { mutableStateOf(false) }
    val optionsScroll = rememberScrollState()
    val translationScroll = rememberScrollState()
    var history by rememberSaveable { mutableStateOf(false) }
    var faceToFace by rememberSaveable { mutableStateOf(initialFaceToFace) }
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
    LaunchedEffect(Unit) {faceToFace=initialFaceToFace}
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
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, session.exportText()), uiText(UiR.string.ui_share_conversation_e187f)))
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val speaker = pendingSpeaker; val snapshot = pendingConfig
        pendingSpeaker = null; pendingConfig = null
        if (granted && speaker != null && snapshot != null && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.speak(speaker, snapshot)
        else if (!granted) controller.reportError(uiText(UiR.string.ui_microphone_permission_was_not_granted_type_instead_remains_availa_76bd7))
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
        FaceToFacePanel(state = state.copy(error = voiceError ?: state.error, status = if (speaking) ConversationStatus.SPEAKING else state.status),
            textSize = textSize, showOriginal = showOriginal, canSpeak = ::canSpeak,
            onSpeak = ::speak, onFinish = controller::finish, onLanguage = { picker = it },
            onHistory = { faceHistory = it }, onOptions = { options = true },
            onCancel = controller::cancel, onSwap = { controller.languages(state.session.second, state.session.first) })
    } else Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { faceToFace = true }, enabled = !state.busy) { Text(uiText(UiR.string.ui_face_to_face_c104d)) }
            Row {
                IconButton(onClick = { history = true }, enabled = !state.busy) { Icon(Icons.Default.History, uiText(UiR.string.ui_conversation_history_a03d8)) }
                IconButton(onClick = { options = true }, enabled = !state.busy) { Icon(Icons.Default.Tune, uiText(UiR.string.ui_conversation_settings_575f3)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker = 0 }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Text(uiText(UiR.string.ui_me_1_s_24999, LanguageCatalog.option(state.session.first).nativeName))
            }
            IconButton(onClick = { controller.languages(state.session.second, state.session.first) }, enabled = !state.busy,
                modifier = Modifier.size(48.dp).align(androidx.compose.ui.Alignment.CenterVertically)) {
                Icon(Icons.Default.SwapHoriz, uiText(UiR.string.ui_swap_my_language_and_their_language_d0b42))
            }
            OutlinedButton(onClick = { picker = 1 }, enabled = !state.busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                Text(uiText(UiR.string.ui_them_1_s_c05c1, LanguageCatalog.option(state.session.second).nativeName))
            }
        }
        TextButton(onClick={translationSettings=true}) { Text(uiText(UiR.string.ui_translator_1_s_7405d, translatorLabel)) }
        if (config.engine == CaptionEngineChoice.CLOUD) Text(uiText(UiR.string.ui_audio_is_sent_to_your_speech_provider_e97e6), style = MaterialTheme.typography.bodySmall)
        if (translationCodes.isEmpty()) TextButton(onClick = { translationSettings = true }) { Text(uiText(UiR.string.ui_set_up_translation_to_begin_887a3)) }
        if (state.session.turns.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                Image(painterResource(R.drawable.home_conversation),null,Modifier.fillMaxWidth().heightIn(max=160.dp).aspectRatio(2.5f).clip(RoundedCornerShape(28.dp)),contentScale=ContentScale.Crop)
                Text(uiText(UiR.string.ui_your_turn_to_talk_54a90), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
                Text(uiText(UiR.string.ui_tap_speak_below_then_finish_for_the_translation_9257b), Modifier.padding(top = 8.dp))
                Text(if (state.saveHistory) uiText(UiR.string.ui_text_history_on_audio_is_not_saved_734f3) else uiText(UiR.string.ui_history_off_this_session_only_ff01c), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.session.turns, key = { it.id }) { turn ->
                    ElevatedCard(Modifier.fillMaxWidth().glassPanel(),colors=CardDefaults.elevatedCardColors(containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${if (turn.speaker == 0) uiText(UiR.string.ui_me_94563) else uiText(UiR.string.ui_them_3dd51)} · ${uiText.languageName(turn.source)} → ${uiText.languageName(turn.target)}", style = MaterialTheme.typography.labelMedium)
                            if (turn.original.isNotBlank()) LanguageText(turn.original, turn.source, textSize - 2)
                            if (state.activeSpeaker == turn.speaker && turn.id == state.session.turns.last().id && state.partial.isNotBlank()) LanguageText(state.partial, turn.source, textSize - 2)
                            if (turn.translation.isNotBlank()) LanguageText(turn.translation, turn.target, textSize)
                            else Text(when (turn.status) { TurnStatus.LISTENING -> uiText(UiR.string.ui_listening_6c19a); TurnStatus.TRANSLATING -> uiText(UiR.string.ui_translating_ae47b); else -> uiText(UiR.string.ui_translation_unavailable_162a7) }, style = MaterialTheme.typography.bodySmall)
                            turn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (turn.translation.isNotBlank()) {
                                    com.sal7one.transiber.voice.ReadAloudButtons(uiText(UiR.string.ui_translation_ac26a), !state.busy, { system -> voiceError=null; voice.speak(turn,system) }, onVoices)
                                    TextButton(onClick = { flipped = false; presentation = turn }) { Text(uiText(UiR.string.ui_show_large_2d377)) }
                                } else if (turn.original.isNotBlank() && !state.busy) TextButton(onClick = { voice.stop(); controller.retry(turn, config) }, enabled = directionAllowed(turn.source, turn.target)) { Text(uiText(UiR.string.ui_retry_translation_0bd43)) }
                            }
                        }
                    }
                }
            }
            if (list.canScrollForward) TextButton(onClick = { followLatest = true; scope.launch { list.animateScrollToItem(state.session.turns.lastIndex) } }) { Text(uiText(UiR.string.ui_latest_messages_a7ea5)) }
        }
        (voiceError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (speaking) uiText(UiR.string.ui_speaking_a771f) else uiText.label(state.status), Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
            if (speaking) TextButton(onClick = { voice.stop() }) { Text(uiText(UiR.string.ui_stop_speech_3f0d2)) }
            else if (state.busy) TextButton(onClick = controller::cancel) { Text(uiText(UiR.string.ui_cancel_77dfd)) }
            else TextButton(onClick = { typing = true; voice.stop() }) { Text(uiText(UiR.string.ui_type_instead_78a8f)) }
        }
        val missingSpeech = listOf(state.session.first, state.session.second).filterNot { it in recognitionCodes }
        if (missingSpeech.isNotEmpty()) Text(uiText(UiR.string.ui_1_s_speech_is_unavailable_with_this_model_s_declared_language_sup_032e4, missingSpeech.joinToString { uiText.languageName(it) }), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(2) { side ->
                val language = if (side == 0) state.session.first else state.session.second
                val target = if (side == 0) state.session.second else state.session.first
                val selected = state.activeSpeaker == side
                val finishable = selected && state.listening
                Button(onClick = { if (finishable) controller.finish() else speak(side) },
                    enabled = finishable || (!state.busy && language in recognitionCodes && directionAllowed(language, target)),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { contentDescription = "${if (finishable) uiText(UiR.string.ui_finish_b74bd) else uiText(UiR.string.speak_action)} ${uiText.languageName(language)}" }) {
                    Text(if (finishable) uiText(UiR.string.ui_finish_b74bd) else uiText(UiR.string.ui_speak_1_s_77282, LanguageCatalog.option(language).nativeName))
                }
            }
        }
    }

    picker?.let { side -> Dialog(onDismissRequest = { picker = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { LanguagePickerContent(
            title = if (side == 0) uiText(UiR.string.ui_my_language_cdfad) else uiText(UiR.string.ui_their_language_2f66c), choices = languageChoices,
            selected = if (side == 0) state.session.first else state.session.second,
            onSelect = { code -> controller.languages(if (side == 0) code else state.session.first, if (side == 1) code else state.session.second); picker = null }, onDismiss = { picker = null }) }
    } }
    if (typing) AlertDialog(onDismissRequest = { typing = false }, title = { Text(uiText(UiR.string.ui_type_instead_78a8f)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(2) { side -> FilterChip(selected = typedSpeaker == side, onClick = { typedSpeaker = side }, label = { Text(if (side == 0) uiText(UiR.string.ui_me_94563) else uiText(UiR.string.ui_them_3dd51)) }) } }
            Text("${LanguageCatalog.option(if (typedSpeaker == 0) state.session.first else state.session.second).nativeName}")
            OutlinedTextField(typed, { typed = it }, label = { Text(uiText(UiR.string.ui_what_would_you_like_to_say_3e4e8)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        }
    }, confirmButton = { TextButton(onClick = { controller.type(typedSpeaker, typed, config); typed = ""; typing = false }, enabled = typed.isNotBlank() && directionAllowed(if (typedSpeaker == 0) state.session.first else state.session.second, if (typedSpeaker == 0) state.session.second else state.session.first)) { Text(uiText(UiR.string.ui_translate_2be17)) } }, dismissButton = { TextButton(onClick = { typing = false }) { Text(uiText(UiR.string.ui_cancel_77dfd)) } })
    if (options) FeatureOptionsSheet(if (faceToFace) uiText(UiR.string.ui_face_to_face_settings_83243) else uiText(UiR.string.ui_conversation_settings_575f3), { options = false }, optionsScroll) {
            TextButton(onClick = { controller.newSession(); options = false }, enabled = !state.busy) { Text(uiText(UiR.string.ui_new_conversation_7f031)) }
            Text(uiText(UiR.string.ui_speech_1_s_679a7, model.label), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { faceToFace = !faceToFace; options = false }, modifier = Modifier.fillMaxWidth()) {
                Text(if (faceToFace) uiText(UiR.string.ui_open_conversation_view_c60e0) else uiText(UiR.string.ui_open_face_to_face_view_f43f6))
            }
            TextButton(onClick = { options = false; translationSettings = true }) { Text(uiText(UiR.string.ui_translation_1_s_afc1a, translatorLabel)) }
            if (faceToFace) {
                TextButton(onClick = { voice.stop(); options = false; typing = true }) { Text(uiText(UiR.string.ui_type_instead_78a8f)) }
                TextButton(onClick = { options = false; history = true }) { Text(uiText(UiR.string.ui_saved_conversations_5a303)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(uiText(UiR.string.ui_show_original_below_translation_190ac), Modifier.weight(1f)); Switch(showOriginal, { showOriginal = it; prefs.edit().putBoolean("face_original", it).apply() }, modifier = Modifier.semantics { contentDescription = uiText(UiR.string.ui_show_original_below_translation_190ac) }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(uiText(UiR.string.ui_keep_screen_awake_e4656), Modifier.weight(1f)); Switch(keepAwake, { keepAwake = it; prefs.edit().putBoolean("keep_awake", it).apply() }, modifier = Modifier.semantics { contentDescription = uiText(UiR.string.ui_keep_conversation_screen_awake_6e422) }) }
            if (speaking) TextButton(onClick = { voice.stop() }) { Text(uiText(UiR.string.ui_stop_speech_3f0d2)) }
            Text(uiText.note(sourceChoices), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { options = false; onModels() }) { Text(uiText(UiR.string.ui_models_f3798)) }
                if (com.sal7one.transiber.byok.ByokPolicy.FEATURE_BYOK) TextButton(onClick = { options = false; onCloud() }) { Text(uiText(UiR.string.ui_cloud_connection_ad1ee)) }
                TextButton(onClick = { controller.languages(state.session.second, state.session.first) }) { Text(uiText(UiR.string.ui_swap_languages_efa6c)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(uiText(UiR.string.ui_read_translations_aloud_f1394), Modifier.weight(1f)); Switch(automaticSpeech, { automaticSpeech = it; prefs.edit().putBoolean("auto_speech", it).apply() }, modifier = Modifier.semantics { contentDescription = uiText(UiR.string.ui_read_translations_aloud_f1394) }) }
            Text(uiText(UiR.string.ui_uses_your_shared_android_supertonic_or_self_hosted_voice_listenin_614db), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={voice.stop();options=false;onVoices()}){Text(uiText(UiR.string.ui_voices_read_aloud_64e95))}
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(uiText(UiR.string.ui_save_history_on_this_device_5b5ad), Modifier.weight(1f)); Switch(state.saveHistory, controller::saveHistory, modifier = Modifier.semantics { contentDescription = uiText(UiR.string.ui_save_history_on_this_device_5b5ad) }) }
            Text(uiText(UiR.string.ui_turning_history_off_starts_a_temporary_conversation_earlier_saved_57b46), style = MaterialTheme.typography.bodySmall)
            Text(uiText(UiR.string.ui_translation_text_size_1_s_d50ff, textSize.toInt()))
            Slider(textSize, { textSize = it }, modifier = Modifier.semantics { contentDescription = uiText(UiR.string.ui_translation_text_size_408c2); stateDescription = "${textSize.toInt()}" }, valueRange = 18f..40f, onValueChangeFinished = { prefs.edit().putFloat("text_size", textSize).apply() })
            TextButton(onClick = { title = state.session.title; rename = true; options = false }) { Text(uiText(UiR.string.ui_rename_conversation_30da4)) }
            TextButton(onClick = { share(state.session) }, enabled = state.session.turns.isNotEmpty()) { Text(uiText(UiR.string.ui_share_conversation_text_0722c)) }
    }
    if (translationSettings) FeatureOptionsSheet(uiText(UiR.string.ui_translator_1fafc), { translationSettings = false }, translationScroll) {
            TranslatorChooser(ConversationTranslationSettings.selected(context),config.localTranslationModelId,
                uiText(UiR.string.ui_used_by_conversation_face_to_face_and_typed_text_472fa),state.session.first,state.session.second,
                onModels={translationSettings=false;onModels()},onSelect={provider,model->scope.launch {
                    try { CaptionConfigStore.update(context){it.copy(localTranslationModelId=model)};ConversationTranslationSettings.select(context,provider) }
                    catch(e: kotlinx.coroutines.CancellationException){throw e}
                    catch(e: Exception){voiceError=e.message ?: e.toString()}
                }})
    }
    faceHistory?.let { side -> Dialog(onDismissRequest = { faceHistory = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val language = if (side == 0) state.session.first else state.session.second
        val turns = state.session.turns.filter { it.source == language || it.target == language }
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(16.dp).rotate(if (side == 1) 180f else 0f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(LanguageCatalog.option(language).nativeName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { faceHistory = null }) { Text(uiText(UiR.string.ui_close_bbfa7)) }
                }
                Text(uiText(UiR.string.ui_this_conversation_3af08), style = MaterialTheme.typography.labelLarge)
                if (turns.isEmpty()) Text(uiText(UiR.string.ui_no_messages_in_this_language_yet_7d846), Modifier.padding(top = 20.dp))
                LazyColumn(Modifier.weight(1f), state = rememberLazyListState(initialFirstVisibleItemIndex = (turns.size - 1).coerceAtLeast(0)),
                    verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
                    items(turns, key = { it.id }) { turn -> OutlinedCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (turn.speaker == side) uiText(UiR.string.ui_you_said_f105a) else uiText(UiR.string.ui_they_said_f60ab), style = MaterialTheme.typography.labelSmall)
                            val text = if (turn.source == language) turn.original else turn.translation
                            if (text.isNotBlank()) LanguageText(text, language, textSize)
                            else Text(if (turn.status == TurnStatus.TRANSLATING) uiText(UiR.string.ui_translating_ae47b) else uiText(UiR.string.ui_translation_unavailable_162a7))
                            if (showOriginal && turn.source != language && turn.original.isNotBlank()) LanguageText(turn.original, turn.source, textSize - 2)
                            turn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (turn.target == language && turn.translation.isNotBlank()) com.sal7one.transiber.voice.ReadAloudButtons(uiText(UiR.string.ui_translation_ac26a), !state.busy, { system -> voiceError=null; voice.speak(turn,system) }, {faceHistory=null;onVoices()})
                            if (turn.translation.isBlank() && turn.original.isNotBlank()) TextButton(enabled = !state.busy && directionAllowed(turn.source, turn.target), onClick = { voice.stop(); controller.retry(turn, config) }) { Text(uiText(UiR.string.ui_retry_translation_0bd43)) }
                        }
                    } }
                }
                if (speaking) TextButton(onClick = { voice.stop() }) { Text(uiText(UiR.string.ui_stop_speech_3f0d2)) }
            }
        }
    } }
    if (history) Dialog(onDismissRequest = { history = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.padding(16.dp)) {
                Text(uiText(UiR.string.ui_saved_conversations_5a303), style = MaterialTheme.typography.headlineSmall)
                Text(uiText(UiR.string.ui_stored_on_this_device_only_no_audio_is_saved_b80ec))
                TextButton(onClick = { history = false }) { Text(uiText(UiR.string.ui_done_e9b45)) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.history.isEmpty()) item { Text(uiText(UiR.string.ui_no_saved_conversations_yet_ff5e7)) }
                    items(state.history, key = { it.id }) { session -> OutlinedCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium)
                            Text("${LanguageCatalog.option(session.first).nativeName} / ${LanguageCatalog.option(session.second).nativeName} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(session.created))}")
                            Text(session.turns.lastOrNull()?.original.orEmpty(), maxLines = 2)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { controller.openSession(session); history = false }) { Text(uiText(UiR.string.ui_continue_2e026)) }
                                TextButton(onClick = { share(session) }) { Text(uiText(UiR.string.ui_share_09ca5)) }
                                TextButton(onClick = { controller.deleteSession(session.id) }) { Text(uiText(UiR.string.ui_delete_f6fdb)) }
                            }
                        }
                    } }
                }
            }
        }
    }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text(uiText(UiR.string.ui_conversation_name_863c0)) }, text = { OutlinedTextField(title, { title = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { controller.rename(title); rename = false }) { Text(uiText(UiR.string.ui_save_efc00)) } }, dismissButton = { TextButton(onClick = { rename = false }) { Text(uiText(UiR.string.ui_cancel_77dfd)) } })
    presentation?.let { turn -> Dialog(onDismissRequest = { presentation = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.fillMaxSize().padding(24.dp).rotate(if (flipped) 180f else 0f)) {
                LazyColumn(Modifier.weight(1f)) { item { LanguageText(turn.translation, turn.target, 36f) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.sal7one.transiber.voice.ReadAloudButtons(uiText(UiR.string.ui_translation_ac26a), !state.busy, { system -> voiceError=null; voice.speak(turn,system) }, {presentation=null;onVoices()})
                    if(speaking)TextButton(onClick=voice::stop){Text(uiText(UiR.string.ui_stop_speech_3f0d2))}
                    TextButton(onClick = { flipped = !flipped }) { Text(uiText(UiR.string.ui_flip_5836b)) }
                    TextButton(onClick = { presentation = null }) { Text(uiText(UiR.string.ui_close_bbfa7)) }
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

package com.sal7one.transiber.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sal7one.transiber.R
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.home.TvSetupArtwork
import com.sal7one.transiber.i18n.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class TvSetupFeature(@param:StringRes val title: Int, @param:StringRes val detail: Int, @param:StringRes val action: Int) {
    STT(R.string.tv_stt_title, R.string.tv_stt_detail, R.string.tv_stt_action),
    TRANSLATE(R.string.tv_translate_title, R.string.tv_translate_detail, R.string.tv_translate_action),
    TTS(R.string.tv_tts_title, R.string.tv_tts_detail, R.string.tv_tts_action),
}

/** The TV flow deliberately exposes one provider; full settings keep every existing route. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvSetupScreen(feature: TvSetupFeature?, onFeature: (TvSetupFeature?) -> Unit, onHome: () -> Unit, onFullInterface: () -> Unit) {
    val text = rememberUiText()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by remember(context) { CaptionConfigStore.config(context) }.collectAsStateWithLifecycle(initialValue = null)
    val running by CaptionCaptureService.running.collectAsStateWithLifecycle()
    val actions = remember(context) { EasySetupActions(context) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val selectedFeature = feature ?: TvSetupFeature.STT
    val status = rememberTvSetupStatus(config)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(selectedFeature, config != null, running) {
        if (config != null) firstFocus.requestFocus()
    }
    fun save(block: suspend () -> Unit) {
        busy = true; error = null; notice = null
        scope.launch {
            try { block(); notice = text(R.string.tv_saved) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: e.toString() }
            finally { busy = false }
        }
    }
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(.38f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp, androidx.compose.ui.Alignment.CenterVertically)) {
            TvSetupArtwork()
            Text(text(R.string.tv_home_title), style = MaterialTheme.typography.headlineMedium)
            Text(text(if (ByokPolicy.FEATURE_BYOK) R.string.tv_openai_intro else R.string.tv_offline_intro), style = MaterialTheme.typography.bodyLarge)
            Text(text(if (status.configured) R.string.tv_setup_saved_status else R.string.tv_setup_once),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        Column(Modifier.weight(.62f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvSetupFeature.entries.forEach { choice ->
                    RemoteButton(text(when (choice) {
                        TvSetupFeature.STT -> R.string.tv_tab_speech
                        TvSetupFeature.TRANSLATE -> R.string.tv_tab_language
                        TvSetupFeature.TTS -> R.string.tv_tab_voice
                    }), selected = selectedFeature == choice, modifier = Modifier.weight(1f), enabled = !busy,
                        onClick = { onFeature(choice) })
                }
            }
            Surface(Modifier.weight(1f).fillMaxWidth(), shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer) {
            key(selectedFeature) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text(selectedFeature.detail), style = MaterialTheme.typography.titleMedium)
                val cfg = config
                if (cfg == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                if (running && selectedFeature != TvSetupFeature.TTS) {
                    Text(text(R.string.ui_stop_captions_to_change_setup_19d9d))
                    RemoteButton(text(R.string.ui_stop_captions_58b64), focus = firstFocus, onClick = { CaptionCaptureService.stop(context) })
                }
                when (selectedFeature) {
                    TvSetupFeature.STT -> {
                        if (ByokPolicy.FEATURE_BYOK) {
                            var key by remember { mutableStateOf("") } // Credentials never enter saved state.
                            var phoneSetup by remember { mutableStateOf(false) }
                            var phoneKeyboard by remember { mutableStateOf(false) }
                            var manualKey by remember { mutableStateOf(false) }
                            var moreEntry by remember { mutableStateOf(false) }
                            RemoteButton(text(if (status.configured) R.string.tv_change_key_phone else R.string.tv_pair_title), focus = if (!running) firstFocus else null, enabled = !busy && !running,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp), onClick = { phoneSetup = true })
                            TextButton(onClick = { moreEntry = !moreEntry }) { Text(text(R.string.tv_more_entry_options)) }
                            if (moreEntry) {
                                RemoteButton(text(R.string.tv_phone_keyboard), onClick = { phoneKeyboard = true })
                                TextButton(onClick = { manualKey = !manualKey }) { Text(text(R.string.tv_enter_on_tv)) }
                            }
                            if (phoneSetup) com.sal7one.transiber.tv.PhoneSetupDialog(cfg, onSaved = onHome) { phoneSetup = false }
                            if (phoneKeyboard) com.sal7one.transiber.tv.PhoneKeyboardHelpDialog { phoneKeyboard = false }
                            if (manualKey) {
                            Text(text(R.string.tv_key_help), style = MaterialTheme.typography.bodyLarge)
                            OutlinedTextField(key, { key = it }, enabled = !busy && !running, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), label = { Text(text(R.string.ui_api_key_cf678)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp))
                            RemoteButton(text(R.string.tv_save_openai), enabled = !busy && !running, onClick = {
                                save { actions.applyTvOpenAi(key); key = "" }
                            })
                            }
                        } else EasySetupLocalUi(onDone = { notice = text(R.string.tv_local_saved) }, onDownloads = {})
                        Text(text(R.string.ui_audio_acdac), style = MaterialTheme.typography.titleLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            listOf(CaptionSource.PLAYBACK_CAPTURE, CaptionSource.MIC).forEach { source ->
                                RemoteButton(text.label(source), selected = cfg.source == source, enabled = !busy && !running,
                                    onClick = { save { CaptionConfigStore.update(context) { it.copy(source = source) } } })
                            }
                        }
                    }
                    TvSetupFeature.TRANSLATE -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CaptionMode.entries.forEach { mode -> RemoteButton(text.label(mode), selected = cfg.mode == mode,
                                focus = if (!running && cfg.mode == mode) firstFocus else null,
                                enabled = !busy && !running, onClick = { save {
                                    if (ByokPolicy.FEATURE_BYOK && mode == CaptionMode.TRANSLATE) {
                                        check(cfg.engine == CaptionEngineChoice.CLOUD &&
                                            CloudConfigStore.sttMode(context) == CloudConfigStore.SttMode.STREAMING_OPENAI &&
                                            EasySetupPreset.canReuseKey(CloudConfigStore.Provider.OPENAI, CloudConfigStore.Provider.OPENAI.baseUrl,
                                                CloudConfigStore.provider(context), CloudConfigStore.baseUrl(context))) { text(R.string.tv_setup_stt_first) }
                                    }
                                    CaptionConfigStore.update(context) { it.withCaptionMode(mode) }
                                } }) }
                        }
                        var picker by remember { mutableStateOf(false) }
                        RemoteButton(text(R.string.ui_translate_to_1_s_f0b21, text.languageLabel(com.sal7one.common_jni.language.LanguageCatalog.option(cfg.target.languageTag))),
                            enabled = !busy && !running, onClick = { picker = true })
                        if (picker) Dialog(onDismissRequest = { picker = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                            Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                                LanguagePickerContent(text(R.string.ui_translate_to_a1ba6),
                                    if (ByokPolicy.FEATURE_BYOK) CaptionLanguages.openAiTranslation else CaptionLanguages.target(cfg, CloudConfigStore.sttMode(context)),
                                    cfg.target.languageTag, { target -> picker = false; save {
                                        CaptionConfigStore.update(context) { it.copy(target = TranslationTarget.of(target)) }
                                    } }, { picker = false })
                            }
                        }
                        Text(text(R.string.tv_translation_help), style = MaterialTheme.typography.bodyLarge)
                    }
                    TvSetupFeature.TTS -> {
                        var voice by remember { mutableStateOf("coral") }
                        LaunchedEffect(Unit) {
                            if (ByokPolicy.FEATURE_BYOK) try { voice = CloudVoiceStore.forProvider(context, CloudVoiceProvider.OPENAI).voice }
                            catch (e: Exception) { error = e.message ?: e.toString() }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            RemoteButton(text(R.string.tv_voice_on), focus = if (cfg.speakCaptions) firstFocus else null, selected = cfg.speakCaptions, enabled = !busy,
                                onClick = { save { actions.applyTvVoice(voice, true, cfg.speakerVolume) } })
                            RemoteButton(text(R.string.tv_voice_off), focus = if (!cfg.speakCaptions) firstFocus else null, selected = !cfg.speakCaptions, enabled = !busy,
                                onClick = { save { CaptionConfigStore.update(context) { it.copy(speakCaptions = false) } } })
                        }
                        if (ByokPolicy.FEATURE_BYOK && cfg.speakCaptions) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf("coral", "alloy", "nova", "onyx").forEach { choice ->
                                RemoteButton(choice.replaceFirstChar { it.uppercase() }, selected = voice == choice, enabled = !busy,
                                    onClick = { save { actions.applyTvVoice(choice, cfg.speakCaptions, cfg.speakerVolume); voice = choice } })
                            }
                        }
                        Text(text(R.string.ui_speaker_volume_label_b31f0, cfg.speakerVolume), style = MaterialTheme.typography.titleLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            RemoteButton(text(R.string.overlay_decrease), enabled = !busy && cfg.speakerVolume > 0,
                                onClick = { save { CaptionConfigStore.update(context) { it.copy(speakerVolume = it.speakerVolume - 10) } } })
                            RemoteButton(text(R.string.overlay_increase), enabled = !busy && cfg.speakerVolume < 100,
                                onClick = { save { CaptionConfigStore.update(context) { it.copy(speakerVolume = it.speakerVolume + 10) } } })
                        }
                        Text(text(R.string.tv_voice_help), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                (error ?: status.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RemoteButton(text(R.string.tv_done), modifier = Modifier.weight(1f), enabled = !busy,
                    focus = if (!ByokPolicy.FEATURE_BYOK && !running && selectedFeature == TvSetupFeature.STT) firstFocus else null, onClick = onHome)
                RemoteButton(text(R.string.tv_advanced_settings), modifier = Modifier.weight(1f), enabled = !busy, onClick = onFullInterface)
            }
        }
    }
}

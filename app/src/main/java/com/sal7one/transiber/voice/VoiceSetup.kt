package com.sal7one.transiber.voice

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.rememberUiText

import com.sal7one.transiber.settings.*
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceSetup(onDownloads: () -> Unit, initialLocation: SettingsLocation = SettingsLocation.LOCAL, entryRevision: Int = 0) {
    val uiText = rememberUiText()

    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var choice by remember {mutableStateOf(VoiceSettings.choice(context))}
    var error by remember {mutableStateOf<String?>(null)};var busy by remember {mutableStateOf(false)}
    var revision by remember {mutableIntStateOf(0)}
    val models=remember {VoiceModels(File(context.filesDir,"voice-models"))}
    var language by rememberSaveable {mutableStateOf("en")}
    var systemVoices by remember {mutableStateOf(emptyList<android.speech.tts.Voice>())}
    var speaking by remember {mutableStateOf(false)}
    val player=remember {VoicePlayer(context){active,e ->speaking=active;if(e!=null)error=e}}
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event ->if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP)player.stop()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    DisposableEffect(Unit) {
        var tts: TextToSpeech?=null
        tts=TextToSpeech(context){code ->if(code==TextToSpeech.SUCCESS)systemVoices=tts?.voices.orEmpty().filterNot {it.isNetworkConnectionRequired}.sortedBy {it.name}}
        onDispose {tts?.shutdown();player.close()}
    }
    LaunchedEffect(Unit){while(true){delay(1500);revision++}}
    fun save(value: VoiceChoice){choice=value;VoiceSettings.save(context,value);player.stop();error=null}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris ->if(uris.isNotEmpty())scope.launch {
        busy=true;error=null
        try {withContext(Dispatchers.IO){val job=currentCoroutineContext();for(uri in uris){context.contentResolver.openInputStream(uri)?.buffered()?.use {input ->input.mark(4);val a=input.read();val b=input.read();input.reset();if(a==0x50 && b==0x4b)models.importZip(input){job.ensureActive()} else models.import(input){job.ensureActive()}} ?: error(uiText(UiR.string.ui_cannot_open_voice_model_b4ec5))}};revision++}
        catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: e.toString()}finally{busy=false}
    }}
    SettingsTabs(initialLocation, entryRevision) { location ->
        SettingsHeading(if (location == SettingsLocation.LOCAL) uiText(UiR.string.ui_local_voices_29e8b) else uiText(UiR.string.ui_cloud_self_hosted_voices_b6c84),
            uiText(UiR.string.ui_shared_read_aloud_settings_for_captions_conversations_typed_text_c94c1))
        Text(when(VoiceSettings.customBackend(context)) {
            "supertonic" -> uiText(UiR.string.ui_custom_default_supertonic_3_1_s_2f603, choice.voice)
            "remote" -> uiText(UiR.string.ui_custom_default_your_configured_voice_server_c38eb)
            else -> uiText(UiR.string.ui_choose_supertonic_or_self_hosted_to_set_your_custom_default_c52b6)
        },style=MaterialTheme.typography.bodySmall)
        Text(uiText(UiR.string.ui_android_playback_is_always_separate_and_does_not_change_your_cust_56d2f),style=MaterialTheme.typography.bodySmall)
        Text(uiText(UiR.string.ui_speed_1_s_7d1e1, "%.2f".format(choice.rate)))
        Slider(choice.rate,{save(choice.copy(rate=it))},valueRange=.5f..2f,modifier=Modifier.semantics {contentDescription=uiText(UiR.string.ui_speech_speed_3f322)})
        if (location == SettingsLocation.LOCAL) {
            val localBackend = choice.backend.takeUnless { it == "remote" } ?: "system"
            SettingsVoiceLocalUi(choice, localBackend, ::save, language, { language = it }, systemVoices,
                models, revision, { revision++ }, busy, { importer.launch(arrayOf("*/*")) }, onDownloads, { error = it })
        } else {
            SettingsVoiceCloudUi(language, { language = it }, { error = it }, { save(choice.copy(backend = "system")) })
            OutlinedButton(onClick = {
                try { VoiceSettings.remote(context); save(choice.copy(backend = "remote")) }
                catch (e: Exception) { error = e.message ?: e.toString() }
            }) { Text(if (choice.backend == "remote") uiText(UiR.string.ui_using_voice_server_12b82) else uiText(UiR.string.ui_use_voice_server_as_custom_default_f0ea7)) }
        }
        var preview by rememberSaveable { mutableStateOf("Hello. Where is the nearest train station?") }
        OutlinedTextField(preview, { preview = it.take(5000) }, label = { Text(uiText(UiR.string.ui_text_to_preview_32ed3)) }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        val activeHere = if (location == SettingsLocation.CLOUD) choice.backend == "remote" else choice.backend != "remote"
        Button(enabled = preview.isNotBlank() && activeHere, onClick = { error = null; player.speak(preview, language) }) {
            Text(uiText(UiR.string.ui_preview_selected_voice_abd2c))
        }
        if (!activeHere) Text(uiText(UiR.string.ui_select_a_voice_in_this_tab_to_preview_it_8e4c3), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick=player::stop){Text(uiText(UiR.string.ui_stop_9e253))}
        if(speaking)Text(uiText(UiR.string.ui_preparing_or_playing_voice_4e5bc))
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}

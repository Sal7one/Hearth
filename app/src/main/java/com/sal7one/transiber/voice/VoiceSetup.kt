package com.sal7one.transiber.voice

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
        try {withContext(Dispatchers.IO){val job=currentCoroutineContext();for(uri in uris){context.contentResolver.openInputStream(uri)?.buffered()?.use {input ->input.mark(4);val a=input.read();val b=input.read();input.reset();if(a==0x50 && b==0x4b)models.importZip(input){job.ensureActive()} else models.import(input){job.ensureActive()}} ?: error("Cannot open voice model")}};revision++}
        catch(e: CancellationException){throw e}catch(e: Exception){error=e.message ?: e.toString()}finally{busy=false}
    }}
    SettingsTabs(initialLocation, entryRevision) { location ->
        SettingsHeading(if (location == SettingsLocation.LOCAL) "Local voices" else "Cloud / self-hosted voices",
            "Shared read-aloud settings for captions, conversations, typed text and camera. Switching tabs does not change your voice.")
        Text(when(VoiceSettings.customBackend(context)) {
            "supertonic" -> "Custom default: Supertonic 3 · ${choice.voice}"
            "remote" -> "Custom default: your configured voice server"
            else -> "Choose Supertonic or Self-hosted to set your custom default."
        },style=MaterialTheme.typography.bodySmall)
        Text("Android playback is always separate and does not change your custom default.",style=MaterialTheme.typography.bodySmall)
        Text("Speed · ${"%.2f".format(choice.rate)}×")
        Slider(choice.rate,{save(choice.copy(rate=it))},valueRange=.5f..2f,modifier=Modifier.semantics {contentDescription="Speech speed"})
        if (location == SettingsLocation.LOCAL) {
            val localBackend = choice.backend.takeUnless { it == "remote" } ?: "system"
            SettingsVoiceLocalUi(choice, localBackend, ::save, language, { language = it }, systemVoices,
                models, revision, { revision++ }, busy, { importer.launch(arrayOf("*/*")) }, onDownloads, { error = it })
        } else {
            SettingsVoiceCloudUi(language, { language = it }, { error = it }, { save(choice.copy(backend = "system")) })
            OutlinedButton(onClick = {
                try { VoiceSettings.remote(context); save(choice.copy(backend = "remote")) }
                catch (e: Exception) { error = e.message ?: e.toString() }
            }) { Text(if (choice.backend == "remote") "Using voice server" else "Use voice server as custom default") }
        }
        var preview by rememberSaveable { mutableStateOf("Hello. Where is the nearest train station?") }
        OutlinedTextField(preview, { preview = it.take(5000) }, label = { Text("Text to preview") }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        val activeHere = if (location == SettingsLocation.CLOUD) choice.backend == "remote" else choice.backend != "remote"
        Button(enabled = preview.isNotBlank() && activeHere, onClick = { error = null; player.speak(preview, language) }) {
            Text("Preview selected voice")
        }
        if (!activeHere) Text("Select a voice in this tab to preview it.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick=player::stop){Text("Stop")}
        if(speaking)Text("Preparing or playing voice…")
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}

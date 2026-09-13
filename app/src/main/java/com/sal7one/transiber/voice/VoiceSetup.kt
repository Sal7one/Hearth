package com.sal7one.transiber.voice

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.downloads.*
import kotlinx.coroutines.*
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceSetup(onDownloads: () -> Unit) {
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Read aloud",style=MaterialTheme.typography.headlineSmall)
        Text("Shared by typed translation, Conversation, Face to face and Camera. Caption read-aloud uses this selection when you choose Shared voice settings.")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("system" to "Android voices","supertonic" to "Supertonic 3").forEach {(id,label)->FilterChip(choice.backend==id,{save(choice.copy(backend=id))},label={Text(label)})}
            if(ByokPolicy.FEATURE_BYOK)FilterChip(choice.backend=="remote",{save(choice.copy(backend="remote"))},label={Text("Self-hosted")})
        }
        Text("Speed · ${"%.2f".format(choice.rate)}×")
        Slider(choice.rate,{save(choice.copy(rate=it))},valueRange=.5f..2f,modifier=Modifier.semantics {contentDescription="Speech speed"})
        when(choice.backend) {
            "system" -> {
                Text("Only installed offline Android voices are offered. Availability comes from this phone's TTS engine.")
                val languages=systemVoices.map {it.locale.language}.distinct().sorted()
                LaunchedEffect(languages){if(language !in languages && languages.isNotEmpty())language=languages.first()}
                VoiceMenu("Voice language",language,languages.map {it to LanguageCatalog.option(it).nativeName}){language=it}
                val list=systemVoices.filter {it.locale.language==language}
                val p=VoiceSettings.prefs(context)
                var selected by remember(language,revision){mutableStateOf(p.getString("system-voice-$language","").orEmpty())}
                VoiceMenu("Android voice",selected,listOf("" to "Automatic matching voice")+list.map {it.name to "${it.locale.getDisplayName()} · ${it.name}"}){selected=it;p.edit().apply {if(it.isBlank())remove("system-voice-$language") else putString("system-voice-$language",it)}.apply()}
                OutlinedButton(onClick={try{context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}catch(e: Exception){error=e.message}}){Text("Install or manage Android voices")}
            }
            "supertonic" -> {
                val ready=remember(revision,choice.voice){models.ready(choice.voice)}
                LaunchedEffect(Unit){if(language !in VoiceCatalog.languages)language="en"}
                Text("On-device · 31 languages including Arabic · 10 voices · about 383 MiB for all files. Model weights use OpenRAIL-M terms.")
                VoiceMenu("Voice",choice.voice,VoiceCatalog.voices.map {it to it}){save(choice.copy(voice=it))}
                Text("Quality steps · ${choice.steps}")
                Slider(choice.steps.toFloat(),{save(choice.copy(steps=it.toInt()))},valueRange=2f..12f,steps=9,modifier=Modifier.semantics {contentDescription="Supertonic quality steps"})
                Text("More steps can improve quality and take longer. Language support is model-specific; Chinese is not advertised by this model.",style=MaterialTheme.typography.bodySmall)
                Text(if(ready)"Selected engine and voice installed" else "Download or import the engine and selected voice")
                if(ByokPolicy.FEATURE_BYOK) {
                    val active=remember(revision){FileDownloads(context).list().filter {it.active && VoiceCatalog.assets.any {a->a.filename==it.title}}}
                    if(active.isNotEmpty())OutlinedButton(onClick=onDownloads){Text("Download progress · ${active.size} files")}
                    else if(!ready)Button(onClick={try {val dl=FileDownloads(context);(VoiceCatalog.core+VoiceCatalog.file("${choice.voice}.json")).filterNot(models::installed).forEach {dl.enqueue(DownloadSpec(it.url,it.filename),true,it.id)};revision++}catch(e: Exception){error=e.message}}){Text("Download engine & voice")}
                    TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://huggingface.co/Supertone/supertonic-3")))}){Text("Publisher, files & license")}
                }
                OutlinedButton(onClick={importer.launch(arrayOf("*/*"))},enabled=!busy){Text(if(busy)"Verifying…" else "Import voice files or ZIP")}
                Text("Select the ONNX engine files, its JSON files and one or more voice styles, or a ZIP containing them. Original downloads remain in Downloads/Hearth/models.",style=MaterialTheme.typography.bodySmall)
                VoiceMenu("Preview language",language,VoiceCatalog.languages.sorted().map {it to LanguageCatalog.option(it).nativeName}){language=it}
            }
            "remote" -> if(ByokPolicy.FEATURE_BYOK)RemoteVoiceSetup(language=language,onLanguage={language=it},onError={error=it},onForgot={save(choice.copy(backend="system"))})
        }
        var preview by rememberSaveable {mutableStateOf("Hello. Where is the nearest train station?")}
        OutlinedTextField(preview,{preview=it.take(5000)},label={Text("Text to preview")},modifier=Modifier.fillMaxWidth().padding(top=2.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={error=null;player.speak(preview,language)}){Text("Play preview")};OutlinedButton(onClick=player::stop){Text("Stop")}}
        if(speaking)Text("Preparing or playing voice…")
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }
}

@Composable
internal fun VoiceMenu(label: String,selected: String,values: List<Pair<String,String>>,choose: (String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    OutlinedButton(onClick={expanded=true},enabled=values.isNotEmpty(),modifier=Modifier.fillMaxWidth()) {Text("$label: ${values.firstOrNull {it.first==selected}?.second ?: "Choose"}")}
    if(expanded)AlertDialog(onDismissRequest={expanded=false},title={Text(label)},confirmButton={TextButton(onClick={expanded=false}){Text("Close")}},text={
        val scroll=androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex=values.indexOfFirst {it.first==selected}.coerceAtLeast(0))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max=400.dp),state=scroll) {
            items(values.size){index ->val (id,text)=values[index]
                TextButton(onClick={choose(id);expanded=false},modifier=Modifier.fillMaxWidth().semantics {this.selected=id==selected}){Text(if(id==selected)"✓ $text" else text)}
            }
        }
    })
}

@Composable
private fun RemoteVoiceSetup(language: String,onLanguage: (String)->Unit,onError: (String?)->Unit,onForgot: ()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val prefs=VoiceSettings.prefs(context)
    var endpoint by remember {mutableStateOf(prefs.getString("endpoint","").orEmpty())};var key by remember {mutableStateOf("")};var working by remember {mutableStateOf(false)}
    var capabilities by remember {mutableStateOf(runCatching{VoiceSettings.remote(context).capabilities}.getOrNull())}
    var selected by remember {mutableStateOf(prefs.getString("remote-voice","").orEmpty())}
    val client=remember {RemoteVoiceClient()};DisposableEffect(Unit){onDispose{client.close()}}
    Text("Text is sent to your HTTPS voice server. Chatterbox, Qwen3-TTS and Fish Speech require a separately running server; they are not embedded Android models. Language and voice choices come from that server.")
    OutlinedTextField(endpoint,{endpoint=it},label={Text("Hearth voice API base URL · HTTPS")},modifier=Modifier.fillMaxWidth().padding(top=2.dp),singleLine=true)
    OutlinedTextField(key,{key=it},label={Text(if(VoiceSettings.hasKey(context))"Replace saved API key" else "API key · if required")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().padding(top=2.dp),singleLine=true)
    Button(enabled=!working,onClick={working=true;onError(null);scope.launch {try {val secret=key.ifBlank {VoiceSettings.readKey(context)};val raw=client.execute(RemoteVoiceProtocol.request(endpoint,secret),secret).toString(Charsets.UTF_8);val found=RemoteVoiceProtocol.capabilities(raw);VoiceSettings.saveRemote(context,endpoint,key.takeIf(String::isNotBlank),raw);capabilities=found;key=""}catch(e: CancellationException){throw e}catch(e: Exception){onError(e.message ?: e.toString())}finally{working=false}}}){Text(if(working)"Checking…" else "Save & check voices")}
    capabilities?.let {caps ->
        LaunchedEffect(caps){if(language !in caps.languages)onLanguage(caps.languages.first());if(selected !in caps.voices)selected=caps.voices.first()}
        Text("${caps.label} · ${caps.languages.size} languages")
        VoiceMenu("Preview language",language,caps.languages.sorted().map {it to LanguageCatalog.option(it).nativeName},onLanguage)
        Text(caps.languages.sorted().joinToString {LanguageCatalog.option(it).nativeName},style=MaterialTheme.typography.bodySmall)
        VoiceMenu("Server voice",selected,caps.voices.map {it to it}){selected=it;prefs.edit().putString("remote-voice",it).apply()}
    }
    var sources by remember {mutableStateOf(false)}
    TextButton(onClick={sources=!sources}){Text(if(sources)"Hide model sources" else "Model sources & server setup")}
    if(sources) {
        Text("Run scripts/voice/hearth_voice_server.py from the Hearth repository on your own computer, behind HTTPS. Server setup and separate Python environments are documented in docs/voices.md.",style=MaterialTheme.typography.bodySmall)
        listOf(
            Triple("Chatterbox Multilingual V3 · 23 languages including Arabic · MIT","https://github.com/resemble-ai/chatterbox","Chatterbox source & setup"),
            Triple("Qwen3-TTS 0.6B Base · 10 languages, no Arabic · Apache-2.0 · needs your reference WAV and transcript","https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base","Qwen model & files"),
            Triple("Fish Speech · languages depend on your server · Research License; check commercial terms","https://github.com/fishaudio/fish-speech","Fish Speech source & setup")
        ).forEach {(description,url,label)->
            Text(description,style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={try{context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)))}catch(e: Exception){onError(e.message)}}){Text(label)}
        }
    }
    TextButton(onClick={VoiceSettings.forget(context);capabilities=null;onError(null);onForgot()}){Text("Forget voice connection")}
}

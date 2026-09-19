package com.sal7one.transiber.translation

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.voice.*

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
internal fun TypedTranslateScreen(onModels: ()->Unit,onConnections: ()->Unit,onVoices: ()->Unit,sharedText: String?=null,onShareConsumed: ()->Unit={}) {
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    val prefs=remember {context.getSharedPreferences("typed-translation",0)}
    var source by rememberSaveable {mutableStateOf(prefs.getString("source","en")!!)}
    var target by rememberSaveable {mutableStateOf(prefs.getString("target","ar")!!)}
    var text by rememberSaveable {mutableStateOf("")};var automatic by rememberSaveable {mutableStateOf(true)}
    LaunchedEffect(sharedText) {sharedText?.let {text=it;automatic=false;onShareConsumed()}}
    var picker by remember {mutableStateOf<String?>(null)};var options by remember {mutableStateOf(false)}
    var voiceError by remember {mutableStateOf<String?>(null)};var speaking by remember {mutableStateOf(false)}
    val config by remember {CaptionConfigStore.config(context)}.collectAsState(initial=CaptionOverlayConfig())
    val connectionRevision by ConversationTranslationSettings.revision.collectAsState()
    val label=remember(connectionRevision,config.localTranslationModelId){ConversationTranslationSettings.label(context,config.localTranslationModelId)}
    var resumeRevision by remember {mutableIntStateOf(0)}
    val controller=remember {TypedTranslationController(context)};val state by controller.state.collectAsState()
    val voice=remember {VoicePlayer(context){active,error->speaking=active;if(error!=null)voiceError=error}}
    val languages=remember(connectionRevision,config.localTranslationModelId){ConversationTranslationSettings.languages(context,config.localTranslationModelId)}
    fun update(immediate: Boolean=false) {
        voice.stop();voiceError=null
        try {controller.update(text,source,target,if(text.isBlank())null else ConversationTranslationSettings.snapshot(context,config.localTranslationModelId),immediate)}catch(e: Exception){controller.clear();controller.error(e)}
    }
    LaunchedEffect(text,source,target,automatic,connectionRevision,config.localTranslationModelId,resumeRevision) {
        prefs.edit().putString("source",source).putString("target",target).apply()
        if(automatic)update() else controller.clear()
    }
    DisposableEffect(lifecycle){val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP){voice.stop();controller.pause()};if(event==Lifecycle.Event.ON_START)resumeRevision++};lifecycle.lifecycle.addObserver(observer);onDispose {lifecycle.lifecycle.removeObserver(observer);voice.close();controller.close()}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            InputChip(false,{picker="source"},label={Text(LanguageCatalog.option(source).nativeName)},modifier=Modifier.semantics {contentDescription="Source language, ${LanguageCatalog.option(source).englishName}"})
            IconButton(onClick={val old=source;source=target;target=old;text=state.output.takeIf(String::isNotBlank) ?: text}) {Icon(Icons.AutoMirrored.Filled.CompareArrows,"Swap languages")}
            InputChip(false,{picker="target"},label={Text(LanguageCatalog.option(target).nativeName)},modifier=Modifier.semantics {contentDescription="Translation language, ${LanguageCatalog.option(target).englishName}"})
            TextButton(onClick={options=true}){Text("Options")}
        }
        Text(label,style=MaterialTheme.typography.bodySmall)
        if(ConversationTranslationSettings.provider(ConversationTranslationSettings.selected(context))!=null)
            Text(if(automatic)"Text is sent to $label after you pause typing." else "Text is sent to $label when you tap Translate.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(text,{text=it.take(3000)},label={Text("Type to translate")},minLines=4,maxLines=10,modifier=Modifier.fillMaxWidth().padding(top=2.dp),supportingText={Text("${text.length}/3000")})
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={update(true)},enabled=text.isNotBlank()){Text("Translate")}
            ReadAloudButtons("original",text.isNotBlank(),{system->voiceError=null;voice.speak(text,source,if(system)com.sal7one.transiber.voice.VoicePlaybackMode.SYSTEM else com.sal7one.transiber.voice.VoicePlaybackMode.CUSTOM)},onVoices)
            TextButton(onClick={text="";controller.clear();voice.stop()}){Text("Clear")}
        }
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Translation",style=MaterialTheme.typography.titleMedium)
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            SelectionContainer {Text(state.output.ifBlank {if(state.busy)"Translating…" else "Your translation appears here"},style=MaterialTheme.typography.headlineSmall)}
            if(state.output.isNotBlank())FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ReadAloudButtons("translation",onPlay={system->voiceError=null;voice.speak(state.output,target,if(system)com.sal7one.transiber.voice.VoicePlaybackMode.SYSTEM else com.sal7one.transiber.voice.VoicePlaybackMode.CUSTOM)},onSetup=onVoices)
                TextButton(onClick={context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Translation",state.output))}){Text("Copy")}
            }
        }}
        if(speaking)OutlinedButton(onClick=voice::stop){Text("Stop speech")}
        (voiceError ?: state.error)?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
    }
    picker?.let {which->Dialog(onDismissRequest={picker=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {LanguagePickerContent(
            title=if(which=="source")"Source language" else "Translate to",selected=if(which=="source")source else target,
            choices=CaptionLanguageChoices(if(which=="source")languages.filter {a->languages.any {b->ConversationTranslationSettings.supports(context,config.localTranslationModelId,a,b)}}.toSet() else languages.filter {ConversationTranslationSettings.supports(context,config.localTranslationModelId,source,it)}.toSet(),"$label · choose an explicit supported language"),
            onSelect={if(which=="source")source=it else target=it;picker=null},onDismiss={picker=null})}
    }}
    if(options)ModalBottomSheet(onDismissRequest={options=false}) {
        Column(Modifier.padding(20.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Translation & speech",style=MaterialTheme.typography.titleLarge)
            Row {Switch(automatic,{automatic=it},modifier=Modifier.semantics {contentDescription="Translate as I type"});Text("Translate as I type",Modifier.padding(12.dp))}
            Text("Uses the translation connection selected for Conversation. Voice settings are shared across Hearth.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={options=false;onModels()}){Text("Local translation models")}
            if(com.sal7one.transiber.byok.ByokPolicy.FEATURE_BYOK)OutlinedButton(onClick={options=false;onConnections()}){Text("Translation connections")}
            OutlinedButton(onClick={options=false;onVoices()}){Text("Voices & read aloud")}
        }
    }
}

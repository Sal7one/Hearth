package com.sal7one.transiber.translation

import com.sal7one.transiber.ui.theme.glassPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Clear
import com.sal7one.transiber.ui.components.FeatureAction
import com.sal7one.transiber.ui.components.FeatureOptionsSheet
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
internal fun TypedTranslateScreen(onModels: ()->Unit,onConnections: ()->Unit,onVoices: ()->Unit,sharedText: String?=null,onShareConsumed: ()->Unit={}) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current
    val prefs=remember {context.getSharedPreferences("typed-translation",0)}
    var source by rememberSaveable {mutableStateOf(prefs.getString("source","en")!!)}
    var target by rememberSaveable {mutableStateOf(prefs.getString("target","ar")!!)}
    var text by rememberSaveable {mutableStateOf("")};var automatic by rememberSaveable {mutableStateOf(true)}
    LaunchedEffect(sharedText) {sharedText?.let {text=it;automatic=false;onShareConsumed()}}
    var picker by remember {mutableStateOf<String?>(null)};var options by rememberSaveable {mutableStateOf(false)}
    val optionsScroll=rememberScrollState()
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
        }
        if(ConversationTranslationSettings.provider(ConversationTranslationSettings.selected(context))!=null)
            Text(if(automatic)"Text is sent to $label after you pause typing." else "Text is sent to $label when you tap Translate.",style=MaterialTheme.typography.bodySmall)
        TextField(text,{text=it.take(3000)},label={Text("Enter text")},minLines=4,maxLines=10,shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth().padding(top=2.dp).glassPanel(),
            colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,
                focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),
            trailingIcon={if(text.isNotBlank())IconButton(onClick={text="";controller.clear();voice.stop()}){Icon(Icons.Default.Clear,"Clear text")}},
            supportingText={if(text.length>2700)Text("${text.length}/3000")})
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={update(true)},enabled=text.isNotBlank(),modifier=Modifier.heightIn(min=52.dp)){Text("Translate")}
            ReadAloudButtons("original",text.isNotBlank(),{system->voiceError=null;voice.speak(text,source,if(system)com.sal7one.transiber.voice.VoicePlaybackMode.SYSTEM else com.sal7one.transiber.voice.VoicePlaybackMode.CUSTOM)},onVoices)
        }
        Card(Modifier.fillMaxWidth().glassPanel(), colors=CardDefaults.cardColors(containerColor=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Translation",style=MaterialTheme.typography.titleMedium)
            if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            SelectionContainer {Text(state.output.ifBlank {if(state.busy)"Translating…" else "Your translation appears here"},style=MaterialTheme.typography.headlineSmall)}
            if(state.output.isNotBlank())FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ReadAloudButtons("translation",onPlay={system->voiceError=null;voice.speak(state.output,target,if(system)com.sal7one.transiber.voice.VoicePlaybackMode.SYSTEM else com.sal7one.transiber.voice.VoicePlaybackMode.CUSTOM)},onSetup=onVoices)
                TextButton(onClick={context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Translation",state.output))}){Text("Copy")}
            }
        }}
        FeatureAction("Translation settings", Icons.Default.Tune, { options=true }, detail=label)
        if(speaking)OutlinedButton(onClick=voice::stop){Text("Stop speech")}
        (voiceError ?: state.error)?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
    }
    picker?.let {which->Dialog(onDismissRequest={picker=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {LanguagePickerContent(
            title=if(which=="source")"Source language" else "Translate to",selected=if(which=="source")source else target,
            choices=CaptionLanguageChoices(if(which=="source")languages.filter {a->languages.any {b->ConversationTranslationSettings.supports(context,config.localTranslationModelId,a,b)}}.toSet() else languages.filter {ConversationTranslationSettings.supports(context,config.localTranslationModelId,source,it)}.toSet(),"$label · choose an explicit supported language"),
            onSelect={if(which=="source")source=it else target=it;picker=null},onDismiss={picker=null})}
    }}
    if(options)FeatureOptionsSheet("Translation settings", {options=false}, optionsScroll) {
        TranslatorChooser(ConversationTranslationSettings.selected(context),config.localTranslationModelId,
            "Used by typed text, Conversation and Face to face.",source,target,onModels={options=false;onModels()},
            onSelect={provider,model->scope.launch {
                try { CaptionConfigStore.update(context){it.copy(localTranslationModelId=model)};ConversationTranslationSettings.select(context,provider) }
                catch(e: kotlinx.coroutines.CancellationException){throw e}
                catch(e: Exception){controller.error(e)}
            }})
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("Translate as I type",Modifier.weight(1f))
            Switch(automatic,{automatic=it},modifier=Modifier.semantics {contentDescription="Translate as I type"})
        }
        OutlinedButton(onClick={options=false;onVoices()},modifier=Modifier.fillMaxWidth()){Text("Voices & read aloud")}
        if(com.sal7one.transiber.byok.ByokPolicy.FEATURE_BYOK)TextButton(onClick={options=false;onConnections()}){Text("Manage cloud connections")}
    }
}

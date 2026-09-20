package com.sal7one.transiber.ocr

import com.sal7one.transiber.settings.SettingsOcrLocalUi
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.common_jni.language.LanguageCatalog
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.translation.*
import kotlinx.coroutines.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
internal fun CameraTranslateScreen(onModels: () -> Unit,onConnections: () -> Unit,onDownloads: () -> Unit, onVoices: () -> Unit = {}, sharedImage: android.net.Uri? = null, onShareConsumed: () -> Unit = {}) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current
    val prefs=remember {context.getSharedPreferences("camera-translate",0)}
    var profileId by rememberSaveable {mutableStateOf(prefs.getString("profile","latin")!!)}
    var source by rememberSaveable {mutableStateOf(prefs.getString("source","en")!!)}
    var target by rememberSaveable {mutableStateOf(prefs.getString("target","ar")!!)}
    var providerId by rememberSaveable {mutableStateOf(if(ByokPolicy.FEATURE_BYOK)prefs.getString("provider","local")!! else "local")}
    var translate by rememberSaveable {mutableStateOf(prefs.getBoolean("translate",true))}
    val translationRevision by ConversationTranslationSettings.revision.collectAsState()
    LaunchedEffect(translationRevision) { providerId=if(ByokPolicy.FEATURE_BYOK)prefs.getString("provider","local")!! else "local" }
    val profile=OcrCatalog.profiles.firstOrNull {it.id==profileId} ?: OcrCatalog.profiles.first()
    val config by remember { CaptionConfigStore.config(context) }.collectAsState(initial=CaptionOverlayConfig())
    var speaking by remember {mutableStateOf(false)}
    var voiceError by remember {mutableStateOf<String?>(null)}
    val voice=remember {com.sal7one.transiber.voice.VoicePlayer(context){active,error->speaking=active;if(error!=null)voiceError=error}}
    val controller=remember {CameraOcrController(context)};val state by controller.state.collectAsState()
    val models=remember {OcrModels(File(context.filesDir,"ocr-models"))}
    var ready by remember {mutableStateOf(models.ready(profile))}
    var permission by remember {mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    val settingsScroll=rememberScrollState()
    var settings by rememberSaveable {mutableStateOf(false)};var picker by remember {mutableStateOf<String?>(null)}
    var capture by remember {mutableStateOf<(() -> Unit)?>(null)}
    var selectionImage by remember {mutableStateOf<Bitmap?>(null)}
    var frozen by remember {mutableStateOf<Bitmap?>(null)}
    var importing by remember {mutableStateOf(false)}
    val provider=ConversationTranslationSettings.provider(providerId)
    val cloudLanguages=provider?.let {ConversationTranslationSettings.capabilities(context,it)}
    val targetCodes=if(provider==null) {
        if(config.localTranslationModelId==TranslationOptions.ML_KIT) {
            if(source in TranslationOptions.mlKitCodes && ByokPolicy.FEATURE_BYOK) TranslationOptions.mlKitCodes else emptySet()
        } else com.sal7one.common_jni.translation.TranslationCatalog.models.firstOrNull {it.id==config.localTranslationModelId}
            ?.let {spec -> spec.targetLanguages.filter {spec.supports(source,it)}.toSet()}.orEmpty()
    } else cloudLanguages?.targetLanguages.orEmpty().filter {cloudLanguages?.supports(source,it)==true}.toSet()
    val providerLabel=provider?.label ?: TranslationOptions.label(config.localTranslationModelId)
    val permissionRequest=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permission=it;if(!it)controller.error(IllegalStateException("Camera permission denied. Allow it in Android app settings, or import a photo."))}
    fun snapshot(): ConversationTranslatorSnapshot? {
        if(!translate)return null
        check(source in profile.languages) {"Choose a text language supported by ${profile.label}"}
        if(source==target)return null
        check(target in targetCodes) {"$providerLabel does not support $source → $target. Choose a supported language pair or translator."}
        if(provider==null)return ConversationTranslatorSnapshot(config.localTranslationModelId)
        return ConversationTranslatorSnapshot(config.localTranslationModelId,ConversationTranslationSettings.connection(context,provider),checkNotNull(cloudLanguages){"Check ${provider.label} languages in Translation connections first"})
    }
    fun begin(live: Boolean, bitmap: Bitmap?=null) {
        try {
            if(bitmap != null)frozen=bitmap
            controller.start(profile,source,target,snapshot(),live,bitmap?.copy(Bitmap.Config.ARGB_8888,false))
        } catch(e: Exception){controller.error(e)}
    }
    fun recognizePhoto(bitmap: Bitmap) {
        frozen=bitmap
        if(controller.state.value.closing) scope.launch { controller.awaitStopped();begin(false,bitmap) }
        else if(controller.state.value.running) {controller.freeze();controller.offer(bitmap.copy(Bitmap.Config.ARGB_8888,false),true)} else begin(false,bitmap)
    }
    fun photo(bitmap: Bitmap) {
        if(profile.engine == "manga") {controller.stop();frozen=bitmap;selectionImage=bitmap}
        else recognizePhoto(bitmap)
    }
    fun importPhoto(uri: android.net.Uri) { scope.launch {
        importing=true
        try {
            val bitmap=withContext(Dispatchers.IO){ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)){decoder,info,_ ->
                check(info.size.width>0 && info.size.height>0 && info.size.width.toLong()*info.size.height<=100_000_000) {"Photo dimensions exceed the 100 megapixel import limit"}
                val scale=minOf(1f,1600f/maxOf(info.size.width,info.size.height));decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()));decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }}
            photo(bitmap)
        }catch(e: CancellationException){throw e}catch(e: Exception){controller.error(e)}finally{importing=false}
    }}
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri -> if(uri!=null)importPhoto(uri)}
    LaunchedEffect(sharedImage) {sharedImage?.let {importPhoto(it);onShareConsumed()}}
    LaunchedEffect(profileId,source,target,providerId,translate) {prefs.edit().putString("profile",profileId).putString("source",source).putString("target",target).putString("provider",providerId).putBoolean("translate",translate).apply()}
    LaunchedEffect(profileId) {while(true){ready=models.ready(profile);delay(1200)}}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_STOP){controller.stop();voice.stop()}}
        lifecycle.lifecycle.addObserver(observer)
        onDispose {lifecycle.lifecycle.removeObserver(observer);controller.close();voice.close()}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Manga & books on screen",style=MaterialTheme.typography.titleMedium)
                Text("Translate pages in your reader, or draw around one speech bubble.")
                Button(onClick={
                    controller.stop()
                    scope.launch {
                        controller.awaitStopped()
                        prefs.edit().putString("profile",profileId).putString("source",source).putString("target",target).putString("provider",providerId).putBoolean("translate",translate).apply()
                        context.startActivity(android.content.Intent(context,com.sal7one.transiber.reading.ReadingStartActivity::class.java))
                    }
                }){Text("Open reading overlay")}
            }
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            InputChip(selected=false,onClick={picker="source"},enabled=!state.running,label={Text(LanguageCatalog.option(source).nativeName)},modifier=Modifier.semantics {contentDescription="Text language, ${LanguageCatalog.option(source).englishName}"})
            Text("→",Modifier.align(Alignment.CenterVertically))
            InputChip(selected=false,onClick={picker="target"},enabled=!state.running && translate,label={Text(LanguageCatalog.option(target).nativeName)},modifier=Modifier.semantics {contentDescription="Translate to, ${LanguageCatalog.option(target).englishName}"})
            TextButton(onClick={settings=true},enabled=!state.running){Text("Settings")}
        }
        TextButton(onClick={settings=true}) { Text(if(translate)"Translator · $providerLabel" else "Original text only · settings") }
        if(translate && provider != null)Text("Only recognized text is sent to ${provider.label}. Camera images stay on your phone.",style=MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth().height(280.dp).background(Color.Black),contentAlignment=Alignment.Center) {
            if(permission && frozen==null && !importing && sharedImage==null)CameraPreview(Modifier.fillMaxSize(),state.live && !state.loading,
                onFrame={controller.offer(it)},onCaptureReady={capture=it},onPhoto={photo(it)},onError=controller::error)
            else if(!permission && frozen==null)Button(onClick={permissionRequest.launch(Manifest.permission.CAMERA)}){Text("Open camera")}
            frozen?.let {Image(it.asImageBitmap(),"Captured image",Modifier.fillMaxSize().background(Color.Black),contentScale=ContentScale.Fit)}
            if(state.lines.isNotEmpty() && (frozen!=null || state.live))Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                val scale=minOf(size.width/state.width,size.height/state.height)
                val dx=(size.width-state.width*scale)/2;val dy=(size.height-state.height*scale)/2
                state.lines.forEach {line -> drawRect(Color(0xFFFFD54F),Offset(dx+line.x*scale,dy+line.y*scale),Size(line.width*scale,line.height*scale),style=Stroke(2.dp.toPx()))}
            }
        }
        if(!ready) {
            Text("Install the ${profile.label} model files to begin. OCR works offline after installation.")
            OutlinedButton(onClick={settings=true}){Text("Get OCR models")}
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Button(onClick={frozen=null;begin(true)},enabled=profile.live&&ready&&permission&&!state.running&&!importing&&capture!=null){Text("Start live")}
            Button(onClick={capture?.invoke()},enabled=frozen==null&&ready&&permission&&capture!=null&&!state.loading&&!state.closing&&!importing){Text(if(translate) "Capture & translate" else "Capture & read")}
            OutlinedButton(onClick={imagePicker.launch(arrayOf("image/*"))},enabled=ready&&!state.loading&&!state.closing&&!importing){Text(if(importing)"Opening…" else "Import photo")}
            if(state.running)OutlinedButton(onClick={controller.stop()},enabled=!state.closing){Text(if(state.closing)"Stopping…" else "Stop")}
            if(frozen!=null)OutlinedButton(onClick={controller.stop();selectionImage=frozen},enabled=!state.loading&&!state.closing){Text("Draw text area")}
            if(frozen!=null && state.error!=null)OutlinedButton(onClick={frozen?.let(::recognizePhoto)},enabled=!state.loading&&!state.closing){Text("Retry")}
            if(frozen!=null)TextButton(onClick={controller.stop();frozen=null}){Text("Retake")}
        }
        if(state.loading || state.translating || state.closing)LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(when{state.closing->"Releasing models…";state.loading->"Loading ${profile.label}…";state.translating->"Translating settled text…";state.live->"Live · OCR ${state.ocrMs} ms · hold steady";state.running->"Captured · OCR ${state.ocrMs} ms";else->"Ready · camera opens only on this page"},style=MaterialTheme.typography.bodySmall)
        (voiceError ?: state.error)?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        if(state.running && !state.loading && state.text.isBlank())Text("No readable text yet. Hold the phone level, move closer, or capture for a larger reading image.")
        if(speaking)OutlinedButton(onClick=voice::stop){Text("Stop speech")}
        if(state.text.isNotBlank()) {
            Text("Original",style=MaterialTheme.typography.titleSmall)
            SelectionContainer{Text(state.text)}
            if(state.translation.isNotBlank()){Text("Translation",style=MaterialTheme.typography.titleSmall);SelectionContainer{Text(state.translation,style=MaterialTheme.typography.titleLarge)}}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                com.sal7one.transiber.voice.ReadAloudButtons("original",onPlay={system->voiceError=null;voice.speak(state.text,source,if(system)com.sal7one.transiber.voice.VoicePlaybackMode.SYSTEM else com.sal7one.transiber.voice.VoicePlaybackMode.CUSTOM)},onSetup=onVoices)
                if(state.translation.isNotBlank())com.sal7one.transiber.voice.ReadAloudButtons("translation",onPlay={system->voiceError=null;voice.speak(state.translation,target,if(system)com.sal7one.transiber.voice.VoicePlaybackMode.SYSTEM else com.sal7one.transiber.voice.VoicePlaybackMode.CUSTOM)},onSetup=onVoices)
                TextButton(onClick={(context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText("Original text",state.text))}){Text("Copy original")}
                if(state.translation.isNotBlank())TextButton(onClick={context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Translation",state.translation))}){Text("Copy translation")}
            }
        }
    }
    selectionImage?.let { image -> DrawOcrRegion(image,onDismiss={selectionImage=null},onConfirm={crop->
        selectionImage=null
        scope.launch {controller.stop();controller.awaitStopped();begin(false,crop)}
    }) }
    picker?.let { which -> Dialog(onDismissRequest={picker=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {LanguagePickerContent(
            title=if(which=="source")"Text language" else "Translate to",
            choices=CaptionLanguageChoices(if(which=="source")profile.languages else targetCodes,
                if(which=="source")"${profile.label} reads these scripts. This choice tells the translator the source language; it does not force the OCR decoder." else "$providerLabel · targets supported from ${LanguageCatalog.option(source).englishName}"),
            selected=if(which=="source")source else target,onSelect={if(which=="source")source=it else target=it;picker=null},onDismiss={picker=null})}
    }}
    if(settings)ModalBottomSheet(onDismissRequest={settings=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.verticalScroll(settingsScroll).padding(20.dp).navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Camera & reading settings",style=MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick={voice.stop();settings=false;onVoices()}){Text("Voices & read aloud")}
            Row(verticalAlignment=Alignment.CenterVertically){Switch(checked=translate,onCheckedChange={translate=it},modifier=Modifier.semantics {contentDescription="Translate recognized text"});Text("Translate recognized text",Modifier.padding(start=8.dp))}
            TranslatorChooser(providerId,config.localTranslationModelId,"Used by Camera and the screen-reading overlay.",source,target,
                enabled=!state.running && !state.closing, onModels={settings=false;onModels()}, onSelect={provider,model->scope.launch {
                    try { CaptionConfigStore.update(context){it.copy(localTranslationModelId=model)};providerId=provider }
                    catch(e: kotlinx.coroutines.CancellationException){throw e}
                catch(e: Exception){controller.error(e)}
                }})
            if(state.running || state.closing)Text("Stop recognition to change its translator.",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            SettingsOcrLocalUi(onDownloads={settings=false;onDownloads()},selected=profileId,onSelect={profileId=it;val p=OcrCatalog.profile(it);if(source !in p.languages)source=p.languages.first()})
        }
    }
}

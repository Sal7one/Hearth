package com.sal7one.transiber.ocr

import com.sal7one.transiber.settings.SettingsOcrLocalUi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.AutoStories
import com.sal7one.transiber.ui.components.FeatureAction
import com.sal7one.transiber.ui.components.FeatureOptionsSheet
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sal7one.transiber.byok.ByokPolicy
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.translation.*
import kotlinx.coroutines.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
internal fun CameraTranslateScreen(onModels: () -> Unit,onConnections: () -> Unit,onDownloads: () -> Unit, onVoices: () -> Unit = {}, sharedImage: android.net.Uri? = null, onShareConsumed: () -> Unit = {}, onReading: () -> Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current
    val (selection,selectionStore)=rememberOcrPreferences()
    val profileId=selection.profileId;val source=selection.source;val target=selection.target
    val providerId=selection.providerId;val translate=selection.translate
    val translationRevision by ConversationTranslationSettings.revision.collectAsState()
    val profile=selection.profile
    val config by remember { CaptionConfigStore.config(context) }.collectAsState(initial=CaptionOverlayConfig())
    val currentConfig by rememberUpdatedState(config)
    var speaking by remember {mutableStateOf(false)}
    var voiceError by remember {mutableStateOf<String?>(null)}
    val voice=remember {com.sal7one.transiber.voice.VoicePlayer(context){active,error->speaking=active;if(error!=null)voiceError=error}}
    val controller=remember {CameraOcrController(context)};val state by controller.state.collectAsState()
    var previousSettings by remember {mutableStateOf(selection to config.localTranslationModelId)}
    LaunchedEffect(selection,config.localTranslationModelId) {
        val next=selection to config.localTranslationModelId
        if(next!=previousSettings) {controller.stop();controller.invalidate();voice.stop();previousSettings=next}
    }
    val models=remember {OcrModels(File(context.filesDir,"ocr-models"))}
    var ready by remember {mutableStateOf(models.ready(profile))}
    var permission by remember {mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)}
    val settingsScroll=rememberScrollState()
    var settings by rememberSaveable {mutableStateOf(false)}
    var capture by remember {mutableStateOf<(() -> Unit)?>(null)}
    var selectionImage by remember {mutableStateOf<Bitmap?>(null)}
    var frozen by remember {mutableStateOf<Bitmap?>(null)}
    var importing by remember {mutableStateOf(false)}
    val provider=ConversationTranslationSettings.provider(providerId)
    val cloudLanguages=remember(provider,translationRevision) {provider?.let {ConversationTranslationSettings.capabilities(context,it)}}
    val targetCodes=ocrTranslationTargets(selection,config.localTranslationModelId,cloudLanguages,ByokPolicy.FEATURE_BYOK)
    val providerLabel=provider?.label ?: TranslationOptions.label(config.localTranslationModelId)
    fun changeSelection(next: OcrSelection) {
        controller.stop();controller.invalidate();voice.stop()
        selectionStore.update { next }
    }
    val permissionRequest=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permission=it;if(!it)controller.error(IllegalStateException("Camera permission denied. Allow it in Android app settings, or import a photo."))}
    // Capture callbacks can outlive the composition that created them. Snapshot the
    // current saved selection at the action boundary, never a captured old profile.
    fun snapshot(selected: OcrSelection): ConversationTranslatorSnapshot? {
        if(!selected.translate)return null
        check(selected.source in selected.profile.languages) {"Choose a text language supported by ${selected.profile.label}"}
        val selectedProvider=ConversationTranslationSettings.provider(selected.providerId)
        val capabilities=selectedProvider?.let {ConversationTranslationSettings.capabilities(context,it)}
        val localId=currentConfig.localTranslationModelId
        check(selected.target in ocrTranslationTargets(selected,localId,capabilities,ByokPolicy.FEATURE_BYOK)) {
            "${selectedProvider?.label ?: TranslationOptions.label(localId)} does not support ${selected.source} → ${selected.target}. Choose a supported language pair or translator."
        }
        return ConversationTranslationSettings.snapshot(context,localId,selected.providerId)
    }
    fun begin(live: Boolean, bitmap: Bitmap?=null) {
        try {
            val selected=selectionStore.read()
            if(bitmap != null)frozen=bitmap
            controller.start(selected.profile,selected.source,selected.target,snapshot(selected),live,bitmap?.copy(Bitmap.Config.ARGB_8888,false))
        } catch(e: Exception){controller.error(e)}
    }
    fun recognizePhoto(bitmap: Bitmap) {
        frozen=bitmap
        if(controller.state.value.closing) scope.launch { controller.awaitStopped();begin(false,bitmap) }
        else if(controller.state.value.running) {controller.freeze();controller.offer(bitmap.copy(Bitmap.Config.ARGB_8888,false),true)} else begin(false,bitmap)
    }
    fun photo(bitmap: Bitmap) {
        if(selectionStore.read().profile.engine == "manga") {controller.stop();frozen=bitmap;selectionImage=bitmap}
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
    LaunchedEffect(profileId) {while(true){ready=models.ready(profile);delay(1200)}}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_STOP){controller.stop();voice.stop()}}
        lifecycle.lifecycle.addObserver(observer)
        onDispose {lifecycle.lifecycle.removeObserver(observer);controller.close();voice.close()}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OcrLanguageControls(selection,targetCodes,providerLabel,::changeSelection,{settings=true},enabled=!state.closing)
        if(frozen!=null && !state.running && state.text.isBlank())Text("Settings changed? Tap Translate again to update this photo.",style=MaterialTheme.typography.bodySmall)
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
            if(frozen==null)Button(onClick={capture?.invoke()},modifier=Modifier.heightIn(min=56.dp),enabled=ready&&permission&&capture!=null&&!state.loading&&!state.closing&&!importing){Text(if(translate) "Capture & translate" else "Capture & read")}
            if(frozen==null)OutlinedButton(onClick={imagePicker.launch(arrayOf("image/*"))},modifier=Modifier.heightIn(min=56.dp),enabled=ready&&!state.loading&&!state.closing&&!importing){Text(if(importing)"Opening…" else "Choose photo")}
            if(frozen==null && profile.live)TextButton(onClick={begin(true)},enabled=ready&&permission&&!state.running&&!importing&&capture!=null){Text("Start live")}

            if(state.running)OutlinedButton(onClick={controller.stop()},enabled=!state.closing){Text(if(state.closing)"Stopping…" else "Stop")}
            if(frozen!=null)OutlinedButton(onClick={controller.stop();selectionImage=frozen},enabled=!state.loading&&!state.closing){Text("Draw text area")}
            if(frozen!=null)OutlinedButton(onClick={frozen?.let(::photo)},enabled=!state.loading&&!state.closing){Text(if(translate) "Translate again" else "Read again")}
            if(frozen!=null)TextButton(onClick={controller.stop();frozen=null}){Text("Retake")}
        }
        FeatureAction("Camera settings", Icons.Default.Tune, {settings=true}, detail=if(translate)providerLabel else "Original text only")
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
        FeatureAction("Screen & manga", Icons.Default.AutoStories, {

                    controller.stop()
                    scope.launch {
                        controller.awaitStopped()
                        onReading()
                    }

        }, detail="Translate in another app")
    }
    selectionImage?.let { image -> DrawOcrRegion(image,onDismiss={selectionImage=null},onConfirm={crop->
        selectionImage=null
        scope.launch {controller.stop();controller.awaitStopped();begin(false,crop)}
    }) }
    if(settings)FeatureOptionsSheet("Camera & reading settings", {settings=false}, settingsScroll) {
            OutlinedButton(onClick={voice.stop();settings=false;onVoices()}){Text("Voices & read aloud")}
            TranslatorChooser(providerId,config.localTranslationModelId,"Used by Camera and the screen-reading overlay.",source,target,
                enabled=!state.closing, onModels={settings=false;onModels()}, onSelect={provider,model->scope.launch {
                    try { controller.stop();controller.invalidate();voice.stop();CaptionConfigStore.update(context){it.copy(localTranslationModelId=model)};selectionStore.update {it.copy(providerId=provider)} }
                    catch(e: kotlinx.coroutines.CancellationException){throw e}
                catch(e: Exception){controller.error(e)}
                }})
            Text("Changing settings stops recognition. Your captured photo stays available for Translate again.",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            SettingsOcrLocalUi(onDownloads={settings=false;onDownloads()},selected=profileId,onSelect={changeSelection(selection.withProfile(it));settings=false})
    }
}

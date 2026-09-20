package com.sal7one.transiber.ocr

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

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
    val uiText = rememberUiText()

    val context=LocalContext.current;val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current
    val (selection,selectionStore)=rememberOcrPreferences()
    val profileId=selection.profileId;val source=selection.source;val target=selection.target
    val providerId=selection.providerId;val translate=selection.translate
    val translationRevision by ConversationTranslationSettings.revision.collectAsState()
    val profile=selection.profile
    val config by remember { CaptionConfigStore.config(context) }.collectAsState(initial=CaptionOverlayConfig())
    val currentConfig by rememberUpdatedState(config)
    val localModelId = selection.translationModel(config.localTranslationModelId)
    var speaking by remember {mutableStateOf(false)}
    var voiceError by remember {mutableStateOf<String?>(null)}
    val voice=remember {com.sal7one.transiber.voice.VoicePlayer(context){active,error->speaking=active;if(error!=null)voiceError=error}}
    val controller=remember {CameraOcrController(context)};val state by controller.state.collectAsState()
    var previousSettings by remember {mutableStateOf(selection to localModelId)}
    LaunchedEffect(selection,localModelId) {
        val next=selection to localModelId
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
    val targetCodes=ocrTranslationTargets(selection,localModelId,cloudLanguages,ByokPolicy.FEATURE_BYOK)
    val providerLabel=provider?.label ?: TranslationOptions.label(localModelId)
    fun changeSelection(next: OcrSelection) {
        controller.stop();controller.invalidate();voice.stop()
        selectionStore.update { next }
    }
    val permissionRequest=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permission=it;if(!it)controller.error(IllegalStateException(uiText(UiR.string.ui_camera_permission_denied_allow_it_in_android_app_settings_or_impo_7f7c7)))}
    // Capture callbacks can outlive the composition that created them. Snapshot the
    // current saved selection at the action boundary, never a captured old profile.
    fun snapshot(selected: OcrSelection): ConversationTranslatorSnapshot? {
        if(!selected.translate)return null
        check(selected.source in selected.profile.languages) {uiText(UiR.string.ui_choose_a_text_language_supported_by_1_s_4940a, uiText.label(selected.profile))}
        val selectedProvider=ConversationTranslationSettings.provider(selected.providerId)
        val capabilities=selectedProvider?.let {ConversationTranslationSettings.capabilities(context,it)}
        val localId=selected.translationModel(currentConfig.localTranslationModelId)
        check(selected.target in ocrTranslationTargets(selected,localId,capabilities,ByokPolicy.FEATURE_BYOK)) {
            uiText(UiR.string.ui_1_s_does_not_support_2_s_3_s_choose_a_supported_language_pair_or_c8a2b, selectedProvider?.label ?: TranslationOptions.label(localId), selected.source, selected.target)
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
                check(info.size.width>0 && info.size.height>0 && info.size.width.toLong()*info.size.height<=100_000_000) {uiText(UiR.string.ui_photo_dimensions_exceed_the_100_megapixel_import_limit_d37e7)}
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
        if(frozen!=null && !state.running && state.text.isBlank())Text(uiText(UiR.string.ui_settings_changed_tap_translate_again_to_update_this_photo_f837e),style=MaterialTheme.typography.bodySmall)
        if(translate && provider != null)Text(uiText(UiR.string.ui_only_recognized_text_is_sent_to_1_s_camera_images_stay_on_your_ph_0beab, provider.label),style=MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth().height(280.dp).background(Color.Black),contentAlignment=Alignment.Center) {
            if(permission && frozen==null && !importing && sharedImage==null)CameraPreview(Modifier.fillMaxSize(),state.live && !state.loading,
                onFrame={controller.offer(it)},onCaptureReady={capture=it},onPhoto={photo(it)},onError=controller::error)
            else if(!permission && frozen==null)Button(onClick={permissionRequest.launch(Manifest.permission.CAMERA)}){Text(uiText(UiR.string.ui_open_camera_672b0))}
            frozen?.let {Image(it.asImageBitmap(),uiText(UiR.string.ui_captured_image_7fc88),Modifier.fillMaxSize().background(Color.Black),contentScale=ContentScale.Fit)}
            if(state.lines.isNotEmpty() && (frozen!=null || state.live))Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                val scale=minOf(size.width/state.width,size.height/state.height)
                val dx=(size.width-state.width*scale)/2;val dy=(size.height-state.height*scale)/2
                state.lines.forEach {line -> drawRect(Color(0xFFFFD54F),Offset(dx+line.x*scale,dy+line.y*scale),Size(line.width*scale,line.height*scale),style=Stroke(2.dp.toPx()))}
            }
        }
        if(!ready) {
            Text(uiText(UiR.string.ui_install_the_1_s_model_files_to_begin_ocr_works_offline_after_inst_3b7de, uiText.label(profile)))
            OutlinedButton(onClick={settings=true}){Text(uiText(UiR.string.ui_get_ocr_models_245d8))}
        }
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            if(frozen==null)Button(onClick={capture?.invoke()},modifier=Modifier.heightIn(min=56.dp),enabled=ready&&permission&&capture!=null&&!state.loading&&!state.closing&&!importing){Text(if(translate) uiText(UiR.string.ui_capture_translate_a1034) else uiText(UiR.string.ui_capture_read_bc5ec))}
            if(frozen==null)OutlinedButton(onClick={imagePicker.launch(arrayOf("image/*"))},modifier=Modifier.heightIn(min=56.dp),enabled=ready&&!state.loading&&!state.closing&&!importing){Text(if(importing)uiText(UiR.string.ui_opening_b1b85) else uiText(UiR.string.ui_choose_photo_4a7ed))}
            if(frozen==null && profile.live)TextButton(onClick={begin(true)},enabled=ready&&permission&&!state.running&&!importing&&capture!=null){Text(uiText(UiR.string.ui_start_live_3d3ad))}

            if(state.running)OutlinedButton(onClick={controller.stop()},enabled=!state.closing){Text(if(state.closing)uiText(UiR.string.ui_stopping_827c9) else uiText(UiR.string.ui_stop_9e253))}
            if(frozen!=null)OutlinedButton(onClick={controller.stop();selectionImage=frozen},enabled=!state.loading&&!state.closing){Text(uiText(UiR.string.ui_draw_text_area_22b57))}
            if(frozen!=null)OutlinedButton(onClick={frozen?.let(::photo)},enabled=!state.loading&&!state.closing){Text(if(translate) uiText(UiR.string.ui_translate_again_02b28) else uiText(UiR.string.ui_read_again_c6c84))}
            if(frozen!=null)TextButton(onClick={controller.stop();frozen=null}){Text(uiText(UiR.string.ui_retake_16457))}
        }
        FeatureAction(uiText(UiR.string.ui_camera_settings_c1947), Icons.Default.Tune, {settings=true}, detail=if(translate)providerLabel else uiText(UiR.string.ui_original_text_only_7381b))
        if(state.loading || state.translating || state.closing)LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(when{state.closing->uiText(UiR.string.ui_releasing_models_53a44);state.loading->uiText(UiR.string.ui_loading_1_s_aba14, uiText.label(profile));state.translating->uiText(UiR.string.ui_translating_settled_text_06e2b);state.live->uiText(UiR.string.ui_live_ocr_1_s_ms_hold_steady_c9ca4, state.ocrMs);state.running->uiText(UiR.string.ui_captured_ocr_1_s_ms_faf13, state.ocrMs);else->uiText(UiR.string.ui_ready_camera_opens_only_on_this_page_ae38b)},style=MaterialTheme.typography.bodySmall)
        (voiceError ?: state.error)?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        if(state.running && !state.loading && state.text.isBlank())Text(uiText(UiR.string.ui_no_readable_text_yet_hold_the_phone_level_move_closer_or_capture_8a5e9))
        if(speaking)OutlinedButton(onClick=voice::stop){Text(uiText(UiR.string.ui_stop_speech_3f0d2))}
        if(state.text.isNotBlank()) {
            Text(uiText(UiR.string.ui_original_c0a80),style=MaterialTheme.typography.titleSmall)
            SelectionContainer{Text(state.text)}
            if(state.translation.isNotBlank()){Text(uiText(UiR.string.ui_translation_ac26a),style=MaterialTheme.typography.titleSmall);SelectionContainer{Text(state.translation,style=MaterialTheme.typography.titleLarge)}}
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                com.sal7one.transiber.voice.ReadAloudButtons(uiText(UiR.string.ui_original_c0a80),onPlay={system->voiceError=null;voice.speak(state.text,source,if(system)com.sal7one.transiber.voice.VoicePlaybackMode.SYSTEM else com.sal7one.transiber.voice.VoicePlaybackMode.CUSTOM)},onSetup=onVoices)
                if(state.translation.isNotBlank())com.sal7one.transiber.voice.ReadAloudButtons(uiText(UiR.string.ui_translation_ac26a),onPlay={system->voiceError=null;voice.speak(state.translation,target,if(system)com.sal7one.transiber.voice.VoicePlaybackMode.SYSTEM else com.sal7one.transiber.voice.VoicePlaybackMode.CUSTOM)},onSetup=onVoices)
                TextButton(onClick={(context.getSystemService(ClipboardManager::class.java)).setPrimaryClip(ClipData.newPlainText(uiText(UiR.string.ui_original_text_48909),state.text))}){Text(uiText(UiR.string.ui_copy_original_ae008))}
                if(state.translation.isNotBlank())TextButton(onClick={context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(uiText(UiR.string.ui_translation_ac26a),state.translation))}){Text(uiText(UiR.string.ui_copy_translation_ef0c0))}
            }
        }
        FeatureAction(uiText(UiR.string.ui_screen_manga_6c45b), Icons.Default.AutoStories, {

                    controller.stop()
                    scope.launch {
                        controller.awaitStopped()
                        onReading()
                    }

        }, detail=uiText(UiR.string.ui_translate_in_another_app_9f00d))
    }
    selectionImage?.let { image -> DrawOcrRegion(image,onDismiss={selectionImage=null},onConfirm={crop->
        selectionImage=null
        scope.launch {controller.stop();controller.awaitStopped();begin(false,crop)}
    }) }
    if(settings)FeatureOptionsSheet(uiText(UiR.string.ui_camera_reading_settings_a67f8), {settings=false}, settingsScroll) {
            OutlinedButton(onClick={voice.stop();settings=false;onVoices()}){Text(uiText(UiR.string.ui_voices_read_aloud_64e95))}
            TranslatorChooser(providerId,localModelId,uiText(UiR.string.ui_used_by_camera_and_the_screen_reading_overlay_ef332),source,target,
                enabled=!state.closing, onModels={settings=false;onModels()}, onSelect={provider,model->scope.launch {
                    try { controller.stop();controller.invalidate();voice.stop();selectionStore.update {it.copy(providerId=provider,localModelId=model)} }
                    catch(e: kotlinx.coroutines.CancellationException){throw e}
                catch(e: Exception){controller.error(e)}
                }})
            Text(uiText(UiR.string.ui_changing_settings_stops_recognition_your_captured_photo_stays_ava_a5b31),style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            SettingsOcrLocalUi(onDownloads={settings=false;onDownloads()},selected=profileId,onSelect={changeSelection(selection.withProfile(it));settings=false})
    }
}

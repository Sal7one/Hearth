package com.sal7one.transiber.reading

import com.sal7one.transiber.R as UiR
import com.sal7one.transiber.i18n.*

import com.sal7one.transiber.ui.theme.FeatureBackdrop
import com.sal7one.transiber.R
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.sal7one.transiber.caption.CaptionCaptureService
import com.sal7one.transiber.ocr.*
import com.sal7one.transiber.settings.SettingsOcrLocalUi
import com.sal7one.transiber.ocr.OcrModels
import com.sal7one.transiber.ui.theme.FFmpegStudioTheme
import com.sal7one.transiber.ui.theme.rememberThemeMode
import java.io.File
import kotlinx.coroutines.launch
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.translation.*
import com.sal7one.transiber.byok.ByokPolicy

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.AutoStories
import com.sal7one.transiber.ui.components.FeatureAction
import com.sal7one.transiber.ui.components.FeatureOptionsSheet

class ReadingStartActivity : AppCompatActivity() {
    private var setupRevision by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); setupRevision++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FFmpegStudioTheme(themeMode=rememberThemeMode()) { Screen() } }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun Screen() {
    val uiText = rememberUiText()

        val currentSetup = setupRevision
        val prefs=remember {getSharedPreferences("reading-overlay",0)}
        val (selection,selectionStore)=rememberOcrPreferences()
        val scope=rememberCoroutineScope()
        var options by rememberSaveable {mutableStateOf(false)}
        val optionsScroll=rememberScrollState()
        val config by remember {CaptionConfigStore.config(this)}.collectAsState(initial=CaptionOverlayConfig())
        val revision by ConversationTranslationSettings.revision.collectAsState()
        val provider=selection.providerId
        val cloud=remember(provider,revision,currentSetup) {ConversationTranslationSettings.provider(provider)?.let {ConversationTranslationSettings.capabilities(this,it)}}
        val targets=ocrTranslationTargets(selection,config.localTranslationModelId,cloud,ByokPolicy.FEATURE_BYOK)
        val translator=ConversationTranslationSettings.provider(provider)?.label ?: TranslationOptions.label(config.localTranslationModelId)
        val profile=selection.profile
        var models by rememberSaveable {mutableStateOf(false)}
        val modelsScroll=rememberScrollState()
        val savedMode=remember {prefs.getString("mode","page")}
        var mode by rememberSaveable {mutableStateOf(ReadingTrigger.supportedMode(savedMode))}
        var settle by rememberSaveable {mutableFloatStateOf(prefs.getLong("settle",500).toFloat())}
        var scan by rememberSaveable {mutableFloatStateOf(prefs.getLong("scan",250).toFloat())}
        var error by remember {mutableStateOf<String?>(null)}
        val projection=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->
            if(result.resultCode==Activity.RESULT_OK && result.data!=null) {
                CaptionCaptureService.stop(this)
                androidx.core.content.ContextCompat.startForegroundService(this,Intent(this,ReadingOverlayService::class.java).putExtra("projection",result.data))
                finish()
            } else error=uiText(UiR.string.ui_screen_sharing_was_cancelled_tap_start_reading_when_ready_b7b72)
        }
        fun launch() {
            prefs.edit().putString("mode",mode).putLong("settle",settle.toLong()).putLong("scan",scan.toLong()).remove("distance").remove("bursts").remove("volume").apply()
            if(!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
                error=uiText(UiR.string.ui_allow_hearth_to_display_over_other_apps_return_here_then_tap_star_1c196)
                return
            }
            val manager=getSystemService(MediaProjectionManager::class.java)
            projection.launch(if(Build.VERSION.SDK_INT>=34)manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent())
        }
        val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {launch()}
        FeatureBackdrop(R.drawable.home_screen) {
        Surface(Modifier.fillMaxSize(),color=Color.Transparent) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick={finish()}){Text(uiText(UiR.string.ui_back_b52b3))}
                    Text(uiText(UiR.string.ui_screen_manga_6c45b),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    Image(painterResource(R.drawable.home_screen),null,Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(28.dp)),contentScale=ContentScale.Crop)
                    Text(uiText(UiR.string.ui_translate_as_you_read_7ee96),style=MaterialTheme.typography.headlineMedium)
                    Text(uiText(UiR.string.ui_open_your_reader_after_starting_translate_the_page_or_draw_around_c74fc))
                    Text(uiText(UiR.string.ui_translate_when_b5cd2),style=MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("manual" to uiText(UiR.string.ui_i_tap_96db1), "page" to uiText(UiR.string.ui_page_changes_25675)).forEach {(id,label)->
                            FilterChip(selected=mode==id,onClick={mode=id},label={Text(label)})
                        }
                    }
                    OcrLanguageControls(selection,targets,translator,{next->selectionStore.update {next};error=null},{models=true})
                    FeatureAction(uiText(UiR.string.ui_reading_settings_8195d), Icons.Default.Tune, {options=true},
                        detail=translator)
                    if(profile.engine=="manga")Text(uiText(UiR.string.ui_manga_ocr_needs_a_drawn_area_around_one_speech_bubble_214c9),style=MaterialTheme.typography.bodySmall)
                    Text(uiText(UiR.string.ui_images_stay_on_this_phone_cloud_translation_sends_recognized_text_1cbaa),style=MaterialTheme.typography.bodySmall)
                    Text(uiText(UiR.string.ui_starting_screen_translation_stops_live_audio_captions_17e29),style=MaterialTheme.typography.bodySmall)
                    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                }
                Surface(shadowElevation=8.dp) {
                    Button(onClick={
                        if(!OcrModels(File(filesDir,"ocr-models")).ready(profile)) {error=uiText(UiR.string.ui_install_1_s_to_read_this_language_70476, uiText.label(profile));models=true}
                        else if(selection.translate && selection.target !in targets) {error=uiText(UiR.string.ui_choose_a_supported_translation_destination_or_translator_24a0b);options=true}
                        else if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else launch()
                    },modifier=Modifier.fillMaxWidth().padding(20.dp).heightIn(min=56.dp)){Text(uiText(UiR.string.ui_start_reading_overlay_c2be1))}
                }
            }
        }
        }
        if(models)FeatureOptionsSheet(uiText(UiR.string.ui_ocr_reader_2de99),{models=false},modelsScroll) {
            SettingsOcrLocalUi(selected=selection.profileId,
                onSelect={id->selectionStore.update {it.withProfile(id)};error=null;models=false},
                onDownloads={startActivity(Intent(this@ReadingStartActivity,com.sal7one.transiber.MainActivity::class.java).putExtra("page",2).putExtra("returnToReading",true))})
        }
        if(options)FeatureOptionsSheet(uiText(UiR.string.ui_reading_settings_8195d), {options=false}, optionsScroll) {
            TranslatorChooser(provider,config.localTranslationModelId,"Used by Camera and the reading overlay.",selection.source,selection.target,
                onModels={getSharedPreferences("translation-browser",0).edit().putBoolean("open",true).apply();startActivity(Intent(this@ReadingStartActivity,com.sal7one.transiber.MainActivity::class.java).putExtra("page",1).putExtra("returnToReading",true))},
                onSelect={id,model->scope.launch {try {
                    CaptionConfigStore.update(this@ReadingStartActivity){it.copy(localTranslationModelId=model)}
                    selectionStore.update {it.copy(providerId=id)};error=null
                } catch(e: kotlinx.coroutines.CancellationException){throw e}
                catch(e: Exception){error=e.message ?: e.toString()} }})
            if(mode!="manual") {
                Text(uiText(UiR.string.ui_wait_after_movement_1_s_ms_25f21, settle.toInt()))
                Slider(value=settle,onValueChange={settle=it},modifier=Modifier.semantics {contentDescription=uiText(UiR.string.ui_wait_after_movement_milliseconds_ed502)},valueRange=300f..2000f,steps=16)
            }
            Text(uiText(UiR.string.ui_check_screen_every_1_s_ms_37c5a, scan.toInt()))
            Slider(value=scan,onValueChange={scan=it},modifier=Modifier.semantics {contentDescription=uiText(UiR.string.ui_screen_movement_check_interval_milliseconds_a06ad)},valueRange=200f..1000f,steps=15)
            Text(uiText(UiR.string.ui_faster_checks_use_more_battery_screen_images_are_not_stored_4e8d9),style=MaterialTheme.typography.bodySmall)
            Text(uiText(UiR.string.ui_scroll_with_the_lock_closed_tap_it_to_interact_with_translated_te_47c95),style=MaterialTheme.typography.bodySmall)
            Text(uiText(UiR.string.ui_uses_screen_sharing_no_accessibility_service_camera_or_microphone_e7d33),style=MaterialTheme.typography.bodySmall)
            if(savedMode=="distance" || savedMode=="scrolls")Text(uiText(UiR.string.ui_page_changes_replaces_the_old_scroll_shortcut_and_detects_visual_3ca76),style=MaterialTheme.typography.bodySmall)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}

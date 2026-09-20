package com.sal7one.transiber.reading

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
import androidx.activity.ComponentActivity
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
import com.sal7one.transiber.ocr.OcrCatalog
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

class ReadingStartActivity : ComponentActivity() {
    private var setupRevision by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); setupRevision++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FFmpegStudioTheme(themeMode=rememberThemeMode()) { Screen() } }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun Screen() {
        val currentSetup = setupRevision
        val prefs=remember {getSharedPreferences("reading-overlay",0)}
        val camera=remember {getSharedPreferences("camera-translate",0)}
        val scope=rememberCoroutineScope()
        var options by rememberSaveable {mutableStateOf(false)}
        val optionsScroll=rememberScrollState()
        val config by remember {CaptionConfigStore.config(this)}.collectAsState(initial=CaptionOverlayConfig())
        val revision by ConversationTranslationSettings.revision.collectAsState()
        var provider by remember {mutableStateOf(if(ByokPolicy.FEATURE_BYOK)camera.getString("provider","local")!! else "local")}
        LaunchedEffect(revision,currentSetup) {provider=if(ByokPolicy.FEATURE_BYOK)camera.getString("provider","local")!! else "local"}
        val profile=remember(currentSetup) {OcrCatalog.profile(camera.getString("profile","latin")!!)}
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
            } else error="Screen sharing was cancelled. Tap Start reading when ready."
        }
        fun launch() {
            prefs.edit().putString("mode",mode).putLong("settle",settle.toLong()).putLong("scan",scan.toLong()).remove("distance").remove("bursts").remove("volume").apply()
            if(!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
                error="Allow Hearth to display over other apps, return here, then tap Start reading."
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
                    TextButton(onClick={finish()}){Text("Back")}
                    Text("Screen & manga",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    Image(painterResource(R.drawable.home_screen),null,Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(28.dp)),contentScale=ContentScale.Crop)
                    Text("Translate as you read",style=MaterialTheme.typography.headlineMedium)
                    Text("Open your reader after starting. Translate the page or draw around one bubble.")
                    Text("Translate when",style=MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("manual" to "I tap", "page" to "Page changes").forEach {(id,label)->
                            FilterChip(selected=mode==id,onClick={mode=id},label={Text(label)})
                        }
                    }
                    FeatureAction("Reading settings", Icons.Default.Tune, {options=true},
                        detail="${profile.label} · ${camera.getString("source","en")} → ${camera.getString("target","ar")}")
                    if(profile.engine=="manga")Text("Manga OCR needs a drawn area around one speech bubble.",style=MaterialTheme.typography.bodySmall)
                    Text("Images stay on this phone. Cloud translation sends recognized text to your selected provider.",style=MaterialTheme.typography.bodySmall)
                    Text("Starting screen translation stops live audio captions.",style=MaterialTheme.typography.bodySmall)
                    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                }
                Surface(shadowElevation=8.dp) {
                    Button(onClick={
                        if(!OcrModels(File(filesDir,"ocr-models")).ready(profile)) {error="Install ${profile.label} in Camera settings first.";options=true}
                        else if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else launch()
                    },modifier=Modifier.fillMaxWidth().padding(20.dp).heightIn(min=56.dp)){Text("Start reading overlay")}
                }
            }
        }
        }
        if(options)FeatureOptionsSheet("Reading settings", {options=false}, optionsScroll) {
            OutlinedButton(onClick={options=false;startActivity(Intent(this@ReadingStartActivity,com.sal7one.transiber.MainActivity::class.java).putExtra("page",10))},modifier=Modifier.fillMaxWidth()){Text("OCR model & languages")}
            TranslatorChooser(provider,config.localTranslationModelId,"Used by Camera and the reading overlay.",camera.getString("source","en"),camera.getString("target","ar"),
                onModels={startActivity(Intent(this@ReadingStartActivity,com.sal7one.transiber.MainActivity::class.java).putExtra("page",1))},
                onSelect={id,model->scope.launch {try {
                    CaptionConfigStore.update(this@ReadingStartActivity){it.copy(localTranslationModelId=model)}
                    camera.edit().putString("provider",id).apply();provider=id
                } catch(e: kotlinx.coroutines.CancellationException){throw e}
                catch(e: Exception){error=e.message ?: e.toString()} }})
            if(mode!="manual") {
                Text("Wait after movement: ${settle.toInt()} ms")
                Slider(value=settle,onValueChange={settle=it},modifier=Modifier.semantics {contentDescription="Wait after movement, milliseconds"},valueRange=300f..2000f,steps=16)
            }
            Text("Check screen every ${scan.toInt()} ms")
            Slider(value=scan,onValueChange={scan=it},modifier=Modifier.semantics {contentDescription="Screen movement check interval, milliseconds"},valueRange=200f..1000f,steps=15)
            Text("Faster checks use more battery. Screen images are not stored.",style=MaterialTheme.typography.bodySmall)
            Text("Scroll with the lock closed. Tap it to interact with translated text. The handle and notification offer Translate, Pause and Stop.",style=MaterialTheme.typography.bodySmall)
            Text("Uses screen sharing. No Accessibility service, camera or microphone permission is needed.",style=MaterialTheme.typography.bodySmall)
            if(savedMode=="distance" || savedMode=="scrolls")Text("Page changes replaces the old scroll shortcut and detects visual movement, not exact scroll distance.",style=MaterialTheme.typography.bodySmall)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}

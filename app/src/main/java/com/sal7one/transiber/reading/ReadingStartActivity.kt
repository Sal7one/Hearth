package com.sal7one.transiber.reading

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

class ReadingStartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FFmpegStudioTheme(themeMode=rememberThemeMode()) { Screen() } }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun Screen() {
        val prefs=remember {getSharedPreferences("reading-overlay",0)}
        val camera=remember {getSharedPreferences("camera-translate",0)}
        val scope=rememberCoroutineScope()
        val config by remember {CaptionConfigStore.config(this)}.collectAsState(initial=CaptionOverlayConfig())
        val revision by ConversationTranslationSettings.revision.collectAsState()
        var provider by remember {mutableStateOf(if(ByokPolicy.FEATURE_BYOK)camera.getString("provider","local")!! else "local")}
        LaunchedEffect(revision) {provider=if(ByokPolicy.FEATURE_BYOK)camera.getString("provider","local")!! else "local"}
        val profile=OcrCatalog.profile(camera.getString("profile","latin")!!)
        val savedMode=remember {prefs.getString("mode","page")}
        var mode by remember {mutableStateOf(ReadingTrigger.supportedMode(savedMode))}
        var settle by remember {mutableFloatStateOf(prefs.getLong("settle",500).toFloat())}
        var scan by remember {mutableFloatStateOf(prefs.getLong("scan",250).toFloat())}
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
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("Read other apps",style=MaterialTheme.typography.headlineMedium)
                Text("Manga, comics & books",style=MaterialTheme.typography.titleMedium)
                Text("Open your reader after starting. Tap Translate for a page, or Draw area for one bubble. Your images stay on this phone. Cloud translation sends only recognized text to your chosen provider.")
                Text("${profile.label} · ${camera.getString("source","en")} → ${camera.getString("target","ar")}")
                Text("Uses the OCR model, languages and translator selected on Camera. Starting this screen-reading session stops live audio captions.",style=MaterialTheme.typography.bodySmall)
                TranslatorChooser(provider,config.localTranslationModelId,"Used by Camera and the reading overlay.",camera.getString("source","en"),camera.getString("target","ar"),
                    onModels={startActivity(Intent(this@ReadingStartActivity,com.sal7one.transiber.MainActivity::class.java).putExtra("page",1))},
                    onSelect={id,model->scope.launch {try {
                        CaptionConfigStore.update(this@ReadingStartActivity){it.copy(localTranslationModelId=model)}
                        camera.edit().putString("provider",id).apply();provider=id
                    } catch(e: kotlinx.coroutines.CancellationException){throw e}
                    catch(e: Exception){error=e.message ?: e.toString()} }})
                if(profile.engine=="manga")Text("Manga OCR reads one selected bubble. Draw an area first; the same area is reused until you change it.")
                Text("Translations appear over text in your reader. Scroll through them with the lock closed. Tap the lock to interact with text boxes; the handle and notification always keep controls available.",style=MaterialTheme.typography.bodySmall)
                Text("Translate when",style=MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("manual" to "I tap", "page" to "Page changes").forEach {(id,label)->
                        FilterChip(selected=mode==id,onClick={mode=id},label={Text(label)})
                    }
                }
                if(mode!="manual") {
                    Text("Wait after movement: ${settle.toInt()} ms")
                    Slider(value=settle,onValueChange={settle=it},modifier=Modifier.semantics {contentDescription="Wait after movement, milliseconds"},valueRange=300f..2000f,steps=16)
                    Text("Visual movement clears old text positions. Only the latest settled view is translated; exact scroll events are not required.",style=MaterialTheme.typography.bodySmall)
                }
                Text("Check screen every ${scan.toInt()} ms")
                Slider(value=scan,onValueChange={scan=it},modifier=Modifier.semantics {contentDescription="Screen movement check interval, milliseconds"},valueRange=200f..1000f,steps=15)
                Text("Faster checks respond sooner and use more battery. No screen images are stored.",style=MaterialTheme.typography.bodySmall)
                Text("Uses screen sharing only. No Accessibility service or volume-key access is needed.",style=MaterialTheme.typography.bodySmall)
                if(savedMode=="distance" || savedMode=="scrolls") {
                    Text("Your previous scroll shortcut has been replaced by Page changes. It detects visual changes after scrolling; it does not measure scroll distance. You can choose I tap instead.",style=MaterialTheme.typography.bodySmall)
                }
                error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                Button(onClick={
                    if(!OcrModels(File(filesDir,"ocr-models")).ready(profile))error="Install ${profile.label} in Camera settings first."
                    else if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else launch()
                },modifier=Modifier.fillMaxWidth()){Text("Start reading overlay")}
                Text("A small movable handle always stays touchable. The notification also offers Translate, Pause and Stop. No camera or microphone permission is needed.",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={finish()}){Text("Back to Camera")}
            }
        }
    }
}

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

class ReadingStartActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FFmpegStudioTheme(themeMode=rememberThemeMode()) { Screen() } }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun Screen() {
        val prefs=remember {getSharedPreferences("reading-overlay",0)}
        val camera=remember {getSharedPreferences("camera-translate",0)}
        val profile=OcrCatalog.profile(camera.getString("profile","latin")!!)
        var mode by remember {mutableStateOf(prefs.getString("mode","manual")!!)}
        var settle by remember {mutableFloatStateOf(prefs.getLong("settle",700).toFloat())}
        var distance by remember {mutableFloatStateOf(prefs.getFloat("distance",.75f))}
        var bursts by remember {mutableFloatStateOf(prefs.getInt("bursts",2).toFloat())}
        var volume by remember {mutableStateOf(prefs.getBoolean("volume",false))}
        var error by remember {mutableStateOf<String?>(null)}
        val projection=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {result->
            if(result.resultCode==Activity.RESULT_OK && result.data!=null) {
                CaptionCaptureService.stop(this)
                androidx.core.content.ContextCompat.startForegroundService(this,Intent(this,ReadingOverlayService::class.java).putExtra("projection",result.data))
                finish()
            } else error="Screen sharing was cancelled. Tap Start reading when ready."
        }
        fun launch() {
            prefs.edit().putString("mode",mode).putLong("settle",settle.toLong()).putFloat("distance",distance).putInt("bursts",bursts.toInt()).putBoolean("volume",volume).apply()
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
                if(profile.engine=="manga")Text("Manga OCR reads one selected bubble. Draw an area first; the same area is reused until you change it.")
                Text("Translate when",style=MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("manual" to "I tap", "page" to "Page changes", "distance" to "Scroll distance", "scrolls" to "After scrolls").forEach {(id,label)->
                        FilterChip(selected=mode==id,onClick={mode=id},label={Text(label)})
                    }
                }
                if(mode!="manual") {
                    Text("Wait after movement: ${settle.toInt()} ms")
                    Slider(value=settle,onValueChange={settle=it},modifier=Modifier.semantics {contentDescription="Wait after movement, milliseconds"},valueRange=300f..2000f,steps=16)
                    Text("Automatic checks run about once per second; only the latest settled view is translated.",style=MaterialTheme.typography.bodySmall)
                }
                if(mode=="distance") {Text("Scroll ${"%.2f".format(distance)} screen heights");Slider(distance,{distance=it},modifier=Modifier.semantics {contentDescription="Scroll distance in screen heights"},valueRange=.25f..3f,steps=10)}
                if(mode=="scrolls") {Text("After ${bursts.toInt()} scroll bursts");Slider(bursts,{bursts=it},modifier=Modifier.semantics {contentDescription="Number of scroll bursts"},valueRange=1f..5f,steps=3)}
                Row {Switch(volume,{volume=it},modifier=Modifier.semantics {contentDescription="Volume Up translates"});Text("Volume Up translates",Modifier.padding(12.dp))}
                if(mode=="distance" || mode=="scrolls" || volume) {
                    Text("Optional reading shortcuts observe scrolling and Volume Up while the overlay is active. They do not read accessibility text or control the reader. Some readers do not report scroll distance; use Page changes or I tap in those apps.")
                    OutlinedButton(onClick={startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("Enable Hearth reading shortcuts")}
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

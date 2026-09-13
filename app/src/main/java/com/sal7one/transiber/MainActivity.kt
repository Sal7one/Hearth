package com.sal7one.transiber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp
import com.sal7one.transiber.caption.*
import com.sal7one.transiber.byok.*
import com.sal7one.transiber.ui.theme.*
import com.sal7one.transiber.benchmark.LocalBenchmarkScreen
import com.sal7one.transiber.conversation.ConversationScreen
import com.sal7one.transiber.translation.ConversationTranslationSetup
import com.sal7one.transiber.models.ModelsScreen
import com.sal7one.transiber.downloads.DownloadsScreen

class MainActivity : ComponentActivity() {
 private val navigation=kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
 override fun onNewIntent(intent: android.content.Intent) {
  super.onNewIntent(intent);setIntent(intent)
  if(intent.hasExtra("page"))navigation.value=intent.getIntExtra("page",0).coerceIn(0,12)
 }
 @OptIn(ExperimentalMaterial3Api::class)
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val nativeFailure = try { com.sal7one.common_jni.CommonJni.init(applicationContext); null }
   catch(e: Exception) { e.message ?: e.toString() }
  enableEdgeToEdge()
  setContent {
   HearthTheme {
    val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
    SideEffect {
     WindowCompat.getInsetsController(window, window.decorView).apply {
      isAppearanceLightStatusBars = lightBars
      isAppearanceLightNavigationBars = lightBars
     }
    }
    // Keep the existing bubble's page=1/2 links working after removing tabs.
    var page by rememberSaveable { mutableIntStateOf(intent.getIntExtra("page", 0).coerceIn(0, 12)) }
    var backStack by rememberSaveable {mutableStateOf(listOf<Int>())}
    val screenState=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun go(next: Int){if(next!=page){backStack=if(next==0)emptyList() else (backStack+page).takeLast(12);page=next}}
    val requestedPage by navigation.collectAsState()
    LaunchedEffect(requestedPage){requestedPage?.let {backStack=emptyList();page=it;navigation.value=null}}
    var faceLayout by rememberSaveable { mutableStateOf(false) }
    fun back() {if(backStack.isNotEmpty()){page=backStack.last();backStack=backStack.dropLast(1)}else page=if(page in setOf(3,7,8,10,11))0 else 3}
    BackHandler(enabled = page != 0) { back() }
    Scaffold(topBar = {
     TopAppBar(title = { Text(when(page) { 0 -> "Hearth"; 1 -> "Models"; 2 -> "Downloads"; 3 -> "Setup"; 4 -> "Cloud connection"; 5 -> "Advanced setup"; 7 -> if (faceLayout) "Face to face" else "Conversation"; 8 -> "Local benchmark"; 9 -> "Translation connections"; 10 -> "Camera translate"; 11 -> "Type to translate"; 12 -> "Voices & read aloud"; else -> "Help" }, style = MaterialTheme.typography.titleMedium) },
      navigationIcon = { if (page != 0) IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      actions = { if (page == 0) TextButton(onClick = { go(3) }) { Text("Setup") }
        else TextButton(onClick = { go(0) }) { Text("Done") } })
    }) { padding ->
     Column(Modifier.fillMaxSize().padding(padding)) {
      nativeFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
      Box(Modifier.weight(1f)) {
       screenState.SaveableStateProvider(page) {
       when(page) {
        0 -> CaptionHome(onModels = { go(1) }, onCloud = { go(4) }, onConversation = { faceLayout = false; go(7) }, onBenchmark = { go(8) }, onFaceToFace = { faceLayout = true; go(7) }, onCamera = { go(10) }, onTextTranslate = {go(11)})
        1 -> ModelsScreen(onCloud = { go(4) }, onDownloads = { go(2) },onVoices={go(12)})
        2 -> DownloadsScreen(onBrowseModels = { go(1) })
        3 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Set up once. Start from the home screen.", style = MaterialTheme.typography.bodyMedium)
          SetupLink("Models", "Choose speech, translation and camera models") { go(1) }
          SetupLink("Downloads", "Install downloaded models or download a file") { go(2) }
          if (ByokPolicy.FEATURE_BYOK) SetupLink("Cloud connection", "Your provider, saved key and cloud options") { go(4) }
          SetupLink("Voices & read aloud", "Android, Supertonic and self-hosted speech") {go(12)}
          AppearanceSettings()
          SetupLink("Local benchmark", "Compare installed models using the same audio and text") { go(8) }
          TextButton(onClick = { go(5) }) { Text("Advanced setup") }
          TextButton(onClick = { go(6) }) { Text("Help & diagnostics") }
        }
        4 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
          if (ByokPolicy.FEATURE_BYOK) {
            SetupLink("Translation connections", "Google, Microsoft, DeepL or LibreTranslate for text translation") { go(9) }
            Spacer(Modifier.height(16.dp))
            ByokKeySection()
          }
          else Text("Cloud connections are unavailable in the offline build.")
        }
        5 -> CaptionScreen(onBrowseModels = { go(1) })
        7 -> ConversationScreen(onModels = { go(1) }, onCloud = { go(4) }, onLayoutChanged = { faceLayout = it }, initialFaceToFace = faceLayout, onVoices={go(12)})
        10 -> com.sal7one.transiber.ocr.CameraTranslateScreen(onModels = { go(1) }, onConnections = { go(9) }, onDownloads = { go(2) },onVoices={go(12)})
        11 -> com.sal7one.transiber.translation.TypedTranslateScreen(onModels={go(1)},onConnections={go(9)},onVoices={go(12)})
        12 -> com.sal7one.transiber.voice.VoiceSetup(onDownloads={go(2)})
        8 -> LocalBenchmarkScreen(onModels = { go(1) })
        9 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
          ConversationTranslationSetup(onModels = { go(1) })
        }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text("Choose audio and captions or translation, then Start. Android may ask for audio access or screen-sharing consent.")
          Text("Use the bubble settings for appearance and language controls. The notification can pause, recover or stop captions.")
          Text("Some apps block device-audio capture. Microphone listens to nearby sound instead.")
          CaptionDiagnosticActions()
        }
       }
       }
      }
     }
    }
   }
  }
 }
}

@Composable
private fun SetupLink(title: String, subtitle: String, onClick: () -> Unit) {
 OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
   Text(title, style = MaterialTheme.typography.titleMedium)
   Text(subtitle, style = MaterialTheme.typography.bodySmall)
  }
 }
}

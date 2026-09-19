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
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.sal7one.transiber.ui.AppNavigation
import com.sal7one.transiber.ui.MainTab
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
 private var sharedImage by mutableStateOf<android.net.Uri?>(null)
 private var sharedText by mutableStateOf<String?>(null)
 private fun receiveShare(intent: android.content.Intent) {
  if(intent.action!=android.content.Intent.ACTION_SEND)return
  if(intent.type?.startsWith("image/")==true) {
   @Suppress("DEPRECATION") val uri=intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
   if(uri?.scheme=="content") {sharedImage=uri;intent.putExtra("page",10)}
  } else if(intent.type=="text/plain") {
   sharedText=intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString()
   intent.putExtra("page",11)
  }
 }
 private val navigation=kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
 override fun onNewIntent(intent: android.content.Intent) {
  super.onNewIntent(intent);setIntent(intent);receiveShare(intent)
  if(intent.hasExtra("page"))navigation.value=intent.getIntExtra("page",0).coerceIn(0,12)
 }
 @OptIn(ExperimentalMaterial3Api::class)
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  if(savedInstanceState==null)receiveShare(intent)
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
    var route by rememberSaveable(stateSaver = listSaver(
     save = { state: AppNavigation -> state.save() }, restore = { AppNavigation.restore(it) }
    )) { mutableStateOf(AppNavigation.initial(intent.getIntExtra("page", 0))) }
    val page = route.page
    val focus = LocalFocusManager.current
    val screenState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    fun go(next: Int) { focus.clearFocus(); route = route.open(next) }
    val requestedPage by navigation.collectAsState()
    LaunchedEffect(requestedPage) {
     requestedPage?.let { focus.clearFocus(); route = AppNavigation.initial(it); navigation.value = null }
    }
    var faceLayout by rememberSaveable { mutableStateOf(false) }
    fun back() { focus.clearFocus(); route = route.back() }
    BackHandler(enabled = route.canGoBack) { back() }
    Scaffold(topBar = {
     TopAppBar(title = { Text(when(page) {
      0 -> "Live captions"; 1 -> "Models"; 2 -> "Downloads"; 3 -> "Settings"
      4 -> "Cloud speech"; 5 -> "Advanced captions"
      7 -> if (faceLayout) "Face to face" else "Conversation"
      8 -> "Local benchmark"; 9 -> "Translation connections"; 10 -> "Camera & OCR"
      11 -> "Type to translate"; 12 -> "Voices & read aloud"; else -> "Help"
     }, style = MaterialTheme.typography.titleMedium) },
      navigationIcon = { if (!route.isRoot) IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      actions = { if (!route.isRoot) TextButton(onClick = { focus.clearFocus(); route = route.toRoot() }) { Text("Done") } })
    }, bottomBar = {
     NavigationBar {
      MainTab.entries.forEach { tab ->
       NavigationBarItem(
        selected = route.tab == tab,
        onClick = { focus.clearFocus(); route = route.select(tab) },
        icon = { Icon(when(tab) {
         MainTab.CAPTIONS -> Icons.Default.ClosedCaption
         MainTab.TALK -> Icons.Default.Forum
         MainTab.TRANSLATE -> Icons.Default.Translate
         MainTab.CAMERA -> Icons.Default.CameraAlt
         MainTab.SETTINGS -> Icons.Default.Settings
        }, contentDescription = null) },
        label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.semantics { contentDescription = tab.title },
       )
      }
     }
    }) { padding ->
     Column(Modifier.fillMaxSize().padding(padding)) {
      nativeFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
      Box(Modifier.weight(1f)) {
       screenState.SaveableStateProvider(page) {
       when(page) {
        0 -> CaptionHome(onModels = { go(1) }, onCloud = { go(4) })
        1 -> ModelsScreen(onCloud = { go(4) }, onDownloads = { go(2) },onVoices={go(12)})
        2 -> DownloadsScreen(onBrowseModels = { go(1) })
        3 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("Models, connections and your preferences.", style = MaterialTheme.typography.bodyMedium)
          SetupLink("Models", "Choose speech, translation and camera models") { go(1) }
          SetupLink("Downloads", "Install downloaded models or download a file") { go(2) }
          if (ByokPolicy.FEATURE_BYOK) {
            SetupLink("Cloud speech", "Speech provider, saved key and streaming options") { go(4) }
            SetupLink("Cloud translation", "Google, Microsoft, DeepL or LibreTranslate") { go(9) }
          }
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
        10 -> com.sal7one.transiber.ocr.CameraTranslateScreen(onModels = { go(1) }, onConnections = { go(9) }, onDownloads = { go(2) },onVoices={go(12)},sharedImage=sharedImage,onShareConsumed={sharedImage=null})
        11 -> com.sal7one.transiber.translation.TypedTranslateScreen(onModels={go(1)},onConnections={go(9)},onVoices={go(12)},sharedText=sharedText,onShareConsumed={sharedText=null})
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

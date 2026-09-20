package com.sal7one.transiber

import com.sal7one.transiber.i18n.*
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.material.icons.filled.Home
import com.sal7one.transiber.home.HomeScreen
import com.sal7one.transiber.setup.EasySetupStep
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
import com.sal7one.transiber.settings.*
import com.sal7one.transiber.models.ModelsScreen
import com.sal7one.transiber.downloads.DownloadsScreen

class MainActivity : AppCompatActivity() {
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
  if(intent.hasExtra("page"))navigation.value=intent.getIntExtra("page",0).coerceIn(0,15)
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
    val uiText = rememberUiText()
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
    val layout = rememberNavigationLayout()
    val simple = layout == NavigationLayout.SIMPLE
    var showHome by rememberSaveable { mutableStateOf(!intent.hasExtra("page")) }
    var homeDestination by rememberSaveable { mutableStateOf<Int?>(null) }
    var homeEntry by rememberSaveable { mutableIntStateOf(0) }
    var quickSettings by rememberSaveable { mutableStateOf(false) }
    val atHome = simple && showHome
    val page = route.page
    val focus = LocalFocusManager.current
    val screenState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var setupStep by rememberSaveable { mutableStateOf(EasySetupStep.CHOICE) }
    fun go(next: Int) {
     focus.clearFocus()
     if (next == 15) {
      setupStep = EasySetupStep.CHOICE
      if (atHome || page != 15) screenState.removeState(15)
     }
     if (atHome) {
      route = AppNavigation.initial(next)
      homeDestination = next
     } else route = route.open(next)
     showHome = false
    }
    var speechLocation by rememberSaveable { mutableStateOf(if (intent.getIntExtra("page", 0) == 4) SettingsLocation.CLOUD else SettingsLocation.LOCAL) }
    var translationLocation by rememberSaveable { mutableStateOf(SettingsLocation.LOCAL) }
    var voiceLocation by rememberSaveable { mutableStateOf(SettingsLocation.LOCAL) }
    var speechEntry by rememberSaveable { mutableIntStateOf(0) }
    var translationEntry by rememberSaveable { mutableIntStateOf(0) }
    var voiceEntry by rememberSaveable { mutableIntStateOf(0) }
    val requestedPage by navigation.collectAsState()
    LaunchedEffect(requestedPage) {
     requestedPage?.let { if (it == 15) setupStep = EasySetupStep.CHOICE; if (it == 4) { speechLocation = SettingsLocation.CLOUD; speechEntry++ }; focus.clearFocus(); route = AppNavigation.initial(it); homeDestination = null; showHome = false; navigation.value = null }
    }
    var faceLayout by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(page, atHome, faceLayout) {
     if (!atHome) com.sal7one.transiber.home.HomeService.forPage(page, faceLayout)?.let {
      com.sal7one.transiber.home.HomeServiceStore.remember(this@MainActivity, it)
     }
    }
    var modelsSection by rememberSaveable { mutableStateOf("Speech") }
    fun localModels(section: String) { modelsSection = section; go(1) }
    fun speechSettings(location: SettingsLocation) { speechLocation = location; speechEntry++; go(4) }
    fun translationSettings(location: SettingsLocation) { translationLocation = location; translationEntry++; go(9) }
    fun voiceSettings(location: SettingsLocation? = null) {
     voiceLocation = location ?: if (com.sal7one.transiber.voice.VoiceSettings.choice(this@MainActivity).backend == "remote") SettingsLocation.CLOUD else SettingsLocation.LOCAL
     voiceEntry++; go(12)
    }
    fun home() { focus.clearFocus(); homeDestination = null; showHome = true }
    fun back() {
     focus.clearFocus()
     if (page == 15 && setupStep.back() != null) {
      setupStep = requireNotNull(setupStep.back())
      return
     }
     if (intent.getBooleanExtra("returnToReading", false) && (route.isRoot || page == intent.getIntExtra("page", 0))) finish()
     else if (simple && (route.isRoot || page == homeDestination)) home() else route = route.back()
    }
    fun settingsFeature(location: SettingsLocation, feature: SettingsFeature) {
     when (feature) {
      SettingsFeature.SPEECH -> speechSettings(location)
      SettingsFeature.TRANSLATION -> translationSettings(location)
      SettingsFeature.VOICES -> voiceSettings(location)
      SettingsFeature.CAMERA -> localModels("Camera")
     }
    }
    fun reading() {
     com.sal7one.transiber.home.HomeServiceStore.remember(this@MainActivity, com.sal7one.transiber.home.HomeService.SCREEN)
     if (simple) { homeEntry++; home() }
     startActivity(android.content.Intent(this@MainActivity, com.sal7one.transiber.reading.ReadingStartActivity::class.java)) }
    BackHandler(enabled = !atHome && (simple || route.canGoBack)) { back() }
    if (quickSettings) SettingsQuickSheet(
     onDismiss = { quickSettings = false },
     onFeature = { location, feature -> quickSettings = false; settingsFeature(location, feature) },
     onPage = { quickSettings = false; go(it) },
     onReading = { quickSettings = false; reading() },
    )
    FeatureBackdrop(artwork = when { atHome -> null; page == 0 -> R.drawable.home_captions; page == 7 -> R.drawable.home_conversation; page == 10 -> R.drawable.home_camera; page == 11 -> R.drawable.home_text; else -> R.drawable.home_face },
     tint = androidx.compose.ui.graphics.Color(when(page) { 7 -> 0xFFEAA077; 10 -> 0xFFEDA84C; 11 -> 0xFF9470F5; else -> 0xFF448EFF })) {
    Scaffold(containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground, topBar = {
     TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .55f)), title = { Text(if (atHome) "Hearth" else when(page) {
      0 -> uiText(R.string.label_maintab_captions_title); 1 -> uiText(R.string.ui_models_f3798); 2 -> uiText(R.string.nav_downloads); 3 -> uiText(R.string.label_maintab_settings_title)
      4 -> uiText(R.string.nav_speech_settings); 5 -> uiText(R.string.nav_advanced_captions)
      7 -> if (faceLayout) uiText(R.string.label_homeservice_face_title) else uiText(R.string.label_homeservice_conversation_title)
      8 -> uiText(R.string.ui_local_benchmark_3acfe); 9 -> uiText(R.string.label_settingsfeature_translation_label); 10 -> uiText(R.string.label_maintab_camera_title)
      11 -> uiText(R.string.label_maintab_translate_title); 12 -> uiText(R.string.label_settingsfeature_voices_label); 13 -> uiText(R.string.ui_appearance_navigation_433af); 14 -> uiText(R.string.ui_phone_shortcuts_b969d); 15 -> uiText(R.string.ui_easy_setup_35fb5); else -> uiText(R.string.nav_help)
     }, style = MaterialTheme.typography.titleMedium) },
      navigationIcon = { if (!atHome && (simple || !route.isRoot)) IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText(R.string.ui_back_b52b3)) } },
      actions = {
       if (simple && !atHome) IconButton(onClick = { home() }) { Icon(Icons.Default.Home, uiText(R.string.nav_home)) }
       IconButton(onClick = { quickSettings = true }) { Icon(Icons.Default.Settings, uiText(R.string.nav_quick_settings)) }
      })
    }, bottomBar = {
     if (!simple) NavigationBar {
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
        label = { Text(uiText.label(tab), style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.semantics { contentDescription = uiText.title(tab) },
       )
      }
     }
    }) { padding ->
     Column(Modifier.fillMaxSize().padding(padding)) {
      nativeFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
      Box(Modifier.weight(1f)) {
       screenState.SaveableStateProvider(if (atHome) "simple-home" else page) {
       if (atHome) HomeScreen(
        entryRevision = homeEntry,
        onSetup = { go(15) },
        onCaptions = { go(0) },
        onTalk = { face -> faceLayout = face; go(7) },
        onTranslate = { go(11) },
        onCamera = { go(10) },
        onReading = { reading() },
       ) else when(page) {
        0 -> CaptionHome(onModels = { go(1) }, onCloud = { speechSettings(SettingsLocation.CLOUD) })
        1 -> ModelsScreen(onDownloads = { go(2) }, onVoices = { voiceSettings(SettingsLocation.LOCAL) }, initialSection = modelsSection)
        2 -> DownloadsScreen(onBrowseModels = { go(1) })
        3 -> SettingsScreen(
            onEasySetup = { go(15) },
            onAppearance = { go(13) }, onShortcuts = { go(14) },
            onFeature = ::settingsFeature,
            onDownloads = { go(2) }, onBenchmark = { go(8) }, onAdvanced = { go(5) }, onHelp = { go(6) },
        )
        15 -> com.sal7one.transiber.setup.EasySetupScreen(step=setupStep,onStep={setupStep=it},onCaptions={go(0)},onHome={home()},onSettings={go(3)},onDownloads={go(2)})
        13 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { AppearanceSettings() }
        14 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { SettingsShortcutsUi() }
        4 -> SettingsSpeechScreen(speechLocation, speechEntry)
        5 -> CaptionScreen(onBrowseModels = { go(1) })
        7 -> ConversationScreen(onModels = { go(1) }, onCloud = { speechSettings(SettingsLocation.CLOUD) }, onLayoutChanged = { faceLayout = it }, initialFaceToFace = faceLayout, onVoices={voiceSettings()})
        10 -> com.sal7one.transiber.ocr.CameraTranslateScreen(onModels = { localModels("Camera") }, onConnections = { translationSettings(SettingsLocation.CLOUD) }, onDownloads = { go(2) },onVoices={voiceSettings()},sharedImage=sharedImage,onShareConsumed={sharedImage=null},onReading={reading()})
        11 -> com.sal7one.transiber.translation.TypedTranslateScreen(onModels={localModels("Translation")},onConnections={translationSettings(SettingsLocation.CLOUD)},onVoices={voiceSettings()},sharedText=sharedText,onShareConsumed={sharedText=null})
        12 -> com.sal7one.transiber.voice.VoiceSetup(onDownloads={go(2)}, initialLocation=voiceLocation, entryRevision=voiceEntry)
        8 -> LocalBenchmarkScreen(onModels = { go(1) })
        9 -> com.sal7one.transiber.translation.TranslationHub(onModels = { localModels("Translation") }, initialLocation = translationLocation, entryRevision = translationEntry)
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(uiText(R.string.help_0))
          Text(uiText(R.string.help_1))
          Text(uiText(R.string.help_2))
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
}

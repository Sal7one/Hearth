package com.sal7one.transiber

import com.sal7one.transiber.i18n.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
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
import com.sal7one.transiber.setup.SetupModePicker
import com.sal7one.transiber.preferences.UserPreferences
import com.sal7one.transiber.preferences.StartupModelAudit
import com.sal7one.transiber.setup.ModelBindingRepairNotice
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.sal7one.transiber.ui.AppNavigation
import com.sal7one.transiber.ui.MainTab
import com.sal7one.transiber.ui.SettingsSheetReturn
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
 private var tvOverlayVisibility: AutoCloseable? = null
 override fun onStart() {
  super.onStart()
  if (TvControls.isTvDevice(this)) tvOverlayVisibility = com.sal7one.transiber.shortcuts.OverlaySetupVisibility.acquire()
 }
 override fun onStop() {
  tvOverlayVisibility?.close(); tvOverlayVisibility = null
  super.onStop()
 }
 override fun onDestroy() {
  tvOverlayVisibility?.close(); tvOverlayVisibility = null
  super.onDestroy()
 }

 private var sharedImage by mutableStateOf<android.net.Uri?>(null)
 private var sharedText by mutableStateOf<String?>(null)
 private var translateSharedTextImmediately by mutableStateOf(false)
 private fun receiveShare(intent: android.content.Intent) {
  if(intent.action==android.content.Intent.ACTION_PROCESS_TEXT) {
   sharedText=intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT)?.toString()
   translateSharedTextImmediately=true
   intent.putExtra("page",11)
  } else if(intent.action==android.content.Intent.ACTION_SEND && intent.type?.startsWith("image/")==true) {
   @Suppress("DEPRECATION") val uri=intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
   if(uri?.scheme=="content") {sharedImage=uri;intent.putExtra("page",10)}
  } else if(intent.action==android.content.Intent.ACTION_SEND && intent.type=="text/plain") {
   sharedText=intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString()
   translateSharedTextImmediately=false
   intent.putExtra("page",11)
  }
 }
 private val navigation=kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
 override fun onNewIntent(intent: android.content.Intent) {
  super.onNewIntent(intent);setIntent(intent);receiveShare(intent)
  if(intent.hasExtra("page"))navigation.value=intent.getIntExtra("page",0).coerceIn(0,16)
 }
 /**
  * Remote/TV keys: media play-pause toggles capture pause, media stop kills
  * captions and any spoken audio, and holding BACK stops everything — the
  * "hold to kill" gesture remotes have instead of swipe-away.
  */
 override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
  if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event != null) {
   event.startTracking(); return super.onKeyDown(keyCode, event)
  }
  com.sal7one.transiber.caption.TvControls.remoteKeyToAction(keyCode)?.takeIf {
   com.sal7one.transiber.caption.TvControls.isTvDevice(this) && com.sal7one.transiber.caption.CaptionCaptureService.running.value
  }?.let { action ->
   startService(android.content.Intent(this, com.sal7one.transiber.caption.CaptionCaptureService::class.java).setAction(action))
   return true
  }
  return super.onKeyDown(keyCode, event)
 }

 override fun onKeyLongPress(keyCode: Int, event: android.view.KeyEvent?): Boolean {
  if (keyCode == android.view.KeyEvent.KEYCODE_BACK && com.sal7one.transiber.caption.TvControls.isTvDevice(this)) {
   com.sal7one.transiber.caption.CaptionCaptureService.stopCaptions(this)
   return true
  }
  return super.onKeyLongPress(keyCode, event)
 }

 @OptIn(ExperimentalMaterial3Api::class)
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  if(savedInstanceState==null)receiveShare(intent)
  val nativeFailure = try { com.sal7one.common_jni.CommonJni.init(applicationContext); null }
   catch(e: Exception) { e.message ?: e.toString() }
  enableEdgeToEdge()
  setContent {
   var preferencesReady by remember { mutableStateOf(false) }
   var preferencesError by remember { mutableStateOf<String?>(null) }
   var initializationAttempt by remember { mutableIntStateOf(0) }
   LaunchedEffect(initializationAttempt) {
    preferencesError = null
    try {
     kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { UserPreferences.initialize(applicationContext) }
     preferencesReady = true
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { preferencesError = e.message ?: e.toString() }
   }
   if (!preferencesReady) {
    MaterialTheme {
     Surface(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
       Text(getString(if (preferencesError == null) R.string.setup_preferences_loading else R.string.setup_preferences_failed))
       preferencesError?.let { failure ->
        Text(failure, color = MaterialTheme.colorScheme.error)
        Button(onClick = { initializationAttempt++ }) { Text(getString(R.string.setup_preferences_retry)) }
       } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
      }
     }
    }
    return@setContent
   }
   HearthTheme {
    val uiText = rememberUiText()
    val preferencesFailure by UserPreferences.failures.collectAsState()
    var modelCheckError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
     try {
      val audit = StartupModelAudit.await(applicationContext, retry = true)
      modelCheckError = audit.modelError ?: audit.preferencesError
     }
     catch (e: kotlinx.coroutines.CancellationException) { throw e }
     catch (e: Exception) { modelCheckError = e.message ?: e.toString() }
    }
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
    val television = remember { TvControls.isTvDevice(this@MainActivity) }
    var fullInterface by rememberSaveable { mutableStateOf(false) }
    val tvUi = com.sal7one.transiber.ui.useTvInterface(television, fullInterface)
    val simple = tvUi || layout == NavigationLayout.SIMPLE
    var showHome by rememberSaveable { mutableStateOf(!intent.hasExtra("page")) }
    var homeDestination by rememberSaveable { mutableStateOf<Int?>(null) }
    var homeEntry by rememberSaveable { mutableIntStateOf(0) }
    var quickSettings by rememberSaveable { mutableStateOf(false) }
    var quickSettingsSession by rememberSaveable { mutableIntStateOf(0) }
    var settingsReturns by rememberSaveable(stateSaver = listSaver(
     save = { history: List<SettingsSheetReturn> -> SettingsSheetReturn.saveHistory(history) },
     restore = { SettingsSheetReturn.restoreHistory(it) },
    )) { mutableStateOf(emptyList<SettingsSheetReturn>()) }
    // Keep real pipeline data while a settings child is visible. Restoring a
    // scrollable loading-only sheet would clamp its saved position to the top.
    var settingsConfig by remember { mutableStateOf<CaptionOverlayConfig?>(null) }
    var settingsConfigError by remember { mutableStateOf<String?>(null) }
    var settingsConfigAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(settingsConfigAttempt) {
     try {
      CaptionConfigStore.config(this@MainActivity).collect { settingsConfig = it; settingsConfigError = null }
     } catch (e: kotlinx.coroutines.CancellationException) { throw e }
     catch (e: Exception) { settingsConfigError = e.message ?: e.toString() }
    }
    fun retrySettingsConfig() { settingsConfigError = null; settingsConfigAttempt++ }
    val atHome = simple && showHome
    val page = route.page
    val tvHome = tvUi && (atHome || page == 0)
    val focus = LocalFocusManager.current
    val screenState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var tvSetupFeature by rememberSaveable { mutableStateOf<com.sal7one.transiber.setup.TvSetupFeature?>(null) }
    var setupStep by rememberSaveable { mutableStateOf(EasySetupStep.CHOICE) }
    fun go(next: Int) {
     focus.clearFocus()
     if (next == 15) {
      setupStep = EasySetupStep.CHOICE
      if (atHome || page != 15) screenState.removeState(com.sal7one.transiber.ui.navigationStateKey(tvUi, false, 15))
     }
     if (atHome) {
      route = AppNavigation.initial(next)
      homeDestination = next
     } else route = route.open(next)
     showHome = false
    }
    var speechLocation by rememberSaveable { mutableStateOf(if (intent.getIntExtra("page", 0) == 4) SettingsLocation.CLOUD else SettingsLocation.LOCAL) }
    var translationLocation by rememberSaveable { mutableStateOf(SettingsLocation.LOCAL) }
    suspend fun captionVoiceLocation(): SettingsLocation = if (com.sal7one.transiber.caption.CaptionConfigStore.config(this@MainActivity).first().speakerChoice == com.sal7one.transiber.caption.CaptionSpeakerChoice.CLOUD) SettingsLocation.CLOUD else SettingsLocation.LOCAL
    var voiceLocation by rememberSaveable { mutableStateOf(SettingsLocation.LOCAL) }
    var speechEntry by rememberSaveable { mutableIntStateOf(0) }
    var translationEntry by rememberSaveable { mutableIntStateOf(0) }
    var voiceEntry by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { if (intent.getIntExtra("page", 0) == 12) { voiceLocation = captionVoiceLocation(); voiceEntry++ } }
    val requestedPage by navigation.collectAsState()
    LaunchedEffect(requestedPage) {
     requestedPage?.let { if (it == 15) setupStep = EasySetupStep.CHOICE; if (it == 4) { speechLocation = SettingsLocation.CLOUD; speechEntry++ }; if (it == 12) { voiceLocation = captionVoiceLocation(); voiceEntry++ }; focus.clearFocus(); quickSettings = false; settingsReturns = emptyList(); route = AppNavigation.initial(it); homeDestination = null; showHome = false; navigation.value = null }
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
    fun home() { focus.clearFocus(); quickSettings = false; settingsReturns = emptyList(); homeDestination = null; showHome = true }
    fun back() {
     focus.clearFocus()
     if (page == 15 && !tvUi && setupStep.back() != null) {
      setupStep = requireNotNull(setupStep.back())
      return
     }
     settingsReturns.lastOrNull()?.takeIf { !tvUi && it.reachedFrom(route) }?.let { origin ->
      route = origin.origin
      showHome = origin.home
      homeDestination = origin.homeDestination
      settingsReturns = settingsReturns.dropLast(1)
      quickSettings = true
      return
     }
     if (intent.action == android.content.Intent.ACTION_PROCESS_TEXT && page == 11 && route.isRoot) { finish(); return }
     if (tvUi) { home(); return }
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
    fun fromSettingsSheet(open: () -> Unit) {
     val origin = route
     val originHome = atHome
     val originDestination = homeDestination
     quickSettings = false
     open()
     settingsReturns = SettingsSheetReturn.push(settingsReturns,
      SettingsSheetReturn(origin, originHome, originDestination, route.page))
    }
    fun reading(preserveOrigin: Boolean = false) {
     com.sal7one.transiber.home.HomeServiceStore.remember(this@MainActivity, com.sal7one.transiber.home.HomeService.SCREEN)
     if (simple && !preserveOrigin) { homeEntry++; home() }
     startActivity(android.content.Intent(this@MainActivity, com.sal7one.transiber.reading.ReadingStartActivity::class.java)) }
    LaunchedEffect(tvUi) { if (tvUi) { quickSettings = false; settingsReturns = emptyList() } }
    BackHandler(enabled = !atHome && !tvHome && (simple || route.canGoBack || settingsReturns.isNotEmpty())) { back() }
    BackHandler(enabled = quickSettings && !tvUi && settingsConfig == null) { quickSettings = false }
    val readySettings = settingsConfig
    RetainedSettingsSheet(visible = quickSettings && !tvUi, ready = readySettings != null,
     presentationSession = quickSettingsSession, retainingForChild = settingsReturns.isNotEmpty()) { scroll, sheetState ->
     SettingsQuickSheet(
      scroll = scroll, sheetState = sheetState, config = checkNotNull(readySettings),
      onDismiss = { quickSettings = false },
      onFeature = { location, feature -> fromSettingsSheet { settingsFeature(location, feature) } },
      onTranslationModels = { fromSettingsSheet { localModels("Translation") } },
      onPage = { destination -> fromSettingsSheet { go(destination) } },
      onReading = { reading(preserveOrigin = true) },
     )
    }
    if (quickSettings && !tvUi && readySettings == null && settingsConfigError != null) AlertDialog(
     onDismissRequest = { quickSettings = false },
     title = { Text(uiText(R.string.setup_preferences_failed)) },
     text = { Text(settingsConfigError.orEmpty()) },
     confirmButton = { TextButton(onClick = { retrySettingsConfig() }) { Text(uiText(R.string.setup_preferences_retry)) } },
     dismissButton = { TextButton(onClick = { quickSettings = false }) { Text(uiText(R.string.ui_close_bbfa7)) } },
    )
    FeatureBackdrop(artwork = when { atHome -> null; page == 0 -> R.drawable.home_captions; page == 7 -> R.drawable.home_conversation; page == 10 -> R.drawable.home_camera; page == 11 -> R.drawable.home_text; else -> R.drawable.home_face },
     tint = androidx.compose.ui.graphics.Color(when(page) { 7 -> 0xFFEAA077; 10 -> 0xFFEDA84C; 11 -> 0xFF9470F5; else -> 0xFF448EFF })) {
    Scaffold(modifier = Modifier.semantics { testTagsAsResourceId = true }, containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground, topBar = {
     if (!tvHome) TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .55f)), title = { Text(if (atHome || tvHome) "Hearth" else if (tvUi) uiText(R.string.tv_setup_title) else when(page) {
      0 -> uiText(R.string.label_maintab_captions_title); 1 -> uiText(R.string.ui_models_f3798); 2 -> uiText(R.string.nav_downloads); 3 -> uiText(R.string.label_maintab_settings_title)
      4 -> uiText(R.string.nav_speech_settings); 5 -> uiText(R.string.nav_advanced_captions)
      7 -> if (faceLayout) uiText(R.string.label_homeservice_face_title) else uiText(R.string.label_homeservice_conversation_title)
      8 -> uiText(R.string.ui_local_benchmark_3acfe); 9 -> uiText(R.string.label_settingsfeature_translation_label); 10 -> uiText(R.string.label_maintab_camera_title)
      11 -> uiText(R.string.label_maintab_translate_title); 12 -> uiText(R.string.label_settingsfeature_voices_label); 13 -> uiText(R.string.ui_appearance_navigation_433af); 14 -> uiText(R.string.overlay_shortcuts_title); 15 -> uiText(R.string.ui_easy_setup_35fb5); 16 -> uiText(R.string.caption_history_title); else -> uiText(R.string.nav_help)
     }, style = MaterialTheme.typography.titleMedium) },
      navigationIcon = { if (!atHome && !tvHome && (simple || !route.isRoot || settingsReturns.isNotEmpty())) IconButton(modifier = Modifier.testTag("app-back"), onClick = { if (page == 16) onBackPressedDispatcher.onBackPressed() else back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText(R.string.ui_back_b52b3)) } },
      actions = {
       if (simple && !atHome && !tvUi) IconButton(modifier = Modifier.testTag("app-home"), onClick = { home() }) { Icon(Icons.Default.Home, uiText(R.string.nav_home)) }
       if (television && fullInterface) TextButton(onClick = { fullInterface = false; home() }) { Text(uiText(R.string.tv_mode)) }
       if (!tvUi) SetupModePicker()
       if (!tvUi) IconButton(modifier = Modifier.testTag("quick-settings-open"), onClick = { if (settingsConfigError != null) retrySettingsConfig(); quickSettingsSession++; quickSettings = true }) { Icon(Icons.Default.Settings, uiText(R.string.nav_quick_settings)) }
      })
    }, bottomBar = {
     if (!simple) NavigationBar {
      MainTab.entries.forEach { tab ->
       NavigationBarItem(
        selected = route.tab == tab,
        onClick = { focus.clearFocus(); quickSettings = false; settingsReturns = emptyList(); route = route.select(tab) },
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
      preferencesFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
      if (quickSettings && readySettings == null && settingsConfigError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
      ModelBindingRepairNotice(onRepair = { localModels("Speech") }, launchError = modelCheckError)
      Box(Modifier.weight(1f)) {
       screenState.SaveableStateProvider(com.sal7one.transiber.ui.navigationStateKey(tvUi, atHome, page)) {
       if (tvHome) com.sal7one.transiber.home.TvHomeScreen(
        onSetup = { tvSetupFeature = com.sal7one.transiber.setup.TvSetupFeature.STT; go(15) },
        onStart = {
         lifecycleScope.launch {
          val saved = CaptionConfigStore.config(this@MainActivity).first()
          val openAi = saved.engine == CaptionEngineChoice.CLOUD && CloudConfigStore.sttMode(this@MainActivity) == CloudConfigStore.SttMode.STREAMING_OPENAI &&
           com.sal7one.transiber.setup.EasySetupPreset.canReuseKey(CloudConfigStore.Provider.OPENAI, CloudConfigStore.Provider.OPENAI.baseUrl,
            CloudConfigStore.provider(this@MainActivity), CloudConfigStore.baseUrl(this@MainActivity))
          if (ByokPolicy.FEATURE_BYOK && !openAi) { go(15); tvSetupFeature = com.sal7one.transiber.setup.TvSetupFeature.STT }
          else startActivity(android.content.Intent(this@MainActivity, com.sal7one.transiber.shortcuts.OverlayLaunchActivity::class.java))
         }
        },
       ) else if (tvUi) com.sal7one.transiber.setup.TvSetupScreen(feature = tvSetupFeature,
        onFeature = { tvSetupFeature = it }, onHome = { home() }, onFullInterface = { fullInterface = true; route = AppNavigation.initial(0); home() })
       else if (atHome) HomeScreen(
        entryRevision = homeEntry,
        onSetup = { go(15) },
        onCaptions = { go(0) },
        onTalk = { face -> faceLayout = face; go(7) },
        onTranslate = { go(11) },
        onCamera = { go(10) },
        onReading = { reading() },
       ) else when(page) {
        0 -> CaptionHome(onModels = { go(1) }, onCloud = { speechSettings(SettingsLocation.CLOUD) }, onHistory = { go(16) })
        1 -> ModelsScreen(onDownloads = { go(2) }, onVoices = { voiceSettings(SettingsLocation.LOCAL) }, initialSection = modelsSection)
        2 -> DownloadsScreen(onBrowseModels = { go(1) }, onVoices = { voiceSettings(SettingsLocation.LOCAL) })
        3 -> if (readySettings != null) SettingsScreen(
            initialConfig = readySettings,
            onEasySetup = { go(15) }, onHistory = { go(16) },
            onAppearance = { go(13) }, onShortcuts = { go(14) },
            onFeature = ::settingsFeature,
            onTranslationModels = { localModels("Translation") },
            onDownloads = { go(2) }, onBenchmark = { go(8) }, onAdvanced = { go(5) }, onHelp = { go(6) },
        ) else Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            settingsConfigError?.let { failure ->
                Text(failure, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retrySettingsConfig() }) { Text(uiText(R.string.setup_preferences_retry)) }
            } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        15 -> com.sal7one.transiber.setup.EasySetupScreen(step=setupStep,onStep={setupStep=it},onCaptions={go(0)},onHome={home()},onSettings={go(3)},onDownloads={go(2)})
        13 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { AppearanceSettings() }
        14 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) { SettingsShortcutsUi() }
        4 -> SettingsSpeechScreen(speechLocation, speechEntry, onHistory = { go(16) })
        5 -> CaptionScreen(onBrowseModels = { go(1) })
        7 -> ConversationScreen(onModels = { go(1) }, onCloud = { speechSettings(SettingsLocation.CLOUD) }, onLayoutChanged = { faceLayout = it }, initialFaceToFace = faceLayout, onVoices={voiceSettings()})
        10 -> com.sal7one.transiber.ocr.CameraTranslateScreen(onModels = { localModels("Camera") }, onConnections = { translationSettings(SettingsLocation.CLOUD) }, onDownloads = { go(2) },onVoices={voiceSettings()},sharedImage=sharedImage,onShareConsumed={sharedImage=null},onReading={reading(preserveOrigin = true)})
        11 -> com.sal7one.transiber.translation.TypedTranslateScreen(onModels={localModels("Translation")},onConnections={translationSettings(SettingsLocation.CLOUD)},onVoices={voiceSettings()},sharedText=sharedText,translateSharedTextImmediately=translateSharedTextImmediately,onShareConsumed={sharedText=null;translateSharedTextImmediately=false})
        12 -> com.sal7one.transiber.voice.VoiceSetup(onDownloads={go(2)}, initialLocation=voiceLocation, entryRevision=voiceEntry)
        16 -> com.sal7one.transiber.caption.history.CaptionHistoryScreen()
        8 -> LocalBenchmarkScreen(onModels = { localModels(it) }, onDownloads = { go(2) })
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

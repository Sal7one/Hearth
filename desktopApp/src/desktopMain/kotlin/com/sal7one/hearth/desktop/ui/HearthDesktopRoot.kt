package com.sal7one.hearth.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.sal7one.hearth.desktop.DesktopModelController
import com.sal7one.hearth.desktop.DesktopWorkspaceController
import com.sal7one.hearth.desktop.WorkspaceInput
import com.sal7one.hearth.desktop.chooseFile
import com.sal7one.hearth.desktop.chooseSaveFile
import com.sal7one.hearth.desktop.copyToClipboard
import com.sal7one.hearth.desktop.finalTranscript
import com.sal7one.hearth.desktop.openPath
import com.sal7one.hearth.desktop.useVerifiedModel
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.desktop.ui.screens.models.ModelsActions
import com.sal7one.hearth.desktop.ui.screens.models.ModelsFilter
import com.sal7one.hearth.desktop.ui.screens.models.ModelsScreen
import com.sal7one.hearth.desktop.ui.screens.models.modelsState
import com.sal7one.hearth.desktop.engineSelection
import com.sal7one.hearth.desktop.ui.screens.speech.Compute
import com.sal7one.hearth.desktop.ui.screens.speech.Responsiveness
import com.sal7one.hearth.desktop.ui.screens.speech.SpeechActions
import com.sal7one.hearth.desktop.ui.screens.speech.SpeechScreen
import com.sal7one.hearth.desktop.ui.screens.speech.speechState
import com.sal7one.hearth.desktop.ui.screens.translation.TranslationActions
import com.sal7one.hearth.desktop.ui.screens.translation.TranslationScreen
import com.sal7one.hearth.desktop.ui.screens.translation.translationState
import com.sal7one.hearth.desktop.ui.screens.voice.VoicesActions
import com.sal7one.hearth.desktop.ui.screens.voice.VoicesScreen
import com.sal7one.hearth.desktop.ui.screens.voice.voicesState
import com.sal7one.hearth.desktop.ui.screens.appearance.AppearanceActions
import com.sal7one.hearth.desktop.ui.screens.appearance.AppearanceScreen
import com.sal7one.hearth.desktop.ui.screens.appearance.appearanceState
import com.sal7one.hearth.desktop.ui.screens.files.FilesActions
import com.sal7one.hearth.desktop.ui.screens.files.FilesScreen
import com.sal7one.hearth.desktop.ui.screens.files.filesState
import com.sal7one.hearth.desktop.ui.screens.connections.ConnectionsActions
import com.sal7one.hearth.desktop.ui.screens.connections.ConnectionsScreen
import com.sal7one.hearth.desktop.ui.screens.connections.connectionsState
import com.sal7one.hearth.desktop.ui.screens.integrations.IntegrationsActions
import com.sal7one.hearth.desktop.ui.screens.integrations.IntegrationsScreen
import com.sal7one.hearth.desktop.ui.screens.integrations.integrationsState
import com.sal7one.hearth.desktop.ui.screens.translate.TranslateScreen
import com.sal7one.hearth.desktop.ui.screens.translate.translateState
import com.sal7one.hearth.desktop.ui.screens.presets.BuiltInPresets
import com.sal7one.hearth.desktop.ui.screens.presets.PresetsActions
import com.sal7one.hearth.desktop.ui.screens.presets.PresetsScreen
import com.sal7one.hearth.desktop.ui.screens.presets.presetsState
import com.sal7one.hearth.desktop.ui.screens.downloads.DownloadsActions
import com.sal7one.hearth.desktop.ui.screens.downloads.DownloadsScreen
import com.sal7one.hearth.desktop.ui.screens.downloads.downloadsState
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsActions
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsState
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsScreen
import com.sal7one.hearth.desktop.ui.screens.captions.StageSlot
import com.sal7one.hearth.desktop.ui.screens.captions.captionsState
import com.sal7one.hearth.desktop.ui.screens.home.HomeScreen
import com.sal7one.hearth.desktop.ui.screens.home.homeState
import com.sal7one.hearth.desktop.ui.screens.overlay.FloatingCaptionWindow
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayActions
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayScreen
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlaySettings
import com.sal7one.hearth.desktop.ui.screens.overlay.overlayPrefs
import com.sal7one.hearth.desktop.ui.screens.overlay.overlaySettingsFrom
import com.sal7one.hearth.desktop.ui.screens.overlay.overlayState
import com.sal7one.hearth.desktop.ui.shell.AppShell
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import kotlinx.coroutines.launch

/**
 * The application root: navigation, appearance, and the mapping from controllers
 * to each screen's view state. Screens never talk to a controller directly, which
 * is what makes them renderable from a fixed fixture.
 */
@Composable
internal fun HearthDesktopRoot(
    controller: DesktopWorkspaceController,
    models: DesktopModelController,
    initialUi: Map<String, String>,
    saveUi: (Map<String, String>) -> Unit,
    closing: Boolean,
) {
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(DesktopScreen.fromId(initialUi["screen"] ?: "home")) }
    var dark by remember { mutableStateOf(initialUi["theme"] != "light") }
    var preset by remember {
        mutableStateOf(AccentPreset.entries.firstOrNull { it.name == initialUi["palette"] } ?: AccentPreset.INK)
    }
    var showPendingOriginal by remember { mutableStateOf(initialUi["overlay-pending"] != "false") }
    var overlaySettings by remember { mutableStateOf(overlaySettingsFrom(initialUi)) }
    var reduceMotion by remember { mutableStateOf(initialUi["reduce-motion"] == "true") }
    var deviceId by remember { mutableStateOf<String?>(null) }
    var modelFilter by remember {
        mutableStateOf(
            ModelsFilter(
                stage = initialUi["model-stage"]?.let { stored -> PipelineStage.entries.firstOrNull { it.name == stored } },
                onlyInstalled = initialUi["model-installed"] == "true",
            ),
        )
    }

    val config by controller.config.collectAsState()
    val workspace by controller.state.collectAsState()
    val library by models.state.collectAsState()
    val installations by models.library.installations.collectAsState()
    val freeBytes = remember {
        runCatching { java.io.File(controller.dataDirectory.toFile(), ".").usableSpace }.getOrDefault(0L)
    }

    val installed = remember(library.refreshing) {
        runCatching {
            models.library.catalog.count { models.library.installedSelection(it.id) != null }
        }.getOrDefault(0)
    }

    fun applyOverlay(next: OverlaySettings) {
        overlaySettings = next
        saveUi(overlayPrefs(next))
    }

    fun navigate(destination: DesktopScreen) {
        if (screen != destination) {
            screen = destination
            saveUi(mapOf("screen" to destination.id))
        }
    }

    fun failure(message: String) = controller.showError(message)

    val home = homeState(
        config = config,
        capabilities = controller.capabilities,
        installedModels = installed,
        verifiedModels = installed,
        statusText = when {
            closing -> "Closing"
            workspace.error != null -> "Needs attention"
            workspace.active -> "Listening"
            else -> if (installed > 0) "Ready" else "Setup needed"
        },
        statusLive = workspace.active && !closing,
    )

    val libraryState = modelsState(
        catalog = models.library.catalog,
        installations = installations,
        config = config,
        transfer = library.transfer,
        filter = modelFilter,
        freeBytes = freeBytes,
    ).copy(refreshing = library.refreshing)

    val modelsActions = ModelsActions(
        onSearch = { query -> modelFilter = modelFilter.copy(query = query) },
        onFilterStage = { stage ->
            modelFilter = modelFilter.copy(stage = stage)
            saveUi(mapOf("model-stage" to stage?.name.orEmpty()))
        },
        onToggleInstalledOnly = {
            modelFilter = modelFilter.copy(onlyInstalled = !modelFilter.onlyInstalled)
            saveUi(mapOf("model-installed" to modelFilter.onlyInstalled.toString()))
        },
        onAction = { id ->
            val card = models.library.find(id)
            val installed = models.library.installedSelection(id)
            if (installed != null) {
                controller.updateConfig { it.useVerifiedModel(card.stage, installed) }
            } else {
                models.download(id)
            }
        },
        onRefresh = { models.refresh(config) },
        onOpenFolder = {
            runCatching { openPath(controller.dataDirectory) }
                .onFailure { failure(it.message ?: it.toString()) }
        },
        onNavigate = ::navigate,
    )

    val speech = speechState(
        capabilities = controller.capabilities,
        config = config,
        catalog = models.library.catalog,
        installations = installations,
        devices = workspace.devices,
        deviceId = deviceId,
    )

    fun withSpeechOptions(key: String, value: String) = controller.updateConfig { current ->
        val selected = current.speech ?: return@updateConfig current
        current.copy(speech = selected.copy(options = selected.options + (key to value)))
    }

    val speechActions = SpeechActions(
        onNavigate = ::navigate,
        onSelectEngine = { id ->
            controller.capabilities.firstOrNull { it.id == id && it.stage == PipelineStage.STT }?.let { engine ->
                controller.updateConfig { current -> current.copy(speech = engineSelection(engine)) }
            }
        },
        onSelectModel = { id ->
            val verified = models.library.installedSelection(id)
            if (verified != null) {
                controller.updateConfig { current -> current.useVerifiedModel(PipelineStage.STT, verified) }
            } else {
                navigate(DesktopScreen.DOWNLOADS)
            }
        },
        onLanguage = { code -> controller.updateConfig { current -> current.copy(sourceLanguage = code) } },
        onDevice = { id -> deviceId = id },
        onRefreshDevices = { controller.refreshDevices() },
        onResponsiveness = { level -> withSpeechOptions("windowMs", level.windowMs.toString()) },
        onCompute = { compute -> withSpeechOptions("acceleration", compute.key) },
        onThreads = { threads -> withSpeechOptions("threads", threads.coerceIn(1, 32).toString()) },
        onTestMicrophone = {
            if (speech.ready) {
                controller.start(WorkspaceInput.MICROPHONE, "", "", deviceId)
                navigate(DesktopScreen.CAPTIONS)
            } else {
                navigate(DesktopScreen.MODELS)
            }
        },
    )

    val translation = translationState(
        capabilities = controller.capabilities,
        config = config,
        catalog = models.library.catalog,
        installations = installations,
    )

    fun withTranslationOption(key: String, value: String) = controller.updateConfig { current ->
        val selected = current.translation ?: return@updateConfig current
        current.copy(translation = selected.copy(options = selected.options + (key to value)))
    }

    val translationActions = TranslationActions(
        onNavigate = ::navigate,
        onSelectEngine = { id ->
            if (id == null) {
                controller.updateConfig { current -> current.copy(translation = null) }
            } else {
                controller.capabilities.firstOrNull { it.id == id && it.stage == PipelineStage.TRANSLATION }?.let { engine ->
                    controller.updateConfig { current -> current.copy(translation = engineSelection(engine)) }
                }
            }
        },
        onSelectModel = { id ->
            val verified = models.library.installedSelection(id)
            if (verified != null) {
                controller.updateConfig { current -> current.useVerifiedModel(PipelineStage.TRANSLATION, verified) }
            } else {
                navigate(DesktopScreen.DOWNLOADS)
            }
        },
        onSourceLanguage = { code -> controller.updateConfig { current -> current.copy(sourceLanguage = code) } },
        onTargetLanguage = { code -> controller.updateConfig { current -> current.copy(targetLanguage = code) } },
        onPromptMode = { mode -> withTranslationOption("promptMode", mode.key) },
        onCompute = { compute -> withTranslationOption("acceleration", compute.key) },
        onThreads = { threads -> withTranslationOption("threads", threads.coerceIn(1, 32).toString()) },
    )

    val voices = voicesState(controller.capabilities, config)

    fun withVoiceOption(key: String, value: String) = controller.updateConfig { current ->
        val selected = current.voice ?: return@updateConfig current
        current.copy(voice = selected.copy(options = selected.options + (key to value)))
    }

    val voicesActions = VoicesActions(
        onNavigate = ::navigate,
        onSelectEngine = { id ->
            if (id == null) {
                controller.updateConfig { current -> current.copy(voice = null, voiceId = "") }
            } else {
                controller.capabilities.firstOrNull { it.id == id && it.stage == PipelineStage.TTS }?.let { engine ->
                    controller.updateConfig { current ->
                        current.copy(voice = engineSelection(engine), voiceId = current.voiceId.ifBlank { "F1" })
                    }
                }
            }
        },
        onSelectStyle = { style -> controller.updateConfig { current -> current.copy(voiceId = style) } },
        onSpeakTranslation = { spoken -> controller.updateConfig { current -> current.copy(speakTranslatedText = spoken) } },
        onToggleReadAloud = { aloud ->
            if (aloud) {
                controller.capabilities.firstOrNull { it.id == "supertonic-local" || it.stage == PipelineStage.TTS }?.let { engine ->
                    controller.updateConfig { current ->
                        current.copy(voice = current.voice ?: engineSelection(engine), voiceId = current.voiceId.ifBlank { "F1" })
                    }
                }
            } else {
                controller.updateConfig { current -> current.copy(voice = null) }
            }
        },
        onSpeed = { speed -> withVoiceOption("speed", String.format(java.util.Locale.ENGLISH, "%.2f", speed)) },
        onSteps = { steps -> withVoiceOption("steps", steps.toString()) },
        onStopAudio = { controller.stopPlayback() },
    )

    val integrations = integrationsState(workspace)

    val integrationsActions = IntegrationsActions(
        onStart = { controller.startIntegration() },
        onStop = { controller.stopIntegration() },
        onCopyOverlayUrl = {
            runCatching { copyToClipboard(workspace.integration?.overlayUrl.orEmpty()) }
                .onFailure { failure(it.message ?: it.toString()) }
        },
        onCopyApiToken = { controller.copyApiToken(::copyToClipboard) },
    )

    val translate = translateState(config, workspace)

    val connections = connectionsState(
        capabilities = controller.capabilities,
        config = config,
        credentialStorage = "the operating system secure store",
    )

    val connectionsActions = ConnectionsActions(
        onNavigate = ::navigate,
        onActivate = { engineId ->
            controller.capabilities.firstOrNull { it.id == engineId }?.let { engine ->
                controller.updateConfig { current ->
                    when (engine.stage) {
                        PipelineStage.STT -> current.copy(speech = engineSelection(engine))
                        PipelineStage.TRANSLATION -> current.copy(translation = engineSelection(engine))
                        PipelineStage.TTS -> current.copy(voice = engineSelection(engine))
                    }
                }
            }
        },
        onSave = { engineId, endpoint, model, credentialId ->
            controller.capabilities.firstOrNull { it.id == engineId }?.let { engine ->
                controller.updateConfig { current ->
                    val route = EngineSelection(
                        engineId = engine.id,
                        modelId = model,
                        endpoint = endpoint,
                        credentialId = credentialId,
                    )
                    when (engine.stage) {
                        PipelineStage.STT -> current.copy(speech = route)
                        PipelineStage.TRANSLATION -> current.copy(translation = route)
                        PipelineStage.TTS -> current.copy(voice = route)
                    }
                }
            }
        },
        onSaveKey = { credentialId, secret -> controller.saveCredential(credentialId, secret) },
        onForgetKey = { credentialId -> controller.forgetCredential(credentialId) },
    )

    val appearance = appearanceState(selected = preset, dark = dark, reduceMotion = reduceMotion)

    val appearanceActions = AppearanceActions(
        onNavigate = ::navigate,
        onPalette = { chosen ->
            preset = chosen
            saveUi(mapOf("palette" to chosen.name))
        },
        onDark = { isDark ->
            dark = isDark
            saveUi(mapOf("theme" to if (isDark) "dark" else "light"))
        },
        onReduceMotion = { reduced ->
            reduceMotion = reduced
            saveUi(mapOf("reduce-motion" to reduced.toString()))
        },
    )

    val files = filesState(
        dataDirectory = controller.dataDirectory,
        catalog = models.library.catalog,
        installations = installations,
        freeBytes = freeBytes,
    )

    val filesActions = FilesActions(
        onNavigate = ::navigate,
        onOpenFolder = { path ->
            runCatching { openPath(java.nio.file.Path.of(path)) }
                .onFailure { failure(it.message ?: it.toString()) }
        },
        onExportSetup = {
            val target = chooseSaveFile(null, "Export Hearth setup", "hearth-setup.json")
            if (target != null) controller.exportSetup(target)
        },
        onImportSetup = {
            val source = chooseFile(null, "Import Hearth setup", listOf("json"))
            if (source != null) controller.importSetup(source)
        },
    )

    val presets = presetsState(
        definitions = BuiltInPresets.all,
        catalog = models.library.catalog,
        installations = installations,
        config = config,
    )

    val presetsActions = PresetsActions(
        onApply = { id ->
            val definition = BuiltInPresets.all.first { it.id == id }
            val speech = definition.speechId?.let(models.library::installedSelection)
            val translation = definition.translationId?.let(models.library::installedSelection)
            val voice = definition.voiceId?.let(models.library::installedSelection)
            val missing = buildList {
                if (definition.speechId != null && speech == null) add(definition.speechId)
                if (definition.translationId != null && translation == null) add(definition.translationId)
                if (definition.voiceId != null && voice == null) add(definition.voiceId)
            }
            if (missing.isEmpty()) {
                controller.updateConfig {
                    it.copy(
                        speech = speech,
                        translation = translation,
                        voice = voice,
                        voiceId = if (voice != null) definition.voiceStyle else "",
                    )
                }
            } else {
                navigate(DesktopScreen.DOWNLOADS)
            }
        },
        onNavigate = ::navigate,
        onOpenDownloads = { navigate(DesktopScreen.DOWNLOADS) },
    )

    val downloads = downloadsState(
        catalog = models.library.catalog,
        installations = installations,
        transfer = library.transfer,
        freeBytes = freeBytes,
    ).copy(refreshing = library.refreshing)

    val downloadsActions = DownloadsActions(
        onDownload = { id -> models.download(id) },
        onCancel = { models.cancel() },
        onRefresh = { models.refresh(config) },
        onOpenFolder = {
            runCatching { openPath(controller.dataDirectory) }
                .onFailure { failure(it.message ?: it.toString()) }
        },
        onNavigate = ::navigate,
    )

    val overlay = overlayState(
        snapshot = workspace.snapshot,
        translationEnabled = config.translation != null,
        targetLanguage = config.targetLanguage,
        settings = overlaySettings,
    )

    val overlayActions = OverlayActions(
        onChange = ::applyOverlay,
        onToggleVisible = { visible -> applyOverlay(overlaySettings.copy(visible = visible)) },
        onReset = {
            applyOverlay(
                OverlaySettings(
                    showOriginal = overlaySettings.showOriginal,
                    showPartials = overlaySettings.showPartials,
                    visible = overlaySettings.visible,
                ),
            )
        },
    )

    val captions: CaptionsState = captionsState(
        workspace = workspace,
        config = config,
        capabilities = controller.capabilities,
        showOriginal = overlaySettings.showOriginal,
        showPendingOriginal = showPendingOriginal,
        overlayVisible = overlaySettings.visible,
        deviceName = workspace.devices.firstOrNull { it.id == deviceId }?.name,
        inputLabel = "Microphone",
    )

    val captionsActions = CaptionsActions(
        onToggleOverlay = { visible -> applyOverlay(overlaySettings.copy(visible = visible)) },
        onShowOriginal = { applyOverlay(overlaySettings.copy(showOriginal = it)) },
        onShowPendingOriginal = {
            showPendingOriginal = it
            saveUi(mapOf("overlay-pending" to it.toString()))
        },
        onOpenStages = { navigate(DesktopScreen.MODELS) },
        onChangeStage = { slot ->
            navigate(
                when (slot) {
                    StageSlot.RECOGNIZE -> DesktopScreen.SPEECH
                    StageSlot.TRANSLATE -> DesktopScreen.TRANSLATION_SETTINGS
                    StageSlot.SPEAK -> DesktopScreen.VOICES
                },
            )
        },
        onCopyTranscript = {
            runCatching { copyToClipboard(finalTranscript(workspace.snapshot, false)) }
                .onFailure { failure(it.message ?: it.toString()) }
        },
        onCopyTranslation = {
            runCatching { copyToClipboard(finalTranscript(workspace.snapshot, true)) }
                .onFailure { failure(it.message ?: it.toString()) }
        },
        onSaveTranscript = {
            val path = chooseSaveFile(null, "Export transcript", "hearth-transcript.txt")
            if (path != null) controller.exportTranscript(path, false)
        },
        onPlaySpeech = {
            val audio = workspace.snapshot?.audio?.lastOrNull()
            if (audio != null) controller.play(audio) else failure("This session has not produced any speech yet")
        },
        onStopSpeech = { controller.stopPlayback() },
        onStart = {
            if (!captions.speechReady) {
                navigate(DesktopScreen.MODELS)
            } else {
                controller.start(WorkspaceInput.MICROPHONE, "", "", deviceId)
            }
        },
        onStop = { controller.cancel() },
        onFinishMicrophone = { controller.finishMicrophone() },
        onSwapLanguages = {
            controller.updateConfig {
                it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
            }
        },
        onToggleTranslate = {
            if (config.translation == null) navigate(DesktopScreen.TRANSLATION_SETTINGS)
            else controller.updateConfig { it.copy(translation = null) }
        },
        onToggleVoice = {
            if (config.voice == null) navigate(DesktopScreen.VOICES)
            else controller.updateConfig { it.copy(voice = null) }
        },
        onPickMicrophone = { /* already the active input; the label is the menu anchor */ },
        onPickAudioFile = {
            val path = chooseFile(null, "Choose audio or video", listOf("wav", "mp4", "mov", "m4a", "mp3", "aac"))
            if (path != null) {
                if (captions.speechReady) {
                    controller.start(WorkspaceInput.AUDIO_FILE, "", path.toAbsolutePath().toString(), null)
                } else {
                    navigate(DesktopScreen.MODELS)
                }
            }
        },
        onPickText = { navigate(DesktopScreen.TRANSLATE) },
        onRetry = { controller.clearNotice() },
    )

    HearthTheme(preset = preset, dark = dark, reduceMotion = reduceMotion) {
        AppShell(
            current = screen,
            status = home.statusText,
            statusLive = home.statusLive,
            dark = dark,
            onToggleTheme = {
                dark = !dark
                saveUi(mapOf("theme" to if (dark) "dark" else "light"))
            },
            onNavigate = ::navigate,
        ) {
            when (screen) {
                DesktopScreen.HOME -> HomeScreen(
                    state = home,
                    onStart = { navigate(DesktopScreen.CAPTIONS) },
                    onSwapLanguages = {
                        controller.updateConfig {
                            it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
                        }
                    },
                    onOpen = ::navigate,
                    onOpenWorkspace = { navigate(DesktopScreen.CAPTIONS) },
                )
                DesktopScreen.INTEGRATIONS -> IntegrationsScreen(state = integrations, actions = integrationsActions)
                DesktopScreen.TRANSLATE -> TranslateScreen(
                    state = translate,
                    onTranslate = { text ->
                        if (text.isNotBlank()) controller.start(WorkspaceInput.TEXT, text, "", null)
                    },
                    onSwapLanguages = {
                        controller.updateConfig {
                            it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
                        }
                    },
                    onCopyResult = {
                        runCatching { copyToClipboard(translate.translated.ifBlank { translate.original }) }
                            .onFailure { failure(it.message ?: it.toString()) }
                    },
                    onOpenVoice = { navigate(DesktopScreen.VOICES) },
                )
                DesktopScreen.CONNECTIONS -> ConnectionsScreen(state = connections, actions = connectionsActions)
                DesktopScreen.APPEARANCE -> AppearanceScreen(
                    state = appearance,
                    overlay = overlay,
                    actions = appearanceActions,
                    storageLabel = libraryState.storageLabel,
                    freeLabel = libraryState.freeDiskLabel,
                )
                DesktopScreen.FILES -> FilesScreen(state = files, actions = filesActions)
                DesktopScreen.SPEECH -> SpeechScreen(state = speech, actions = speechActions)
                DesktopScreen.TRANSLATION_SETTINGS -> TranslationScreen(state = translation, actions = translationActions)
                DesktopScreen.VOICES -> VoicesScreen(state = voices, actions = voicesActions)
                DesktopScreen.MODELS -> ModelsScreen(state = libraryState, actions = modelsActions)
                DesktopScreen.PRESETS -> PresetsScreen(state = presets, actions = presetsActions)
                DesktopScreen.DOWNLOADS -> DownloadsScreen(state = downloads, actions = downloadsActions)
                DesktopScreen.OVERLAY -> OverlayScreen(state = overlay, actions = overlayActions)
                DesktopScreen.CAPTIONS -> CaptionsScreen(
                    state = captions,
                    actions = captionsActions,
                    onOpenModels = { navigate(DesktopScreen.MODELS) },
                )
                else -> NotPortedScreen(screen = screen, onBack = { navigate(DesktopScreen.HOME) })
            }
        }

        if (overlaySettings.visible) {
            FloatingCaptionWindow(
                state = overlay,
                dark = dark,
                preset = preset,
                onClose = { applyOverlay(overlaySettings.copy(visible = false)) },
            )
        }
    }
}

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
import com.sal7one.hearth.desktop.data.DesktopModelStatus
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
import com.sal7one.hearth.desktop.ui.screens.models.ModelHardwareFilter
import com.sal7one.hearth.desktop.ui.screens.models.ModelsFilter
import com.sal7one.hearth.desktop.ui.screens.models.ModelsScreen
import com.sal7one.hearth.desktop.ui.screens.models.modelsState
import com.sal7one.hearth.desktop.chooseEngine
import com.sal7one.hearth.desktop.selection
import com.sal7one.hearth.desktop.enableReadAloud
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
import com.sal7one.hearth.desktop.ui.screens.conversation.ConversationScreen
import com.sal7one.hearth.desktop.ui.screens.camera.CameraScreen
import com.sal7one.hearth.desktop.ui.screens.reading.ReadingScreen
import com.sal7one.hearth.desktop.ui.screens.integrations.IntegrationsActions
import com.sal7one.hearth.desktop.ui.screens.integrations.IntegrationsScreen
import com.sal7one.hearth.desktop.ui.screens.integrations.integrationsState
import com.sal7one.hearth.desktop.ui.screens.translate.TranslateActions
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
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayShortcutBinding
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayCommand
import com.sal7one.hearth.desktop.ui.screens.overlay.reduceOverlayCommand
import com.sal7one.hearth.desktop.platform.NativeOverlayWindow
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
    rememberedEngine: (String) -> com.sal7one.hearth.core.PipelineConfig? = { null },
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
    val systemAudioAvailable = remember { com.sal7one.hearth.desktop.runtime.DesktopSystemAudioCapture.available }
    val systemAudioReason = remember { com.sal7one.hearth.desktop.runtime.DesktopSystemAudioCapture.availabilityReason }
    var captureSource by remember { mutableStateOf(
        if (initialUi["audio-source"] == "microphone" || !systemAudioAvailable) WorkspaceInput.MICROPHONE else WorkspaceInput.SYSTEM_AUDIO
    ) }
    var deviceId by remember { mutableStateOf<String?>(null) }
    var translationDraft by remember { mutableStateOf("") }
    var modelFilter by remember {
        mutableStateOf(
            ModelsFilter(
                stage = initialUi["model-stage"]?.let { stored -> PipelineStage.entries.firstOrNull { it.name == stored } },
                onlyInstalled = initialUi["model-installed"] == "true",
                hardware = ModelHardwareFilter.fromKey(initialUi["model-hardware"]),
            ),
        )
    }

    val config by controller.config.collectAsState()
    val workspace by controller.state.collectAsState()
    val activeConfig = if (workspace.active) workspace.sessionConfig ?: config else config
    val library by models.state.collectAsState()
    val managedInstallations by models.library.installations.collectAsState()
    val installations = managedInstallations + library.verifiedImports.mapValues { (_, route) ->
        DesktopModelStatus(true, java.nio.file.Path.of(route.modelPath))
    }
    val freeBytes = remember {
        runCatching { java.io.File(controller.dataDirectory.toFile(), ".").usableSpace }.getOrDefault(0L)
    }

    val installed = remember(installations) { installations.values.count { it.ready } }

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

    fun sourceLanguage(code: String) = controller.updateConfig { it.copy(sourceLanguage = code) }
    fun targetLanguage(code: String) = controller.updateConfig { it.copy(targetLanguage = code) }
    fun swapLanguages() = controller.updateConfig { current ->
        val choices = com.sal7one.hearth.desktop.ui.language.pipelineLanguages(current, controller.capabilities, models.library.catalog,
            typedText = screen == DesktopScreen.TRANSLATE)
        if (choices.canSwap) current.copy(sourceLanguage = current.targetLanguage, targetLanguage = current.sourceLanguage) else current
    }
    fun pickCaptureSource(source: WorkspaceInput) {
        if (workspace.active) return
        captureSource = source
        saveUi(mapOf("audio-source" to if (source == WorkspaceInput.SYSTEM_AUDIO) "system" else "microphone"))
    }

    fun installedForEngine(engineId: String): EngineSelection? = models.library.catalog
        .asSequence().filter { it.engineId == engineId }.mapNotNull { models.verifiedSelection(it.id) }.firstOrNull()

    fun normalize(current: com.sal7one.hearth.core.PipelineConfig) =
        com.sal7one.hearth.desktop.ui.language.normalizePipelineLanguages(current, controller.capabilities, models.library.catalog)

    fun chooseStageEngine(current: com.sal7one.hearth.core.PipelineConfig, engine: com.sal7one.hearth.core.EngineDescriptor) =
        normalize(current.chooseEngine(engine, rememberedEngine(engine.id), installedForEngine(engine.id)))

    fun selectEngine(engine: com.sal7one.hearth.core.EngineDescriptor) {
        scope.launch {
            controller.updateConfig { current -> chooseStageEngine(current, engine) }.join()
            val route = controller.config.value.selection(engine.stage)
            if (route?.engineId == engine.id && route.modelPath.isNotBlank() &&
                models.library.catalog.any { it.id == route.modelId && it.engineId == route.engineId } &&
                models.verifiedSelection(route.modelId)?.modelPath != route.modelPath) {
                models.verify(route.modelId, java.nio.file.Path.of(route.modelPath))
            }
        }
    }

    val home = homeState(
        config = activeConfig,
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
        catalog = models.library.catalog,
        installations = installations,
    ).copy(inputLabel = if ((if (workspace.active) workspace.input ?: captureSource else captureSource) == WorkspaceInput.SYSTEM_AUDIO) "Mac audio" else "Microphone")

    val libraryState = modelsState(
        catalog = models.library.catalog,
        installations = installations,
        config = config,
        transfers = library.transfers.values,
        filter = modelFilter,
        freeBytes = freeBytes,
        verifiedImports = library.verifiedImports,
        issues = library.issues,
        engines = controller.capabilities,
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
        onFilterHardware = { hardware ->
            modelFilter = modelFilter.copy(hardware = hardware)
            saveUi(mapOf("model-hardware" to hardware.key))
        },
        onResetFilters = {
            modelFilter = ModelsFilter()
            saveUi(mapOf("model-hardware" to "all", "model-stage" to "", "model-installed" to "false"))
        },
        onAction = { id ->
            val card = models.library.find(id)
            val installed = models.verifiedSelection(id)
            if (installed != null) {
                controller.updateConfig { normalize(it.useVerifiedModel(card.stage, installed)) }
            } else {
                models.download(id)
            }
        },
        onImport = { id ->
            if (workspace.importingSetup || id in library.transfers) {
                failure("This model is already being checked or downloaded")
            } else {
                val card = models.library.find(id)
                val folder = card.artifacts.size != 1 || card.archive != null || card.rangePackage != null
                val path = chooseFile(null, if (folder) "Locate complete ${card.name} folder" else "Locate ${card.artifacts.single().filename}",
                    directoriesOnly = folder)
                if (path != null) models.verify(id, path)
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
                selectEngine(engine)
            }
        },
        onSelectModel = { id ->
            val verified = models.verifiedSelection(id)
            if (verified != null) {
                controller.updateConfig { current -> normalize(current.useVerifiedModel(PipelineStage.STT, verified)) }
            } else {
                navigate(DesktopScreen.DOWNLOADS)
            }
        },
        onLanguage = { code -> controller.updateConfig { current -> current.copy(sourceLanguage = code) } },
        onDevice = { id -> deviceId = id?.takeIf(String::isNotBlank) },
        onRefreshDevices = { controller.refreshDevices() },
        onResponsiveness = { level -> withSpeechOptions("windowMs", level.windowMs.toString()) },
        onCompute = { compute -> withSpeechOptions("acceleration", compute.key) },
        onThreads = { threads -> withSpeechOptions("threads", threads.coerceIn(1, 32).toString()) },
        onTestMicrophone = {
            if (speech.ready) {
                pickCaptureSource(WorkspaceInput.MICROPHONE)
                applyOverlay(overlaySettings.copy(visible = true))
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
                    selectEngine(engine)
                }
            }
        },
        onSelectModel = { id ->
            val verified = models.verifiedSelection(id)
            if (verified != null) {
                controller.updateConfig { current -> normalize(current.useVerifiedModel(PipelineStage.TRANSLATION, verified)) }
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

    val voices = voicesState(controller.capabilities, config, models.library.catalog, installations)

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
                    selectEngine(engine)
                }
            }
        },
        onSelectStyle = { style -> controller.updateConfig { current -> current.copy(voiceId = style) } },
        onSpeakTranslation = { spoken -> controller.updateConfig { current -> current.copy(speakTranslatedText = spoken) } },
        onToggleReadAloud = { aloud ->
            if (aloud) {
                controller.updateConfig { current -> current.enableReadAloud(controller.capabilities, rememberedEngine, ::installedForEngine) }
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

    val translate = translateState(config, workspace, controller.capabilities, models.library.catalog, installations)

    val connections = connectionsState(
        capabilities = controller.capabilities,
        config = config,
        credentialStorage = "the operating system secure store",
    )

    val connectionsActions = ConnectionsActions(
        onNavigate = ::navigate,
        onActivate = { engineId ->
            controller.capabilities.firstOrNull { it.id == engineId }?.let { engine ->
                selectEngine(engine)
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
            val speech = definition.speechId?.let(models::verifiedSelection)
            val translation = definition.translationId?.let(models::verifiedSelection)
            val voice = definition.voiceId?.let(models::verifiedSelection)
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
        transfers = library.transfers.values,
        freeBytes = freeBytes,
        issues = library.issues,
    ).copy(refreshing = library.refreshing)

    val downloadsActions = DownloadsActions(
        onDownload = { id -> models.download(id) },
        onCancel = models::cancel,
        onRefresh = { models.refresh(config) },
        onOpenFolder = {
            runCatching { openPath(controller.dataDirectory) }
                .onFailure { failure(it.message ?: it.toString()) }
        },
        onNavigate = ::navigate,
    )

    var overlayControlIssue by remember { mutableStateOf<String?>(null) }
    val shortcutStatus = OverlayShortcutBinding(overlaySettings.shortcutsEnabled) { command ->
        if (command != OverlayCommand.LOCK || NativeOverlayWindow.supported) {
            applyOverlay(reduceOverlayCommand(overlaySettings, command))
        }
    }
    val overlay = overlayState(
        snapshot = workspace.snapshot,
        translationEnabled = activeConfig.translation != null,
        targetLanguage = activeConfig.targetLanguage,
        settings = overlaySettings,
        showOriginalWhilePending = showPendingOriginal,
    ).copy(shortcutStatus = shortcutStatus, nativeControlsAvailable = NativeOverlayWindow.supported, controlIssue = overlayControlIssue)

    val overlayActions = OverlayActions(
        onChange = ::applyOverlay,
        onToggleVisible = { visible -> applyOverlay(overlaySettings.copy(visible = visible)) },
        onReset = { applyOverlay(reduceOverlayCommand(overlaySettings, OverlayCommand.RESET)) },
    )

    val captions: CaptionsState = captionsState(
        workspace = workspace,
        config = activeConfig,
        capabilities = controller.capabilities,
        showOriginal = overlaySettings.showOriginal,
        showPendingOriginal = showPendingOriginal,
        overlayVisible = overlaySettings.visible,
        deviceName = workspace.devices.firstOrNull { it.id == deviceId }?.name,
        inputLabel = when (if (workspace.active) workspace.input ?: captureSource else captureSource) {
            WorkspaceInput.SYSTEM_AUDIO -> "Mac audio"
            WorkspaceInput.MICROPHONE -> "Microphone"
            WorkspaceInput.AUDIO_FILE -> "Audio file"
            WorkspaceInput.TEXT -> "Text"
        },
        catalog = models.library.catalog,
        installations = installations,
        systemAudioAvailable = systemAudioAvailable,
        systemAudioUnavailableReason = systemAudioReason,
    )

    fun startLiveCapture() {
        navigate(DesktopScreen.CAPTIONS)
        if (workspace.active) {
            applyOverlay(overlaySettings.copy(visible = true))
            return
        }
        if (!captions.readyToStart) {
            failure(captions.readinessIssue ?: captions.languages.issue ?: "Complete the speech setup first")
            return
        }
        if (captureSource == WorkspaceInput.SYSTEM_AUDIO && !systemAudioAvailable) {
            failure(systemAudioReason ?: "Mac audio capture is unavailable")
            return
        }
        applyOverlay(overlaySettings.copy(visible = true))
        controller.start(captureSource, "", "", if (captureSource == WorkspaceInput.MICROPHONE) deviceId else null)
    }

    val captionsActions = CaptionsActions(
        onToggleOverlay = { visible -> applyOverlay(overlaySettings.copy(visible = visible)) },
        onShowOriginal = { applyOverlay(overlaySettings.copy(showOriginal = it)) },
        onShowPendingOriginal = {
            showPendingOriginal = it
            saveUi(mapOf("overlay-pending" to it.toString()))
        },
        onOpenStages = {
            val stage = PipelineStage.entries.filter { it == PipelineStage.STT || config.selection(it) != null }.firstOrNull {
                com.sal7one.hearth.desktop.ui.language.stageSetupIssue(config, it, controller.capabilities, installations, models.library.catalog) != null
            }
            navigate(when (stage) {
                PipelineStage.STT -> DesktopScreen.SPEECH
                PipelineStage.TRANSLATION -> DesktopScreen.TRANSLATION_SETTINGS
                PipelineStage.TTS -> DesktopScreen.VOICES
                null -> DesktopScreen.MODELS
            })
        },
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
        onStart = ::startLiveCapture,
        onStop = { controller.cancel() },
        onFinishMicrophone = { controller.finishCapture() },
        onSwapLanguages = ::swapLanguages,
        onSourceLanguage = ::sourceLanguage,
        onTargetLanguage = ::targetLanguage,
        onOpenOverlay = { applyOverlay(overlaySettings.copy(visible = true)); navigate(DesktopScreen.OVERLAY) },
        onPickSystemAudio = { pickCaptureSource(WorkspaceInput.SYSTEM_AUDIO) },
        onToggleTranslate = {
            if (config.translation == null) navigate(DesktopScreen.TRANSLATION_SETTINGS)
            else controller.updateConfig { it.copy(translation = null) }
        },
        onToggleVoice = {
            if (config.voice == null) navigate(DesktopScreen.VOICES)
            else controller.updateConfig { it.copy(voice = null) }
        },
        onPickMicrophone = { pickCaptureSource(WorkspaceInput.MICROPHONE) },
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
        onRetry = { controller.retry() },
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
            activity = {
                com.sal7one.hearth.desktop.ui.shell.ModelActivityBar(
                    library, models.library.catalog,
                    onOpenDownloads = { navigate(DesktopScreen.DOWNLOADS) },
                    onDismiss = models::dismissNotice,
                )
                if (workspace.active && config != activeConfig) {
                    com.sal7one.hearth.desktop.ui.components.Banner(
                        "Settings saved for the next session. Stop and restart live captions to apply them.",
                        tone = com.sal7one.hearth.desktop.ui.components.BannerTone.Neutral,
                    )
                }
            },
        ) {
            when (screen) {
                DesktopScreen.HOME -> HomeScreen(
                    state = home,
                    onStart = ::startLiveCapture,
                    onSwapLanguages = ::swapLanguages,
                    onSourceLanguage = ::sourceLanguage,
                    onTargetLanguage = ::targetLanguage,
                    onOpenSetup = { navigate(when (home.setupStage) {
                        PipelineStage.TRANSLATION -> DesktopScreen.TRANSLATION_SETTINGS
                        PipelineStage.TTS -> DesktopScreen.VOICES
                        else -> DesktopScreen.SPEECH
                    }) },
                    onOpen = ::navigate,
                    onOpenWorkspace = { navigate(DesktopScreen.CAPTIONS) },
                )
                DesktopScreen.CONVERSATION -> ConversationScreen()
                DesktopScreen.CAMERA -> CameraScreen()
                DesktopScreen.READING -> ReadingScreen()
                DesktopScreen.INTEGRATIONS -> IntegrationsScreen(state = integrations, actions = integrationsActions)
                DesktopScreen.TRANSLATE -> TranslateScreen(
                    state = translate,
                    draft = translationDraft,
                    onDraftChange = { translationDraft = it },
                    actions = TranslateActions(
                        onTranslate = { text ->
                            if (text.isNotBlank() && translate.ready) controller.start(WorkspaceInput.TEXT, text, "", null)
                            else failure(translate.readinessIssue ?: translate.languages.issue ?: "Configure translation first")
                        },
                        onSwapLanguages = ::swapLanguages,
                        onSourceLanguage = ::sourceLanguage,
                        onTargetLanguage = ::targetLanguage,
                        onOpenSetup = {
                            navigate(if (com.sal7one.hearth.desktop.ui.language.stageSetupIssue(config, PipelineStage.TRANSLATION,
                                controller.capabilities, installations, models.library.catalog) == null && config.voice != null)
                                DesktopScreen.VOICES else DesktopScreen.TRANSLATION_SETTINGS)
                        },
                        onOpenCaptions = { navigate(DesktopScreen.CAPTIONS) },
                        onCopyResult = {
                            runCatching { copyToClipboard(translate.translated) }
                                .onFailure { failure(it.message ?: it.toString()) }
                        },
                        onOpenVoice = { navigate(DesktopScreen.VOICES) },
                    ),
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
                onChange = ::applyOverlay,
                onNativeApplied = { overlayControlIssue = null },
                onNativeError = { message ->
                    overlayControlIssue = message
                    if (overlaySettings.clickThrough) applyOverlay(overlaySettings.copy(clickThrough = false))
                    controller.showError("Caption window: $message")
                },
            )
        }
    }
}

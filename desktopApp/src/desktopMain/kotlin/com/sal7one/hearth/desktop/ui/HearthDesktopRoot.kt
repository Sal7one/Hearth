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
import com.sal7one.hearth.desktop.ui.screens.models.ModelsActions
import com.sal7one.hearth.desktop.ui.screens.models.ModelsFilter
import com.sal7one.hearth.desktop.ui.screens.models.ModelsScreen
import com.sal7one.hearth.desktop.ui.screens.models.modelsState
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

    HearthTheme(preset = preset, dark = dark, reduceMotion = initialUi["reduce-motion"] == "true") {
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
                DesktopScreen.MODELS -> ModelsScreen(state = libraryState, actions = modelsActions)
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

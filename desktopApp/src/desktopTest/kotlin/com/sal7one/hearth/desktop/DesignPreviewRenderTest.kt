package com.sal7one.hearth.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.core.PipelineStage
import com.sal7one.hearth.desktop.data.DesktopModelArtifact
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.screens.appearance.AppearanceActions
import com.sal7one.hearth.desktop.ui.screens.appearance.AppearanceScreen
import com.sal7one.hearth.desktop.ui.screens.appearance.appearanceState
import com.sal7one.hearth.desktop.ui.screens.camera.CameraScreen
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionLine
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsActions
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsScreen
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsState
import com.sal7one.hearth.desktop.ui.screens.connections.ConnectionsActions
import com.sal7one.hearth.desktop.ui.screens.connections.ConnectionsScreen
import com.sal7one.hearth.desktop.ui.screens.connections.connectionsState
import com.sal7one.hearth.desktop.ui.screens.conversation.ConversationScreen
import com.sal7one.hearth.desktop.ui.screens.downloads.DownloadsActions
import com.sal7one.hearth.desktop.ui.screens.downloads.DownloadsScreen
import com.sal7one.hearth.desktop.ui.screens.downloads.downloadsState
import com.sal7one.hearth.desktop.ui.screens.files.FilesActions
import com.sal7one.hearth.desktop.ui.screens.files.FilesScreen
import com.sal7one.hearth.desktop.ui.screens.files.filesState
import com.sal7one.hearth.desktop.ui.screens.home.HomeScreen
import com.sal7one.hearth.desktop.ui.screens.home.HomeState
import com.sal7one.hearth.desktop.ui.screens.integrations.IntegrationsActions
import com.sal7one.hearth.desktop.ui.screens.integrations.IntegrationsScreen
import com.sal7one.hearth.desktop.ui.screens.integrations.IntegrationsState
import com.sal7one.hearth.desktop.ui.screens.models.ModelsActions
import com.sal7one.hearth.desktop.ui.screens.models.ModelHardwareFilter
import com.sal7one.hearth.desktop.ui.screens.models.ModelsFilter
import com.sal7one.hearth.desktop.ui.screens.models.ModelsScreen
import com.sal7one.hearth.desktop.ui.screens.models.modelsState
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayActions
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayCaptionView
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayScreen
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlaySettings
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayState
import com.sal7one.hearth.desktop.ui.screens.presets.BuiltInPresets
import com.sal7one.hearth.desktop.ui.screens.presets.PresetsActions
import com.sal7one.hearth.desktop.ui.screens.presets.PresetsScreen
import com.sal7one.hearth.desktop.ui.screens.presets.presetsState
import com.sal7one.hearth.desktop.ui.screens.reading.ReadingScreen
import com.sal7one.hearth.desktop.ui.screens.speech.SpeechActions
import com.sal7one.hearth.desktop.ui.screens.speech.SpeechScreen
import com.sal7one.hearth.desktop.ui.screens.speech.speechState
import com.sal7one.hearth.desktop.ui.screens.translate.TranslateActions
import com.sal7one.hearth.desktop.ui.screens.translate.TranslateScreen
import com.sal7one.hearth.desktop.ui.screens.translate.TranslateState
import com.sal7one.hearth.desktop.ui.screens.translation.TranslationActions
import com.sal7one.hearth.desktop.ui.screens.translation.TranslationScreen
import com.sal7one.hearth.desktop.ui.screens.translation.translationState
import com.sal7one.hearth.desktop.ui.screens.voice.VoicesActions
import com.sal7one.hearth.desktop.ui.screens.voice.VoicesScreen
import com.sal7one.hearth.desktop.ui.screens.voice.voicesState
import com.sal7one.hearth.desktop.ui.shell.AppShell
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Headless render coverage for every desktop destination and for the overlay's
 * states.
 *
 * Each scene is drawn with the real composables and a fixed view state, then the
 * encoded PNG is asserted to be non-empty, decodable, the expected size, and to
 * contain actual pixels. A screen that crashes, draws nothing, or silently renders
 * an empty frame therefore fails a normal `:desktopApp:desktopTest` run.
 *
 * The always-on-top floating Window is deliberately not composed here: opening a
 * real OS window remains an owner-only screen check. These tests never touch a
 * model, microphone, provider, network or display. Set `HEARTH_PREVIEW_DIR` to also
 * write every PNG for human review.
 *
 * The states are read directly from the screen state objects, and every action
 * bundle is the real default bundle, so no controller, provider or platform service
 * is started.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesignPreviewRenderTest {

    private val output: Path? = System.getenv("HEARTH_PREVIEW_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

    // ---------------------------------------------------------------------
    // Fixtures. These mirror what HearthDesktopRoot resolves from controllers,
    // but they are fixed values: no model, microphone, provider or disk is read.
    // ---------------------------------------------------------------------

    private val homeState = HomeState(
        sourceLanguage = "en",
        targetLanguage = "ar",
        sourceLabel = "English",
        targetLabel = "العربية",
        speechEngine = "Whisper Large v3",
        translationEngine = "EuroLLM 1.7B",
        voiceEngine = null,
        installedModels = 4,
        verifiedModels = 4,
        recognitionReady = true,
        statusText = "3 engines ready",
        statusLive = false,
    )

    private val setupHomeState = HomeState(
        sourceLanguage = "en",
        targetLanguage = "ar",
        sourceLabel = "English",
        targetLabel = "العربية",
        speechEngine = null,
        translationEngine = null,
        voiceEngine = null,
        installedModels = 0,
        verifiedModels = 0,
        recognitionReady = false,
        statusText = "Setup needed",
        statusLive = false,
    )

    private val captionsState = CaptionsState(
        sourceLabel = "English",
        targetLabel = "العربية",
        targetLanguage = "ar",
        speechEngine = "Whisper Large v3",
        translationEngine = "EuroLLM 1.7B",
        speechReady = true,
        translationEnabled = true,
        voiceEnabled = false,
        active = true,
        microphone = true,
        showOriginal = true,
        showPendingOriginal = true,
        overlayVisible = false,
        deviceName = "MacBook Pro Microphone",
        lines = listOf(
            CaptionLine(
                "a", 1, "00:04",
                "We should start with the part everyone already agreed on.",
                "لنبدأ بالجزء الذي اتفق عليه الجميع.", false,
            ),
            CaptionLine(
                "b", 2, "00:11",
                "Then we can talk about the budget, and what it costs us next year.",
                "ثم يمكننا الحديث عن الميزانية، وعن تكلفتها في العام القادم.", false,
            ),
            CaptionLine(
                "c", 3, "00:19",
                "I think the number is close enough to last quarter that nobody will object.",
                "أعتقد أن الرقم قريب بما يكفي من الربع الماضي، فلن يعترض أحد.", false,
            ),
            CaptionLine(
                "d", 4, "00:27",
                "And after that, we still have to—",
                "وبعد ذلك، ما زال علينا أن—", true,
            ),
        ),
    )

    private val captionsWithoutEngine = captionsState.copy(
        speechReady = false,
        translationEnabled = false,
        active = false,
        microphone = false,
        lines = emptyList(),
    )

    private fun card(
        id: String,
        name: String,
        stage: PipelineStage,
        gb: Double,
        languages: Set<String>,
    ) = DesktopModelCard(
        id = id,
        name = name,
        family = name.substringBefore(' '),
        stage = stage,
        engineId = when (stage) { PipelineStage.STT -> "whisper-local"; PipelineStage.TRANSLATION -> "llama-translation-local"; PipelineStage.TTS -> "supertonic-local" },
        languages = languages,
        source = "Pinned catalog",
        license = "MIT",
        note = "",
        options = emptyMap(),
        artifacts = listOf(
            DesktopModelArtifact(
                "$id.bin",
                "https://example.invalid/$id",
                (gb * 1_000_000_000).toLong(),
                "0".repeat(64),
            ),
        ),
    )

    private val previewCatalog = listOf(
        card("whisper-large-v3-turbo-q5_0", "Whisper large-v3-turbo · Q5_0", PipelineStage.STT, 1.55, setOf("multilingual")),
        card("whisper-small-q5_1", "Whisper small · Q5_1", PipelineStage.STT, 0.461, setOf("multilingual")),
        card("whisper-tiny-q5_1", "Whisper tiny · Q5_1", PipelineStage.STT, 0.075, setOf("multilingual")),
        card("eurollm-17b-q4", "EuroLLM 1.7B Instruct", PipelineStage.TRANSLATION, 1.05, setOf("en", "ar", "de")),
        card("riva-translate-4b-v2-q4", "Riva-Translate 4B Instruct v2", PipelineStage.TRANSLATION, 2.76, setOf("en", "ar")),
        card("supertonic-3", "Supertonic 3 · ten voices", PipelineStage.TTS, 0.412, setOf("ar", "en")),
    )

    private val previewInstallations = mapOf(
        "whisper-large-v3-turbo-q5_0" to DesktopModelStatus(true, Path.of("/preview/models/whisper-large-v3-turbo-q5_0")),
        "whisper-small-q5_1" to DesktopModelStatus(true, Path.of("/preview/models/whisper-small-q5_1")),
        "eurollm-17b-q4" to DesktopModelStatus(true, Path.of("/preview/models/eurollm-17b-q4")),
        "supertonic-3" to DesktopModelStatus(true, Path.of("/preview/models/supertonic-3")),
        "riva-translate-4b-v2-q4" to DesktopModelStatus(
            false,
            null,
            "Downloaded file does not match the pinned SHA-256",
        ),
    )

    private val previewTransfer = ModelTransfer("whisper-tiny-q5_1", downloaded = 40_000_000, total = 75_000_000)

    private val previewEngines = listOf(
        EngineDescriptor(
            id = "whisper-local",
            name = "Whisper.cpp",
            stage = PipelineStage.STT,
            kind = EngineKind.LOCAL,
            streaming = true,
            acceleration = setOf("cpu", "metal"),
            requiresModel = true,
        ),
        EngineDescriptor(
            id = "llama-translation-local",
            name = "Local GGUF translation",
            stage = PipelineStage.TRANSLATION,
            kind = EngineKind.LOCAL,
            acceleration = setOf("cpu", "metal"),
            requiresModel = true,
        ),
        EngineDescriptor(
            id = "openai-translation",
            name = "OpenAI-compatible translation",
            stage = PipelineStage.TRANSLATION,
            kind = EngineKind.CLOUD,
            requiresCredential = true,
        ),
        EngineDescriptor(
            id = "supertonic-local",
            name = "Supertonic 3",
            stage = PipelineStage.TTS,
            kind = EngineKind.LOCAL,
            acceleration = setOf("cpu"),
            requiresModel = true,
        ),
        EngineDescriptor(
            id = "system-tts",
            name = "Installed system voices",
            stage = PipelineStage.TTS,
            kind = EngineKind.SYSTEM,
        ),
        EngineDescriptor(
            id = "openai-stt",
            name = "OpenAI-compatible transcription",
            stage = PipelineStage.STT,
            kind = EngineKind.CLOUD,
            streaming = true,
            requiresCredential = true,
        ),
    ) + listOf(
        EngineDescriptor("qwen3-asr-local", "Qwen3-ASR 0.6B", PipelineStage.STT, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("transcribepsy-local", "TranscribePsy 250M", PipelineStage.STT, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("nemotron-asr-local", "Nemotron 3.5 ASR", PipelineStage.STT, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("moonshine-local", "Moonshine", PipelineStage.STT, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("vosk-local", "Vosk (Kaldi)", PipelineStage.STT, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("omnilingual-local", "Omnilingual CTC 300M", PipelineStage.STT, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("phonon-local", "Phonon 2", PipelineStage.STT, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("marian-local", "CTranslate2 · Marian/OPUS", PipelineStage.TRANSLATION, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("moss-tts-local", "MOSS-TTS Nano 100M", PipelineStage.TTS, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("omnivoice-tts-local", "OmniVoice (voice design)", PipelineStage.TTS, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("magpie-tts-local", "MagpieTTS Multilingual 357M", PipelineStage.TTS, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("chatterbox-tts-local", "Chatterbox Multilingual V3", PipelineStage.TTS, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("ming-local", "Ming-3.0 TTS 0.5B (local)", PipelineStage.TTS, EngineKind.LOCAL, requiresModel = true),
        EngineDescriptor("openai-tts", "OpenAI-compatible speech", PipelineStage.TTS, EngineKind.CLOUD, requiresCredential = true),
        EngineDescriptor("ming-tts", "Ming-3.0 TTS (self-hosted)", PipelineStage.TTS, EngineKind.CLOUD),
    )

    private val speechFixture = speechState(
        capabilities = previewEngines,
        config = PipelineConfig(
            speech = EngineSelection(
                engineId = "whisper-local",
                modelId = "whisper-large-v3-turbo-q5_0",
                modelPath = "/preview/models/whisper-large-v3-turbo-q5_0",
                options = mapOf("windowMs" to "4000", "acceleration" to "metal"),
            ),
            sourceLanguage = "en",
        ),
        catalog = previewCatalog,
        installations = previewInstallations,
        devices = emptyList(),
        deviceId = null,
    )

    private val translationFixture = translationState(
        capabilities = previewEngines,
        config = PipelineConfig(
            translation = EngineSelection(
                engineId = "llama-translation-local",
                modelId = "eurollm-17b-q4",
                modelPath = "/preview/models/eurollm-17b-q4",
                options = mapOf("promptMode" to "euro", "acceleration" to "metal", "threads" to "6"),
            ),
            sourceLanguage = "en",
            targetLanguage = "ar",
        ),
        catalog = previewCatalog,
        installations = previewInstallations,
    )

    private val voicesFixture = voicesState(
        capabilities = previewEngines,
        config = PipelineConfig(
            voice = EngineSelection(engineId = "supertonic-local", modelId = "supertonic-3", modelPath = "/preview/models/supertonic-3"),
            voiceId = "F2",
            speakTranslatedText = true,
        ),
        catalog = previewCatalog,
        installations = previewInstallations,
    )

    private val previewOverlay = OverlayState(
        settings = OverlaySettings(visible = true),
        caption = OverlayCaptionView(
            primary = "لنبدأ بالجزء الذي اتفق عليه الجميع.",
            original = "We should start with the part everyone agreed on.",
            partial = false,
        ),
        targetRtl = true,
        translationEnabled = true,
    )

    private val emptyOverlay = OverlayState(
        settings = OverlaySettings(visible = false),
        caption = null,
        targetRtl = true,
        translationEnabled = false,
    )

    private val connectionsFixture = connectionsState(
        capabilities = previewEngines,
        config = PipelineConfig(
            speech = EngineSelection(
                engineId = "openai-stt",
                endpoint = "https://api.openai.com/v1/audio/transcriptions",
                modelId = "gpt-4o-mini-transcribe",
                credentialId = "openai-main",
            ),
        ),
        credentialStorage = "the operating system secure store",
    )

    private val filesFixture = filesState(
        dataDirectory = Path.of("/Users/preview/Library/Application Support/Hearth"),
        catalog = previewCatalog,
        installations = previewInstallations,
        freeBytes = 214_000_000_000,
    )

    private val modelsFixture = modelsState(
        catalog = previewCatalog,
        installations = previewInstallations,
        config = PipelineConfig(),
        transfers = listOf(previewTransfer),
        filter = ModelsFilter(),
        freeBytes = 214_000_000_000,
    )

    private val presetsFixture = presetsState(
        definitions = BuiltInPresets.all,
        catalog = previewCatalog,
        installations = previewInstallations,
        config = PipelineConfig(),
    )

    private val downloadsFixture = downloadsState(
        catalog = previewCatalog,
        installations = previewInstallations,
        transfers = listOf(previewTransfer),
        freeBytes = 214_000_000_000,
    )

    private val typedConfig = PipelineConfig(
        translation = EngineSelection("llama-translation-local", "eurollm-17b-q4", "/preview/models/eurollm-17b-q4"),
        sourceLanguage = "en", targetLanguage = "ar",
    )
    private val translateFixture = com.sal7one.hearth.desktop.ui.screens.translate.translateState(
        typedConfig,
        DesktopUiState(input = WorkspaceInput.TEXT, sessionConfig = typedConfig, snapshot = com.sal7one.hearth.core.PipelineSnapshot(
            "preview-text", status = com.sal7one.hearth.core.PipelineStatus.COMPLETED,
            segments = listOf(com.sal7one.hearth.core.CaptionSegment("text-1",
                "The museum opens at nine, but the east wing stays closed until noon.",
                "يفتح المتحف في التاسعة، لكن الجناح الشرقي يبقى مغلقاً حتى الظهر.")),
        )),
        previewEngines, previewCatalog, previewInstallations,
    )

    private val integrationsLimits = listOf(
        "Concurrency" to "Four sessions",
        "Size" to "64 MiB per file",
        "Access" to "This Mac only",
    )

    private val integrationsFixture = IntegrationsState(
        running = true,
        baseUrl = "http://127.0.0.1:47821",
        overlayUrl = "http://127.0.0.1:47821/overlay#c=8f2a",
        limits = integrationsLimits,
    )

    private val stoppedIntegrations = IntegrationsState(
        running = false,
        busy = false,
        limits = integrationsLimits,
    )

    private val busyIntegrations = IntegrationsState(
        running = false,
        busy = true,
        limits = integrationsLimits,
    )

    // Empty fixtures ------------------------------------------------------

    private val emptyModels = modelsState(
        catalog = emptyList(),
        installations = emptyMap(),
        config = PipelineConfig(),
        transfers = emptyList(),
        filter = ModelsFilter(),
        freeBytes = 0,
    )

    private val filteredModels = modelsState(
        catalog = previewCatalog,
        installations = previewInstallations,
        config = PipelineConfig(),
        transfers = emptyList(),
        filter = ModelsFilter(query = "does-not-exist"),
        freeBytes = 214_000_000_000,
    )

    private val missingPresets = presetsState(
        definitions = BuiltInPresets.all,
        catalog = emptyList(),
        installations = emptyMap(),
        config = PipelineConfig(),
    )

    private val emptyDownloads = downloadsState(
        catalog = emptyList(),
        installations = emptyMap(),
        transfers = emptyList(),
        freeBytes = 0,
    )

    private val unconfiguredSpeech = speechState(
        capabilities = emptyList(),
        config = PipelineConfig(),
        catalog = emptyList(),
        installations = emptyMap(),
        devices = emptyList(),
        deviceId = null,
    )

    private val offTranslation = translationState(
        capabilities = emptyList(),
        config = PipelineConfig(),
        catalog = emptyList(),
        installations = emptyMap(),
    )

    private val offVoices = voicesState(
        capabilities = emptyList(),
        config = PipelineConfig(),
    )

    private val emptyConnections = connectionsState(
        capabilities = emptyList(),
        config = PipelineConfig(),
        credentialStorage = "the operating system secure store",
    )

    private val emptyFiles = filesState(
        dataDirectory = Path.of("/Users/preview/Library/Application Support/Hearth"),
        catalog = emptyList(),
        installations = emptyMap(),
        freeBytes = 0,
    )

    private val emptyTranslate = TranslateState(
        sourceLabel = "English",
        targetLabel = "العربية",
        translationEnabled = false,
    )

    // Error fixtures ------------------------------------------------------

    private val errorCaptions = captionsState.copy(
        active = false,
        microphone = false,
        speechReady = true,
        error = "The selected microphone is no longer connected.",
        lines = emptyList(),
    )

    private val errorTranslate = emptyTranslate.copy(
        translationEnabled = true,
        error = "The translation provider rejected the request (401).",
    )

    private val brokenModels = modelsState(
        catalog = previewCatalog.filter { it.id == "riva-translate-4b-v2-q4" },
        installations = previewInstallations.filterKeys { it == "riva-translate-4b-v2-q4" },
        config = PipelineConfig(),
        transfers = emptyList(),
        filter = ModelsFilter(),
        freeBytes = 214_000_000_000,
    )

    private val brokenDownloads = downloadsState(
        catalog = previewCatalog,
        installations = previewInstallations,
        transfers = emptyList(),
        freeBytes = 214_000_000_000,
    )

    private val unavailableSpeech = speechState(
        capabilities = previewEngines.map { engine ->
            if (engine.id == "whisper-local") {
                engine.copy(
                    available = false,
                    unavailableReason = "The native whisper helper is missing from this build.",
                )
            } else {
                engine
            }
        },
        config = PipelineConfig(
            speech = EngineSelection(
                engineId = "whisper-local",
                modelId = "whisper-large-v3-turbo-q5_0",
            ),
            sourceLanguage = "en",
        ),
        catalog = previewCatalog,
        installations = previewInstallations,
        devices = emptyList(),
        deviceId = null,
    )

    private val unavailableConnections = connectionsState(
        capabilities = previewEngines.map { engine ->
            if (engine.id == "openai-stt") {
                engine.copy(
                    available = false,
                    unavailableReason = "No credential store is available on this system.",
                )
            } else {
                engine
            }
        },
        config = PipelineConfig(
            speech = EngineSelection(
                engineId = "openai-stt",
                endpoint = "https://api.openai.com/v1/audio/transcriptions",
                modelId = "gpt-4o-mini-transcribe",
                credentialId = "openai-main",
            ),
        ),
        credentialStorage = "the operating system secure store",
    )

    // Loading fixtures ----------------------------------------------------

    private val startingCaptions = captionsState.copy(
        starting = true,
        active = false,
        microphone = false,
    )

    private val refreshingModels = modelsFixture.copy(refreshing = true)

    private val refreshingDownloads = downloadsFixture.copy(refreshing = true)

    private val workingTranslate = translateFixture.copy(busy = true)

    // ---------------------------------------------------------------------
    // Render cases
    // ---------------------------------------------------------------------

    private fun case(
        screen: DesktopScreen,
        status: String,
        height: Int,
        name: String = screen.id,
        width: Int = 1440,
        statusLive: Boolean = false,
        dark: Boolean = true,
        content: @Composable () -> Unit,
    ) = RenderCase(
        screen = screen,
        name = name,
        status = status,
        height = height,
        width = width,
        statusLive = statusLive,
        dark = dark,
        content = content,
    )

    private val representativeScreens = listOf(
        case(DesktopScreen.HOME, status = "3 engines ready", height = 1500) {
            HomeScreen(
                state = homeState,
                onStart = {},
                onSwapLanguages = {},
                onOpen = {},
                onOpenWorkspace = {},
            )
        },
        case(DesktopScreen.CAPTIONS, status = "Listening", height = 900, statusLive = true) {
            CaptionsScreen(
                state = captionsState,
                actions = CaptionsActions(),
                onOpenModels = {},
                fixedClock = "00:42",
            )
        },
        case(DesktopScreen.TRANSLATE, status = "EuroLLM ready", height = 900) {
            TranslateScreen(state = translateFixture, actions = TranslateActions())
        },
        case(DesktopScreen.OVERLAY, status = "Overlay on", height = 1000, statusLive = true) {
            OverlayScreen(state = previewOverlay, actions = OverlayActions())
        },
        case(DesktopScreen.MODELS, status = "3 installed", height = 1100) {
            ModelsScreen(state = modelsFixture, actions = ModelsActions())
        },
        case(DesktopScreen.PRESETS, status = "4 installed", height = 1200) {
            PresetsScreen(state = presetsFixture, actions = PresetsActions())
        },
        case(DesktopScreen.DOWNLOADS, status = "1 downloading", height = 1150, statusLive = true) {
            DownloadsScreen(state = downloadsFixture, actions = DownloadsActions())
        },
        case(DesktopScreen.INTEGRATIONS, status = "Server running", height = 1150, statusLive = true) {
            IntegrationsScreen(state = integrationsFixture, actions = IntegrationsActions())
        },
        case(DesktopScreen.SPEECH, status = "Whisper ready", height = 1250) {
            SpeechScreen(state = speechFixture, actions = SpeechActions())
        },
        case(DesktopScreen.TRANSLATION_SETTINGS, status = "EuroLLM ready", height = 1250) {
            TranslationScreen(state = translationFixture, actions = TranslationActions())
        },
        case(DesktopScreen.VOICES, status = "Supertonic ready", height = 1250) {
            VoicesScreen(state = voicesFixture, actions = VoicesActions())
        },
        case(DesktopScreen.CONNECTIONS, status = "1 connected", height = 1100) {
            ConnectionsScreen(state = connectionsFixture, actions = ConnectionsActions())
        },
        case(DesktopScreen.FILES, status = "3.3 GB used", height = 1000) {
            FilesScreen(state = filesFixture, actions = FilesActions())
        },
        case(DesktopScreen.APPEARANCE, status = "Saved", height = 1150) {
            AppearanceScreen(
                state = appearanceState(AccentPreset.INK, dark = true, reduceMotion = false),
                overlay = previewOverlay,
                actions = AppearanceActions(),
                storageLabel = modelsFixture.storageLabel,
                freeLabel = modelsFixture.freeDiskLabel,
            )
        },
        case(DesktopScreen.CONVERSATION, status = "On your phone", height = 1000) {
            ConversationScreen()
        },
        case(DesktopScreen.CAMERA, status = "On your phone", height = 1000) {
            CameraScreen()
        },
        case(DesktopScreen.READING, status = "On your phone", height = 1000) {
            ReadingScreen()
        },
    )

    private val emptyStates = listOf(
        case(DesktopScreen.HOME, name = "home-setup", status = "Setup needed", height = 1500) {
            HomeScreen(
                state = setupHomeState,
                onStart = {},
                onSwapLanguages = {},
                onOpen = {},
                onOpenWorkspace = {},
            )
        },
        case(DesktopScreen.CAPTIONS, name = "captions-empty", status = "Not configured", height = 900) {
            CaptionsScreen(
                state = captionsWithoutEngine,
                actions = CaptionsActions(),
                onOpenModels = {},
                fixedClock = "00:00",
            )
        },
        case(DesktopScreen.TRANSLATE, name = "translate-empty", status = "Ready", height = 900) {
            TranslateScreen(state = emptyTranslate, actions = TranslateActions())
        },
        case(DesktopScreen.OVERLAY, name = "overlay-empty", status = "Overlay off", height = 1000) {
            OverlayScreen(state = emptyOverlay, actions = OverlayActions())
        },
        case(DesktopScreen.MODELS, name = "models-empty", status = "Library empty", height = 1100) {
            ModelsScreen(state = emptyModels, actions = ModelsActions())
        },
        case(DesktopScreen.MODELS, name = "models-filter-empty", status = "No matches", height = 1100) {
            ModelsScreen(state = filteredModels, actions = ModelsActions())
        },
        case(DesktopScreen.PRESETS, name = "presets-missing", status = "No models", height = 1200) {
            PresetsScreen(state = missingPresets, actions = PresetsActions())
        },
        case(DesktopScreen.DOWNLOADS, name = "downloads-empty", status = "No transfers", height = 1150) {
            DownloadsScreen(state = emptyDownloads, actions = DownloadsActions())
        },
        case(DesktopScreen.INTEGRATIONS, name = "integrations-stopped", status = "Server stopped", height = 1150) {
            IntegrationsScreen(state = stoppedIntegrations, actions = IntegrationsActions())
        },
        case(DesktopScreen.SPEECH, name = "speech-unconfigured", status = "Not configured", height = 1250) {
            SpeechScreen(state = unconfiguredSpeech, actions = SpeechActions())
        },
        case(
            DesktopScreen.TRANSLATION_SETTINGS,
            name = "translation-off",
            status = "Translation off",
            height = 1250,
        ) {
            TranslationScreen(state = offTranslation, actions = TranslationActions())
        },
        case(DesktopScreen.VOICES, name = "voices-off", status = "Voice off", height = 1250) {
            VoicesScreen(state = offVoices, actions = VoicesActions())
        },
        case(DesktopScreen.CONNECTIONS, name = "connections-empty", status = "On device", height = 1100) {
            ConnectionsScreen(state = emptyConnections, actions = ConnectionsActions())
        },
        case(DesktopScreen.FILES, name = "files-empty", status = "0 MB used", height = 1000) {
            FilesScreen(state = emptyFiles, actions = FilesActions())
        },
    )

    private val errorStates = listOf(
        case(DesktopScreen.CAPTIONS, name = "captions-error", status = "Needs attention", height = 900) {
            CaptionsScreen(
                state = errorCaptions,
                actions = CaptionsActions(),
                onOpenModels = {},
                fixedClock = "00:00",
            )
        },
        case(DesktopScreen.TRANSLATE, name = "translate-error", status = "Needs attention", height = 900) {
            TranslateScreen(state = errorTranslate, actions = TranslateActions())
        },
        case(DesktopScreen.MODELS, name = "models-broken", status = "1 needs repair", height = 1100) {
            ModelsScreen(state = brokenModels, actions = ModelsActions())
        },
        case(DesktopScreen.DOWNLOADS, name = "downloads-broken", status = "1 needs repair", height = 1150) {
            DownloadsScreen(state = brokenDownloads, actions = DownloadsActions())
        },
        case(DesktopScreen.SPEECH, name = "speech-unavailable", status = "Needs attention", height = 1250) {
            SpeechScreen(state = unavailableSpeech, actions = SpeechActions())
        },
        case(
            DesktopScreen.CONNECTIONS,
            name = "connections-unavailable",
            status = "Needs attention",
            height = 1100,
        ) {
            ConnectionsScreen(state = unavailableConnections, actions = ConnectionsActions())
        },
    )

    private val loadingStates = listOf(
        case(DesktopScreen.CAPTIONS, name = "captions-starting", status = "Starting", height = 900, statusLive = true) {
            CaptionsScreen(
                state = startingCaptions,
                actions = CaptionsActions(),
                onOpenModels = {},
                fixedClock = "00:00",
            )
        },
        case(DesktopScreen.MODELS, name = "models-refreshing", status = "Refreshing", height = 1100) {
            ModelsScreen(state = refreshingModels, actions = ModelsActions())
        },
        case(DesktopScreen.DOWNLOADS, name = "downloads-refreshing", status = "Rechecking", height = 1150) {
            DownloadsScreen(state = refreshingDownloads, actions = DownloadsActions())
        },
        case(DesktopScreen.INTEGRATIONS, name = "integrations-starting", status = "Starting", height = 1150, statusLive = true) {
            IntegrationsScreen(state = busyIntegrations, actions = IntegrationsActions())
        },
        case(DesktopScreen.TRANSLATE, name = "translate-working", status = "Working", height = 900, statusLive = true) {
            TranslateScreen(state = workingTranslate, actions = TranslateActions())
        },
    )

    // ---------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------

    @Test
    fun everyDesktopScreenRendersHeadless() {
        assertEquals(
            DesktopScreen.entries.toSet(),
            representativeScreens.map { it.screen }.toSet(),
            "Every DesktopScreen needs a representative render fixture",
        )
        renderAll("representative", representativeScreens)
    }

    @Test
    fun gpuModelFiltersRenderAtNarrowWidth() {
        for (hardware in listOf(ModelHardwareFilter.AVAILABLE, ModelHardwareFilter.CUDA, ModelHardwareFilter.CPU_ONLY)) {
            val state = modelsState(previewCatalog, previewInstallations, PipelineConfig(), emptyList(),
                ModelsFilter(hardware = hardware), 214_000_000_000, engines = previewEngines)
            case(DesktopScreen.MODELS, name = "models-hardware-${hardware.key}", status = "Ready", width = 1100, height = 1150) {
                ModelsScreen(state, ModelsActions())
            }.render()
        }
    }

    @Test
    fun emptyStatesRenderHeadless() {
        renderAll("empty", emptyStates)
    }

    @Test
    fun errorStatesRenderHeadless() {
        renderAll("error", errorStates)
    }

    @Test
    fun loadingStatesRenderHeadless() {
        renderAll("loading", loadingStates)
    }

    @Test
    fun shellHomeAndOverlayRenderInBothBrightnesses() {
        val home = representativeScreens.single { it.screen == DesktopScreen.HOME }
        val overlay = representativeScreens.single { it.screen == DesktopScreen.OVERLAY }

        val darkHome = home.copy(name = "home-dark", dark = true).render()
        val lightHome = home.copy(name = "home-light", dark = false).render()
        val darkOverlay = overlay.copy(name = "overlay-dark", dark = true).render()
        val lightOverlay = overlay.copy(name = "overlay-light", dark = false).render()

        assertFalse(darkHome.contentEquals(lightHome), "The shell/home rendered identically in dark and light")
        assertFalse(
            darkOverlay.contentEquals(lightOverlay),
            "The overlay page rendered identically in dark and light",
        )
        listOf("home-dark" to darkHome, "home-light" to lightHome).forEach { (name, bytes) ->
            assertTrue(bytes.size > 1_000, "$name PNG is suspiciously small (${bytes.size} bytes)")
        }
        listOf("overlay-dark" to darkOverlay, "overlay-light" to lightOverlay).forEach { (name, bytes) ->
            assertTrue(bytes.size > 1_000, "$name PNG is suspiciously small (${bytes.size} bytes)")
        }
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private data class RenderCase(
        val screen: DesktopScreen,
        val name: String,
        val status: String,
        val height: Int,
        val width: Int = 1440,
        val statusLive: Boolean = false,
        val dark: Boolean = true,
        val content: @Composable () -> Unit,
    )

    private fun RenderCase.render(): ByteArray = rasterize(name, width, height) {
        HearthTheme(preset = AccentPreset.INK, dark = dark) {
            AppShell(
                current = screen,
                status = status,
                statusLive = statusLive,
                dark = dark,
                onToggleTheme = {},
                onNavigate = {},
            ) { content() }
        }
    }

    private fun renderAll(kind: String, cases: List<RenderCase>) {
        assertTrue(cases.isNotEmpty(), "No $kind render cases were defined")
        val failures = mutableListOf<String>()
        cases.forEach { case ->
            try {
                case.render()
            } catch (t: Throwable) {
                failures += "${case.screen.title} (${case.name}): ${t.stackTraceToString()}"
            }
        }
        assertTrue(
            failures.isEmpty(),
            "Failed to render ${failures.size} of ${cases.size} $kind screen(s):\n\n" +
                failures.joinToString("\n\n"),
        )
    }

    private fun rasterize(name: String, width: Int, height: Int, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) { content() }
        val bytes = try {
            repeat(2) { scene.render(System.nanoTime()).close() }
            scene.render(System.nanoTime()).use { image ->
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed for $name")
                data.bytes
            }
        } finally {
            scene.close()
        }
        verifyPng(name, width, height, bytes)
        output?.let { dir ->
            Files.createDirectories(dir)
            Files.write(dir.resolve("$name.png"), bytes)
        }
        return bytes
    }

    private fun verifyPng(name: String, width: Int, height: Int, bytes: ByteArray) {
        assertTrue(bytes.isNotEmpty(), "$name produced an empty PNG")
        assertTrue(bytes.size >= 8, "$name produced a truncated PNG")
        assertContentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            bytes.copyOfRange(0, 8),
            "$name did not produce a PNG",
        )
        val image = assertNotNull(
            ImageIO.read(ByteArrayInputStream(bytes)),
            "$name PNG could not be decoded",
        )
        assertEquals(width, image.width, "$name rendered at the wrong width")
        assertEquals(height, image.height, "$name rendered at the wrong height")

        val distinct = HashSet<Int>()
        var y = 0
        while (y < image.height && distinct.size < 24) {
            var x = 0
            while (x < image.width && distinct.size < 24) {
                distinct.add(image.getRGB(x, y))
                x += 19
            }
            y += 19
        }
        assertTrue(
            distinct.size >= 4,
            "$name is effectively blank (${distinct.size} distinct colours)",
        )
    }
}

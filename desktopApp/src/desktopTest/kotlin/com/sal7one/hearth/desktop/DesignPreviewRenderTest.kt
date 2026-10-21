package com.sal7one.hearth.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.sal7one.hearth.desktop.ui.DesktopScreen
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionLine
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsActions
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsScreen
import com.sal7one.hearth.desktop.ui.screens.captions.CaptionsState
import com.sal7one.hearth.core.PipelineConfig
import com.sal7one.hearth.desktop.data.DesktopModelArtifact
import com.sal7one.hearth.desktop.data.DesktopModelCard
import com.sal7one.hearth.desktop.data.DesktopModelStatus
import com.sal7one.hearth.desktop.ModelTransfer
import com.sal7one.hearth.desktop.ui.screens.home.HomeScreen
import com.sal7one.hearth.desktop.ui.screens.models.ModelsActions
import com.sal7one.hearth.desktop.ui.screens.models.ModelsFilter
import com.sal7one.hearth.desktop.ui.screens.models.ModelsScreen
import com.sal7one.hearth.desktop.ui.screens.models.modelsState
import com.sal7one.hearth.core.EngineDescriptor
import com.sal7one.hearth.core.EngineKind
import com.sal7one.hearth.core.EngineSelection
import com.sal7one.hearth.core.PipelineStage
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
import com.sal7one.hearth.desktop.ui.screens.integrations.IntegrationsState
import com.sal7one.hearth.desktop.ui.screens.translate.TranslateScreen
import com.sal7one.hearth.desktop.ui.screens.translate.TranslateState
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayCaptionView
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlaySettings
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayState
import com.sal7one.hearth.desktop.ui.screens.presets.BuiltInPresets
import com.sal7one.hearth.desktop.ui.screens.presets.PresetsActions
import com.sal7one.hearth.desktop.ui.screens.presets.PresetsScreen
import com.sal7one.hearth.desktop.ui.screens.presets.presetsState
import com.sal7one.hearth.desktop.ui.screens.downloads.DownloadsActions
import com.sal7one.hearth.desktop.ui.screens.downloads.DownloadsScreen
import com.sal7one.hearth.desktop.ui.screens.downloads.downloadsState
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayActions
import com.sal7one.hearth.desktop.ui.screens.overlay.OverlayScreen
import com.sal7one.hearth.desktop.ui.screens.home.HomeState
import com.sal7one.hearth.desktop.ui.shell.AppShell
import com.sal7one.hearth.desktop.ui.theme.AccentPreset
import com.sal7one.hearth.desktop.ui.theme.HearthTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * Opt-in design QA: rasterises the real composables with a fixed state and writes
 * PNGs for review. It runs only when HEARTH_PREVIEW_DIR is set, never in a normal
 * test run, and it never touches a model, the microphone or a provider.
 */
@OptIn(ExperimentalComposeUiApi::class)
class DesignPreviewRenderTest {

    private val output: Path? = System.getenv("HEARTH_PREVIEW_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

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

    @Test
    fun renderHome() = render("home") { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.HOME,
                    status = "3 engines ready",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    HomeScreen(
                        state = homeState,
                        onStart = {},
                        onSwapLanguages = {},
                        onOpen = {},
                        onOpenWorkspace = {},
                    )
                }
            }
        }
    }

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
            CaptionLine("a", 1, "00:04", "We should start with the part everyone already agreed on.",
                "لنبدأ بالجزء الذي اتفق عليه الجميع.", false),
            CaptionLine("b", 2, "00:11", "Then we can talk about the budget, and what it costs us next year.",
                "ثم يمكننا الحديث عن الميزانية، وعن تكلفتها في العام القادم.", false),
            CaptionLine("c", 3, "00:19", "I think the number is close enough to last quarter that nobody will object.",
                "أعتقد أن الرقم قريب بما يكفي من الربع الماضي، فلن يعترض أحد.", false),
            CaptionLine("d", 4, "00:27", "And after that, we still have to—", "وبعد ذلك، ما زال علينا أن—", true),
        ),
    )

    @Test
    fun renderCaptions() = render("captions", height = 900) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.CAPTIONS,
                    status = "Listening",
                    statusLive = true,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    CaptionsScreen(
                        state = captionsState,
                        actions = CaptionsActions(),
                        onOpenModels = {},
                        fixedClock = "00:42",
                    )
                }
            }
        }
    }

    @Test
    fun renderOverlay() = render("overlay", height = 1000) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.OVERLAY,
                    status = "Overlay on",
                    statusLive = true,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    OverlayScreen(
                        state = OverlayState(
                            settings = OverlaySettings(visible = true),
                            caption = OverlayCaptionView(
                                primary = "لنبدأ بالجزء الذي اتفق عليه الجميع.",
                                original = "We should start with the part everyone agreed on.",
                                partial = false,
                            ),
                            targetRtl = true,
                            translationEnabled = true,
                        ),
                        actions = OverlayActions(),
                    )
                }
            }
        }
    }

    private fun card(
        id: String,
        name: String,
        stage: com.sal7one.hearth.core.PipelineStage,
        gb: Double,
        languages: Set<String>,
    ) = DesktopModelCard(
        id = id,
        name = name,
        family = name.substringBefore(' '),
        stage = stage,
        engineId = "${'$'}{stage.name.lowercase()}-local",
        languages = languages,
        source = "Pinned catalog",
        license = "MIT",
        note = "",
        options = emptyMap(),
        artifacts = listOf(DesktopModelArtifact("${'$'}id.bin", "https://example.invalid/${'$'}id", (gb * 1_000_000_000).toLong(), "0".repeat(64))),
    )

    private val previewCatalog = listOf(
        card("whisper-large-v3-turbo-q5_0", "Whisper large-v3-turbo · Q5_0", com.sal7one.hearth.core.PipelineStage.STT, 1.55, setOf("multilingual")),
        card("whisper-small-q5_1", "Whisper small · Q5_1", com.sal7one.hearth.core.PipelineStage.STT, 0.461, setOf("multilingual")),
        card("whisper-tiny-q5_1", "Whisper tiny · Q5_1", com.sal7one.hearth.core.PipelineStage.STT, 0.075, setOf("multilingual")),
        card("eurollm-17b-q4", "EuroLLM 1.7B Instruct", com.sal7one.hearth.core.PipelineStage.TRANSLATION, 1.05, setOf("en", "ar", "de")),
        card("riva-translate-4b-v2-q4", "Riva-Translate 4B Instruct v2", com.sal7one.hearth.core.PipelineStage.TRANSLATION, 2.76, setOf("en", "ar")),
        card("supertonic-3", "Supertonic 3 · ten voices", com.sal7one.hearth.core.PipelineStage.TTS, 0.412, setOf("ar", "en")),
    )

    private val previewInstallations = mapOf(
        "whisper-large-v3-turbo-q5_0" to DesktopModelStatus(true, null),
        "whisper-small-q5_1" to DesktopModelStatus(true, null),
        "eurollm-17b-q4" to DesktopModelStatus(true, null),
        "supertonic-3" to DesktopModelStatus(true, null),
        "riva-translate-4b-v2-q4" to DesktopModelStatus(false, null, "Downloaded file does not match the pinned SHA-256"),
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
    )

    private val speechFixture = speechState(
        capabilities = previewEngines,
        config = PipelineConfig(
            speech = EngineSelection(
                engineId = "whisper-local",
                modelId = "whisper-large-v3-turbo-q5_0",
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
            voice = EngineSelection(engineId = "supertonic-local", modelId = "supertonic-3"),
            voiceId = "F2",
            speakTranslatedText = true,
        ),
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
        dataDirectory = java.nio.file.Path.of("/Users/preview/Library/Application Support/Hearth"),
        catalog = previewCatalog,
        installations = previewInstallations,
        freeBytes = 214_000_000_000,
    )

    private val modelsFixture = modelsState(
        catalog = previewCatalog,
        installations = previewInstallations,
        config = PipelineConfig(),
        transfer = previewTransfer,
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
        transfer = previewTransfer,
        freeBytes = 214_000_000_000,
    )

    @Test
    fun renderModels() = render("models", height = 1100) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.MODELS,
                    status = "3 installed",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    ModelsScreen(state = modelsFixture, actions = ModelsActions())
                }
            }
        }
    }

    @Test
    fun renderPresets() = render("presets", height = 1200) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.PRESETS,
                    status = "4 installed",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    PresetsScreen(state = presetsFixture, actions = PresetsActions())
                }
            }
        }
    }

    @Test
    fun renderDownloads() = render("downloads", height = 1150) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.DOWNLOADS,
                    status = "1 downloading",
                    statusLive = true,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    DownloadsScreen(state = downloadsFixture, actions = DownloadsActions())
                }
            }
        }
    }

    @Test
    fun renderSpeech() = render("speech", height = 1250) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.SPEECH,
                    status = "Whisper ready",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    SpeechScreen(state = speechFixture, actions = SpeechActions())
                }
            }
        }
    }

    @Test
    fun renderTranslation() = render("translation", height = 1250) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.TRANSLATION_SETTINGS,
                    status = "EuroLLM ready",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    TranslationScreen(state = translationFixture, actions = TranslationActions())
                }
            }
        }
    }

    @Test
    fun renderVoices() = render("voices", height = 1250) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.VOICES,
                    status = "Supertonic ready",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    VoicesScreen(state = voicesFixture, actions = VoicesActions())
                }
            }
        }
    }

    @Test
    fun renderAppearance() = render("appearance", height = 1150) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.APPEARANCE,
                    status = "Saved",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    AppearanceScreen(
                        state = appearanceState(AccentPreset.INK, dark = true, reduceMotion = false),
                        overlay = previewOverlay,
                        actions = AppearanceActions(),
                        storageLabel = modelsFixture.storageLabel,
                        freeLabel = modelsFixture.freeDiskLabel,
                    )
                }
            }
        }
    }

    @Test
    fun renderFiles() = render("files", height = 1000) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.FILES,
                    status = "3.3 GB used",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    FilesScreen(state = filesFixture, actions = FilesActions())
                }
            }
        }
    }

    @Test
    fun renderConnections() = render("connections", height = 1100) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.CONNECTIONS,
                    status = "1 connected",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    ConnectionsScreen(state = connectionsFixture, actions = ConnectionsActions())
                }
            }
        }
    }

    @Test
    fun renderIntegrations() = render("integrations", height = 1150) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.INTEGRATIONS,
                    status = "Server running",
                    statusLive = true,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    IntegrationsScreen(
                        state = IntegrationsState(
                            running = true,
                            baseUrl = "http://127.0.0.1:47821",
                            overlayUrl = "http://127.0.0.1:47821/overlay#c=8f2a",
                            limits = listOf(
                                "Concurrency" to "Four sessions",
                                "Size" to "64 MiB per file",
                                "Access" to "This Mac only",
                            ),
                        ),
                        actions = IntegrationsActions(),
                    )
                }
            }
        }
    }

    @Test
    fun renderTranslate() = render("translate", height = 900) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.TRANSLATE,
                    status = "EuroLLM ready",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) {
                    TranslateScreen(
                        state = TranslateState(
                            sourceLabel = "English",
                            targetLabel = "العربية",
                            translationEnabled = true,
                            translated = "يفتح المتحف في التاسعة، لكن الجناح الشرقي يبقى مغلقاً حتى الظهر.",
                            original = "The museum opens at nine, but the east wing stays closed until noon.",
                        ),
                        onTranslate = {},
                        onSwapLanguages = {},
                        onCopyResult = {},
                        onOpenVoice = {},
                    )
                }
            }
        }
    }

    @Test
    fun renderCompanions() = render("companions", height = 1000) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.CONVERSATION,
                    status = "On your phone",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) { ConversationScreen() }
            }
        }
    }

    @Test
    fun renderCamera() = render("camera", height = 1000) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.CAMERA,
                    status = "On your phone",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) { CameraScreen() }
            }
        }
    }

    @Test
    fun renderReading() = render("reading", height = 1000) { width, height ->
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            HearthTheme(preset = AccentPreset.INK, dark = true) {
                AppShell(
                    current = DesktopScreen.READING,
                    status = "On your phone",
                    statusLive = false,
                    dark = true,
                    onToggleTheme = {},
                    onNavigate = {},
                ) { ReadingScreen() }
            }
        }
    }

    private fun render(name: String, width: Int = 1440, height: Int = 1500, content: (Int, Int) -> ImageComposeScene) {
        val dir = output ?: return
        Files.createDirectories(dir)
        val scene = content(width, height)
        try {
            repeat(4) { scene.render(System.nanoTime()).close() }
            scene.render(System.nanoTime()).use { image ->
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
                Files.write(dir.resolve("$name.png"), data.bytes)
            }
        } finally {
            scene.close()
        }
    }
}

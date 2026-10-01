package com.sal7one.transiber.caption

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the caption engine does with recognized speech.
 *
 * CAPTIONS keeps the original language (CC for deaf/hard-of-hearing users).
 * TRANSLATE targets another language: English is served by Whisper's built-in
 * translation task (any supported input language transcribed directly as
 * English, on-device); other targets — Arabic first — caption in the
 * original language and translate finalized utterances through the
 * [TranslationLayer] on-device pipeline.
 */
enum class CaptionMode(val label: String) {
    CAPTIONS("Captions (original language)"),
    TRANSLATE("Translate"),
}

/** Where the audio comes from. */
enum class CaptionSource(val label: String, val explanation: String) {
    PLAYBACK_CAPTURE(
        "Device audio",
        "Captures what the phone is playing. Only apps that allow capture are included; " +
            "Protected audio and apps that opt out are silent. " +
            "Works fully offline.",
    ),
    MIC(
        "Microphone",
        "Listens through the microphone near the speaker. Works with any app " +
            "including ones that block device-audio capture, at reduced accuracy.",
    ),
}

enum class CaptionEngineChoice(val label: String, val explanation: String) {
    VOSK(
        "Vosk (fast)",
        "Instant partial captions in the model's language. Best responsiveness; " +
            "one model per language.",
    ),
    WHISPER(
        "Whisper (accurate)",
        "Multilingual with live language detection; slower partials. " +
            "Includes built-in English translation.",
    ),
    MOONSHINE("Moonshine · English", "Small English-only recognition in short utterance windows. Optional local translation."),
    QWEN(
        "Qwen3-ASR (local)",
        "Offline multilingual captions from a Qwen model package. Final text arrives in utterance windows; " +
            "automatic language detection. Original-language CC; optional local translation bridge.",
    ),
    OMNILINGUAL(
        "Omnilingual CTC 300M (local)",
        "Compact multilingual captions in short utterance windows. No language-forcing input; selecting a spoken language declares it for downstream translation.",
    ),
    PHONON(
        "Phonon 2 (local)",
        "English-only parakeet-tdt transducer captions in utterance windows, from a converted Phonon 2 ONNX package. " +
            "Original-language CC; optional local translation bridge.",
    ),
    NEMOTRON(
        "Nemotron 3.5 (local)",
        "Offline multilingual captions with streaming partials from a Nemotron GGUF package. " +
            "Original-language CC; optional local translation bridge. Phone speed depends on the device.",
    ),
    /**
     * NETWORK CODE (BYOK) — play distribution only; hidden in the FOSS
     * build (ByokPolicy.cloudEngineAvailable()) and never constructed
     * there. See byok/ByokPolicy.kt for the offline-first contract.
     */
    CLOUD(
        "Cloud · BYOK",
        "OpenAI-compatible Whisper API with your own key: top accuracy, " +
            "any language, needs network (play distribution only).",
    ),
}

enum class CaptionTheme(val label: String) {
    DARK("Dark"),
    HIGH_CONTRAST("High contrast"),
    LIGHT("Light"),
    SUBTLE("Subtle"),
}

/**
 * Voice used to speak finalized caption lines aloud.
 *
 * SYSTEM — the device's offline TTS engine (works everywhere, language
 *   coverage depends on installed voices).
 * NATIVE — this app's on-device neural TTS (TtsKit: Kokoro/sherpa-onnx,
 *   needs an imported ONNX voice model; high quality, fully offline).
 * CLOUD — NETWORK CODE (BYOK, play distribution only — byok/CloudTtsSpeaker).
 */
enum class CaptionSpeakerChoice(val label: String, val explanation: String) {
    SYSTEM("Device voice", "Instant, offline, any language the device has a voice for."),
    CUSTOM("Custom default", "Use your saved Supertonic or self-hosted voice. Set it in Voices & read aloud."),
    NATIVE("Supertonic 3", "On-device speech in 31 languages; install its model and a voice in Voice settings."),
    SHARED("Shared voice settings", "Use the Android, on-device or self-hosted voice selected in Hearth Voice settings."),
    CLOUD("Cloud · BYOK", "Provider TTS with your API key (play distribution only)."),
}

/** Preferred voice gender for read-aloud; engines apply it where supported. */
enum class SpeakerGender(val label: String) {
    ANY("Follow voice choice"), FEMALE("Female"), MALE("Male"),
}

enum class CaptionAnchor(val label: String) {
    TOP("Top"),
    CENTER("Center"),
    BOTTOM("Bottom"),
}

enum class CaptionLanguagePicker { SOURCE, TARGET }

enum class CaptionFontScale(val label: String, val multiplier: Float) {
    SMALL("Small", 0.8f),
    NORMAL("Normal", 1.0f),
    LARGE("Large", 1.3f),
    HUGE("Huge", 1.7f);

    /** One step larger, saturating at HUGE (Android TV 10-foot boost). */
    fun boosted(): CaptionFontScale =
        CaptionFontScale.entries.getOrNull(ordinal + 1) ?: this
}

/**
 * Everything the overlay and capture pipeline can be tuned with. Persisted in
 * DataStore so a session resumes exactly as the user left it.
 *
 * [theme] is the user's own style choice and is the one field caption presets
 * must leave alone; they restyle the bubble through [withShape] instead.
 */
data class CaptionOverlayConfig(
    val mode: CaptionMode = CaptionMode.CAPTIONS,
    val source: CaptionSource = CaptionSource.PLAYBACK_CAPTURE,
    val engine: CaptionEngineChoice = CaptionEngineChoice.WHISPER,
    val modelId: String = "",
    // Explicit connection selection must restart even when the engine remains CLOUD.
    val speechSelectionRevision: Long = 0,
    // Optional local text stage, independently switchable from speech recognition.
    val localTranslationEnabled: Boolean = false,
    val localTranslationModelId: String = "",
    // Empty keeps the existing speech-provider/legacy route; otherwise an explicit text translator.
    val textTranslationProviderId: String = "",
    // Translation
    val target: TranslationTarget = TranslationTarget.ENGLISH,
    // Spoken-language preference; runtime capabilities decide whether it can be sent as a hint.
    val streamLanguage: String = "auto",
    val display: CaptionDisplay = CaptionDisplay.BOTH,
    // Layout
    val anchor: CaptionAnchor = CaptionAnchor.BOTTOM,
    val widthPercent: Int = OverlayGeometryDefaults.PORTRAIT.widthPercent,   // 40..100 of screen width
    val bubbleHeightDp: Int? = null,     // null preserves the pre-upgrade reading height
    val maxHeightPercent: Int = OverlayGeometryDefaults.PORTRAIT.maxHeightPercent,  // 20..60 of screen height; scroll beyond
    val xOffsetPx: Int = 0,              // manual drag offset from anchor
    val yOffsetPx: Int = 0,
    // Text
    val fontScale: CaptionFontScale = CaptionFontScale.NORMAL,
    val historyLines: Int = CaptionReading.DEFAULT_PREVIOUS_LINES, // previous finals: 0..8
    val showPartial: Boolean = true,     // live (unconfirmed) text
    // Style
    /**
     * The user's own light/dark choice — persisted across restarts and owned
     * by the user. Presets restyle the bubble's shape via [withShape] and must
     * never change [theme]; only an explicit settings-panel selection does.
     */
    val theme: CaptionTheme = CaptionTheme.DARK,
    val backgroundOpacity: Int = 70,     // 0..100
    // Behaviour
    val tapThrough: Boolean = false,     // overlay ignores all touches
    val paused: Boolean = false,         // discard captured audio; suspend speech processing
    // Speak finalized lines aloud (captions mode: the original text;
    // translate mode: the translation once it lands).
    val speakCaptions: Boolean = false,
    val speakerChoice: CaptionSpeakerChoice = CaptionSpeakerChoice.SYSTEM,
    val speakerGender: SpeakerGender = SpeakerGender.ANY,
    /** Read-aloud loudness 0..100 percent of the engine's normal output. */
    val speakerVolume: Int = 100,
    // Note: paused is deliberately NOT persisted (see CaptionConfigStore): a
    // fresh overlay session always starts running; pausing is a live gesture.
    // Transient UI state (never persisted; survives via the in-memory flow)
    val showSettings: Boolean = false,
    val languagePicker: CaptionLanguagePicker? = null,
) {
    val effectiveEngine: CaptionEngineChoice
        get() = engine

    /**
     * True when translation mode's output language equals the caption engine's
     * own output, so captions ARE the translation with no second stage and no
     * whisper switch. Concretely: Vosk + English target is the fast path
     * (instant English captions); whisper still uses its one-pass English
     * task. Non-English targets always need the Marian stage.
     */
    val translationIsIdentity: Boolean
        get() = mode == CaptionMode.TRANSLATE &&
            target == TranslationTarget.ENGLISH &&
            engine == CaptionEngineChoice.VOSK

    /** True when rendered translation text should be right-aligned/RTL. */
    val translationRtl: Boolean
        get() = mode == CaptionMode.TRANSLATE && target.rtl

    /** Translation mode activates its selected text stage; CC never loads it.
     * Reconciles old settings where mode and the legacy bridge switch disagreed.
     * Integrated cloud and legacy Whisper routes remain selected without an override.
     */
    fun withCaptionMode(next: CaptionMode) = copy(
        mode = next,
        localTranslationEnabled = next == CaptionMode.TRANSLATE &&
            (localTranslationEnabled || (engine.speechBackend != null && localTranslationModelId.isNotBlank()) || textTranslationProviderId.isNotBlank()),
    )

    fun withUiClamp() = copy(
        widthPercent = widthPercent.coerceIn(40, 100),  // 40% is the landscape default
        speakerVolume = speakerVolume.coerceIn(0, 100),
        maxHeightPercent = maxHeightPercent.coerceIn(20, 60),
        bubbleHeightDp = bubbleHeightDp?.coerceIn(144, 600),
        historyLines = historyLines.coerceIn(0, 8),
        backgroundOpacity = backgroundOpacity.coerceIn(0, 100),
    )

    /**
     * Copies only the bubble's visual shape onto [this]: layout, text sizing
     * and background. Capture/translation semantics (mode, source, engine,
     * target, display) and the user-owned [theme] are deliberately absent, so
     * caption presets can restyle the bubble without overriding the user's
     * theme. Defaults keep the current value, matching [copy] semantics for
     * the included fields.
     */
    fun withShape(
        anchor: CaptionAnchor = this.anchor,
        widthPercent: Int = this.widthPercent,
        xOffsetPx: Int = this.xOffsetPx,
        yOffsetPx: Int = this.yOffsetPx,
        fontScale: CaptionFontScale = this.fontScale,
        historyLines: Int = this.historyLines,
        showPartial: Boolean = this.showPartial,
        backgroundOpacity: Int = this.backgroundOpacity,
    ): CaptionOverlayConfig = copy(
        anchor = anchor,
        widthPercent = widthPercent,
        xOffsetPx = xOffsetPx,
        yOffsetPx = yOffsetPx,
        fontScale = fontScale,
        historyLines = historyLines,
        showPartial = showPartial,
        backgroundOpacity = backgroundOpacity,
    )
}

private val Context.captionDataStore by preferencesDataStore(name = "caption_overlay_config")

/**
 * Reads and writes [CaptionOverlayConfig]. Every entry point (service,
 * overlay, settings) passes a Context; the store itself is stateless.
 */
object CaptionConfigStore {

    /**
     * Rotation signal for collectors that outlive a configuration change (the
     * overlay services). The caller's own resources stay authoritative: a
     * freshly collected screen always reads its current orientation here, so
     * a stale signal can never redirect someone's edits to the wrong set.
     */
    private val rotation = kotlinx.coroutines.flow.MutableStateFlow(Unit)
    fun onOrientationChanged(@Suppress("UNUSED_PARAMETER") configurationOrientation: Int) {
        rotation.value = Unit
    }

    fun config(context: Context): Flow<CaptionOverlayConfig> =
        kotlinx.coroutines.flow.combine(
            context.applicationContext.captionDataStore.data,
            rotation,
        ) { prefs, _ ->
            readFrom(prefs, OverlayOrientation.from(context.resources.configuration.orientation))
        }

    suspend fun update(
        context: Context,
        transform: (CaptionOverlayConfig) -> CaptionOverlayConfig,
    ) {
        context.applicationContext.captionDataStore.edit { prefs ->
            val active = OverlayOrientation.from(context.resources.configuration.orientation)
            val changed = transform(readFrom(prefs, active))
            val next = changed.withCaptionMode(changed.mode).withUiClamp()
            writeInto(prefs, next, active)
        }
    }

    internal fun readFrom(prefs: androidx.datastore.preferences.core.Preferences): CaptionOverlayConfig =
        readFrom(prefs, OverlayOrientation.PORTRAIT)

    /**
     * Geometry comes from this orientation's keys: portrait falls back to the
     * pre-orientation stored value (the user's tuned bubble survives upgrade);
     * landscape starts from its own default. Every other option stays global.
     */
    internal fun readFrom(prefs: androidx.datastore.preferences.core.Preferences, orientation: OverlayOrientation): CaptionOverlayConfig {
        fun geometry(active: androidx.datastore.preferences.core.Preferences.Key<Int>, legacy: androidx.datastore.preferences.core.Preferences.Key<Int>?, default: Int): Int =
            prefs[active] ?: legacy?.let { prefs[it] } ?: default
        val portrait = orientation == OverlayOrientation.PORTRAIT
        val defaults = OverlayGeometryDefaults.defaultsFor(orientation)
        return CaptionOverlayConfig(
            mode = prefs[Mode]?.let { enumOrDefault(it, CaptionMode.CAPTIONS) } ?: CaptionMode.CAPTIONS,
            source = prefs[Source]?.let { enumOrDefault(it, CaptionSource.PLAYBACK_CAPTURE) }
                ?: CaptionSource.PLAYBACK_CAPTURE,
            engine = prefs[Engine]?.let { enumOrDefault(it, CaptionEngineChoice.WHISPER) }
                ?: CaptionEngineChoice.WHISPER,
            modelId = prefs[ModelId] ?: "",
            speechSelectionRevision = prefs[SpeechSelectionRevision] ?: 0,
            textTranslationProviderId = prefs[TextTranslationProviderId] ?: "",
            localTranslationEnabled = prefs[LocalTranslationEnabled] ?: false,
            localTranslationModelId = prefs[LocalTranslationModelId] ?: "",
            target = prefs[Target]?.let(TranslationTarget::fromStored)
                ?: TranslationTarget.ENGLISH,
            streamLanguage = prefs[StreamLanguage] ?: "auto",
            display = prefs[Display]?.let { enumOrDefault(it, CaptionDisplay.BOTH) } ?: CaptionDisplay.BOTH,
            anchor = (prefs[if (portrait) AnchorPortrait else AnchorLandscape] ?: (if (portrait) prefs[Anchor] else null))
                ?.let { enumOrDefault(it, CaptionAnchor.BOTTOM) } ?: CaptionAnchor.BOTTOM,
            widthPercent = geometry(if (portrait) WidthPortrait else WidthLandscape, if (portrait) WidthPercent else null, defaults.widthPercent),
            maxHeightPercent = geometry(if (portrait) HeightPortrait else HeightLandscape, if (portrait) MaxHeightPercent else null, defaults.maxHeightPercent),
            bubbleHeightDp = (prefs[if (portrait) BubbleHeightPortrait else BubbleHeightLandscape] ?: (if (portrait) prefs[BubbleHeightDp] else null)),
            xOffsetPx = geometry(if (portrait) XOffsetPortrait else XOffsetLandscape, if (portrait) XOffset else null, 0),
            yOffsetPx = geometry(if (portrait) YOffsetPortrait else YOffsetLandscape, if (portrait) YOffset else null, 0),
            fontScale = prefs[FontScale]?.let { enumOrDefault(it, CaptionFontScale.NORMAL) }
                ?: CaptionFontScale.NORMAL,
            historyLines = prefs[HistoryLines] ?: CaptionReading.DEFAULT_PREVIOUS_LINES,
            showPartial = prefs[ShowPartial] ?: true,
            theme = prefs[Theme]?.let { enumOrDefault(it, CaptionTheme.DARK) } ?: CaptionTheme.DARK,
            backgroundOpacity = prefs[BackgroundOpacity] ?: 70,
            tapThrough = false, // Session-only opt-in, including upgrades with a saved true value.
            speakCaptions = prefs[SpeakCaptions] ?: false,
            speakerChoice = prefs[SpeakerChoice]
                ?.let { enumOrDefault(it, CaptionSpeakerChoice.SYSTEM) }
                ?: CaptionSpeakerChoice.SYSTEM,
            speakerGender = prefs[SpeakerGenderKey]?.let { enumOrDefault(it, SpeakerGender.ANY) } ?: SpeakerGender.ANY,
            speakerVolume = (prefs[SpeakerVolumeKey] ?: 100).coerceIn(0, 100),
        ).let { it.withCaptionMode(it.mode).withUiClamp() }
    }

    internal fun writeInto(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        config: CaptionOverlayConfig,
        orientation: OverlayOrientation = OverlayOrientation.PORTRAIT,
    ) {
        val portrait = orientation == OverlayOrientation.PORTRAIT
        prefs[Mode] = config.mode.name
        prefs[Source] = config.source.name
        prefs[Engine] = config.engine.name
        prefs[ModelId] = config.modelId
        prefs[SpeechSelectionRevision] = config.speechSelectionRevision
        prefs[TextTranslationProviderId] = config.textTranslationProviderId
        prefs[LocalTranslationEnabled] = config.localTranslationEnabled
        prefs[LocalTranslationModelId] = config.localTranslationModelId
        prefs[Target] = config.target.name
        prefs[StreamLanguage] = config.streamLanguage
        prefs[Display] = config.display.name
        prefs[if (portrait) AnchorPortrait else AnchorLandscape] = config.anchor.name
        prefs[if (portrait) WidthPortrait else WidthLandscape] = config.widthPercent
        prefs[if (portrait) HeightPortrait else HeightLandscape] = config.maxHeightPercent
        val bubbleKey = if (portrait) BubbleHeightPortrait else BubbleHeightLandscape
        config.bubbleHeightDp?.let { prefs[bubbleKey] = it } ?: prefs.remove(bubbleKey)
        prefs[if (portrait) XOffsetPortrait else XOffsetLandscape] = config.xOffsetPx
        prefs[if (portrait) YOffsetPortrait else YOffsetLandscape] = config.yOffsetPx
        prefs[FontScale] = config.fontScale.name
        prefs[HistoryLines] = config.historyLines
        prefs[ShowPartial] = config.showPartial
        prefs[Theme] = config.theme.name
        prefs[BackgroundOpacity] = config.backgroundOpacity
        prefs.remove(TapThrough)
        prefs[SpeakCaptions] = config.speakCaptions
        prefs[SpeakerChoice] = config.speakerChoice.name
        prefs[SpeakerGenderKey] = config.speakerGender.name
        prefs[SpeakerVolumeKey] = config.speakerVolume
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    private val TextTranslationProviderId = stringPreferencesKey("text_translation_provider_id")
    private val LocalTranslationEnabled = booleanPreferencesKey("local_translation_enabled")
    private val LocalTranslationModelId = stringPreferencesKey("local_translation_model_id")
    private val Mode = stringPreferencesKey("mode")
    private val Source = stringPreferencesKey("source")
    private val Engine = stringPreferencesKey("engine")
    private val ModelId = stringPreferencesKey("model_id")
    private val SpeechSelectionRevision = longPreferencesKey("speech_selection_revision")
    private val Target = stringPreferencesKey("translation_target")
    private val StreamLanguage = stringPreferencesKey("stream_language")
    private val Display = stringPreferencesKey("caption_display")
    private val Anchor = stringPreferencesKey("anchor")
    // Geometry is per orientation; unsuffixed keys are legacy migration sources.
    private val WidthPortrait = intPreferencesKey("width_percent_portrait_v1")
    private val WidthLandscape = intPreferencesKey("width_percent_landscape_v1")
    private val HeightPortrait = intPreferencesKey("max_height_percent_portrait_v1")
    private val HeightLandscape = intPreferencesKey("max_height_percent_landscape_v1")
    private val BubbleHeightPortrait = intPreferencesKey("bubble_height_portrait_v1")
    private val BubbleHeightLandscape = intPreferencesKey("bubble_height_landscape_v1")
    private val AnchorPortrait = stringPreferencesKey("anchor_portrait_v1")
    private val AnchorLandscape = stringPreferencesKey("anchor_landscape_v1")
    private val XOffsetPortrait = intPreferencesKey("x_offset_portrait_v1")
    private val XOffsetLandscape = intPreferencesKey("x_offset_landscape_v1")
    private val YOffsetPortrait = intPreferencesKey("y_offset_portrait_v1")
    private val YOffsetLandscape = intPreferencesKey("y_offset_landscape_v1")
    private val WidthPercent = intPreferencesKey("width_percent")
    // v2 keys: the 2026-08 UX revision changed the shipped defaults
    // (taller bubble, larger text, history off) — fresh keys so devices
    // with the old stored values pick up the new defaults instead of
    // silently keeping the stale ones. max_height v3: the bubble grew
    // again after device feedback (top lines were scrolling out).
    private val BubbleHeightDp = intPreferencesKey("bubble_height_dp")
    private val MaxHeightPercent = intPreferencesKey("max_height_percent_v3")
    private val XOffset = intPreferencesKey("x_offset_px")
    private val YOffset = intPreferencesKey("y_offset_px")
    private val FontScale = stringPreferencesKey("font_scale_v4")
    private val HistoryLines = intPreferencesKey("history_lines_v2")
    private val ShowPartial = booleanPreferencesKey("show_partial")
    private val Theme = stringPreferencesKey("theme")
    private val BackgroundOpacity = intPreferencesKey("background_opacity")
    private val TapThrough = booleanPreferencesKey("tap_through")
    private val SpeakCaptions = booleanPreferencesKey("speak_captions")
    private val SpeakerChoice = stringPreferencesKey("speaker_choice")
    private val SpeakerGenderKey = stringPreferencesKey("speaker_gender_v1")
    private val SpeakerVolumeKey = intPreferencesKey("speaker_volume_v1")
}

# Local and Cloud settings

Settings starts with **Search settings** and **Models & testing**. **Benchmark
models** is a visible, one-tap entry in both Basic and Advanced mode, followed by
downloads/imports and recommended bundles. The toolbar settings sheet also puts
the benchmark first, so it is reachable directly from Home or any feature. Local
benchmarking and model transfers are hidden on TVs; built-in device voices remain
available there. FOSS retains offline benchmarking and imports without network
delegation.

Below this is the saved caption pipeline: **Recognize speech → Translate
captions → Read captions aloud**. Each stage shows its current choice and opens its
matching editor directly. Translation and read-aloud switches are available on the
directory and toolbar sheet; preparing an engine does not start capture or playback.
Caption translation can be changed inline without changing conversation, typed-text
or camera defaults. Its switch uses the existing caption-mode policy, preserving
integrated cloud/Whisper routes as well as independent text translators.

Focused editors keep Local/Cloud tabs when both are available. The offline flavor
offers no cloud destinations or delegation. On TV, speech and translation editors
open Cloud directly; Android device voices remain available without downloaded
models. Browsing an editor never selects an engine, uploads content or starts a
download.

- **Local:** speech, translation, voices, camera/screen OCR, downloads/imports,
  and model benchmarking.
- **Cloud:** speech providers, text translation connections, and self-hosted voices.
- **App preferences:** appearance, advanced caption controls, and help are shared.
  Since 0.18.0 they are direct directory links, also reachable from the toolbar
  settings sheet; appearance and phone shortcuts have their own pages. App
  preferences appear once below the pipeline. History is a direct link; Advanced
  mode also exposes its recording preferences. Benchmarks require no collapsed
  tools section and no mode switch.

Search matches localized titles/details and common English, Arabic and Chinese
task terms (for example GPU, speed, بنشمارك and 导入). Results open the same saved
stage editor rather than selecting another engine. The query lives in the shared
DataStore under `settings-directory`; clearing it restores the full directory's
separate saved scroll position. Search never exposes an unavailable local/TV or
cloud/FOSS destination. Advanced-only caption tuning remains Advanced-only.

Settings use shared page, section and card spacing. Phone controls stack when
needed; large screens use a centered column with a maximum width of 840 dp.

Speech, translation and voice setup use the same Local/Cloud tab layout. Settings links
open the matching tab. Model management contains only local categories. A model
may still require an initial download in the connected build; “Local” describes
where inference happens, not the origin of its files.

Each tab retains its own scroll position and saveable presentation state when
switching tabs or returning from another setup page. Unsaved credentials are
in-memory only and may be cleared when leaving their editor. Keys never enter
Compose saved state. Existing encrypted stores, model locations and provider
choices are retained. Cloud caption voices in 0.23.5 snapshot the legacy voice configuration once into an independent store; see [cloud TTS](cloud-tts.md).

## Components

The `app/src/main/java/com/sal7one/transiber/settings/` directory contains:

| File | Responsibility |
| --- | --- |
| `settings_common.kt` | Tab/state/scroll container, headings, navigation cards |
| `settings_screen.kt` | Local/Cloud settings directory |
| `settings_app_preferences.kt` | Shared app preferences |
| `settings_speech_screen.kt` | Speech tabs and shared configuration state |
| `settings_speech_cloud_ui.kt` | Cloud speech connections, model selection, keys |
| `settings_speech_local_ui.kt` | Local speech engine and artifact browser |
| `settings_translate_cloud_ui.kt` | Cloud translation connection editor |
| `settings_translate_local_ui.kt` | Local translation model installation |
| `settings_ocr_local_ui.kt` | Local OCR models and imports |
| `settings_voice_local_ui.kt` | Android and Supertonic settings |
| `settings_voice_cloud_ui.kt` | Self-hosted voice connection and capabilities |
| `CloudTtsSettingsUi.kt` | Independent caption cloud providers, keys, models, voices and explicit preview |
| `settings_voice_model_card.kt` | Shared voice source/license card |
| `settings_voice_picker_ui.kt` | Shared voice/language picker |
| `SettingsDestination.kt` | Flavor-aware settings destinations |
| `SettingsDirectory.kt` | Search matching and mode/flavor/device-aware directory entries |
| `SettingsDirectoryPreferences.kt` | Persisted search query in the shared user DataStore |

`TranslationHub`, `ModelsScreen`, and `VoiceSetup` orchestrate their existing stores
and the focused editors. `TranslatorChooser` remains the shared selector used in
feature pages and service overlays. Its scoped mode shows only the settings tab's
local models or cloud connections. “Use this translator across Hearth” remains an
explicit action; switching settings tabs cannot trigger it.

The local model library no longer toggles caption CC/translation mode when selecting
or importing a text model. Caption-specific bridge controls remain in caption setup.
Voice-server setup requires an explicit “Use voice server as custom default” action;
browsing Cloud alone cannot change playback or send preview text.

Local translator pickers show installed and currently selected models first, with
search by model/language. A missing current choice remains visible for repair.
One direct model-library action opens downloads/imports; “Show models to download”
reveals the wider catalog. Applying a translator to other features remains an
explicit secondary action. Provider connection details, voice gender and technical
descriptions remain available through disclosures; Advanced mode exposes the
corresponding tuning controls. Keys stay in memory while editing, never in saved
Compose state.

Keep runtime/provider/network code in feature packages. Add a focused UI file under
settings and register the destination rather than duplicating stores or pipelines.
The simplified home shipped in 0.18.0; Classic tabs retains the earlier navigation.
See [navigation and themes](simple-home.md).

## Caption delivery tuning (per model)

Utterance-windowed speech engines (Moonshine, Qwen3-ASR, Omnilingual CTC,
Phonon 2) expose a per-model Responsiveness control in Settings → Local speech
(model details) and in the caption setup card: Standard (4 s windows — the
default), Responsive (2 s), Eager (1.2 s) presets, or Custom sliders for the
utterance window (1–15 s) and end-of-caption silence (0.2–2 s) in 20 ms steps.
Choices are saved per speech profile (DataStore), the default is marked in the
dropdown, Reset restores it, and the recognizer restarts on apply. Streaming
engines (Nemotron) and non-package engines are not affected; benchmarks always
run at the standard windows.

## Read captions aloud (TTS layer)

Settings → Speech → Local → Read captions aloud speaks each finalized caption
line straight after recognition (captions mode: the recognized language;
translate mode: the translation once it lands). It is off by default and is
independent of recognizing or displaying captions. The engine picker matches the
overlay's — Device voice (Android TTS), Supertonic 3 on-device, shared voice
settings, a self-hosted voice server, or the BYOK cloud voice (play build).
Voice gender (Follow voice choice / Female / Male) is exact on Supertonic
(F1–F5 / M1–M5 ids) and the cloud voice (nova/shimmer vs echo/onyx), mapped
best-effort on Android system voices, and ignored where a server does not
expose it. Loudness (0–100%) scales the utterance volume on every backend.
Both options also live in the overlay's settings sheet for live changes; the
speaker applies them on the next spoken line.

Since 0.23.3, the overlay gear opens **Appearance**, followed by
**CC/translation** and **Audio** as the third tab. Read-aloud on/off,
TTS loudness, voice gender preference and engine choices use the same component
as Speech settings, including when read aloud is off. CC/translation and
appearance have separate tabs. Settings and language pickers use the same fixed
height as captions, with controls scrolling inside; text never expands the
overlay over the video. Width and height preview live during a slider gesture
and save when released. Shared controls inherit the overlay's text color.

Since 0.23.4, **Reset** appears in the settings header only in portrait when the
actual overlay height is below 250 dp. It stays outside the scroll area for
compact-panel recovery and is hidden at 250 dp or above and in landscape.
It restores appearance and the active orientation's geometry, including a
bounded default height. It keeps models, language choices, translation and TTS
preferences, and the running/paused session. It does not change device volume.

The Audio tab also has **Device media volume**, which reads Android's current
media stream and changes it only after a slider gesture. It is device-wide;
it cannot set the private player gain of YouTube or another app independently.
The TTS slider adds a per-utterance gain, and changing device media volume can
also affect TTS's base level. This is not an isolated two-track mixer. Opening,
closing or restarting Hearth does not save, reapply or restore media volume.
Hardware/route changes refresh only while the Audio tab is visible; fixed-volume
devices disable the slider and actual policy/service errors are shown.

Local/system/self-hosted voice playback and cloud voice playback request
`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`. Android can temporarily lower a compatible
media app during voice output and restore it on focus release. Android chooses
the attenuation; Hearth cannot promise an exact original/TTS ratio. Speech
content, player policies, routes and OEM behavior can prevent ducking or pause
the source instead. See [Android audio focus](https://developer.android.com/media/optimize/audio-focus)
and [AudioManager volume controls](https://developer.android.com/reference/android/media/AudioManager#setStreamVolume(int,int,int)).
Real device mixing, capture quality while ducked, headset routing and fixed-volume
TV behavior remain owner device checks.

Turning read aloud off, silencing it, or stopping captions cancels queued voice
work. Cloud voice work is sequential and bounded to three waiting lines; each
line retains its own voice and volume. Provider and playback errors are shown
instead of silently losing a line. Synthesized cloud speech uses a non-media
audio usage so it is excluded from Hearth's playback capture. Microphone input
can still hear a device's physical speaker; use headphones when needed.

## Overlay geometry per orientation

Every overlay (caption bubble, reading translation bubble, quick translate)
remembers its geometry separately for portrait and landscape: width (40–100%),
max height, explicit bubble height, anchor and manual drag offsets. Rotating
the device swaps to the other set live; editing in one orientation never
touches the other. Phone defaults: portrait 85% width / 40% height, landscape 80% /
30%, with default height bounded to 144–320 dp. TV defaults to 80% width and 64 dp
height, adjustable down to 32 dp in 8 dp steps. Both start at the bottom. Saved
explicit sizes survive updates; Reset uses the current defaults.
Upgrades keep a user's previously stored values as the portrait set.
Everything else (theme, engines, languages, TTS) remains shared.

## Android TV and remotes

TV support is experimental. Optional TV features allow installation without
requiring a touchscreen; a TV launcher entry/banner and real-hardware validation
remain pending. On TV, media Play resumes captions, Pause pauses them,
Play/Pause toggles them, and Stop ends captions and spoken audio. Holding BACK
in Hearth's TV activity stops captions.

The notification places Pause / Controls / Stop first, with Silence voice when
read aloud is enabled. Controls opens the overlay settings sheet, which becomes
D-pad focusable on TV while open and returns to non-focusable when closed.
TV captions register a media session for remote commands; phone captions leave
media-key ownership with the underlying video or music app. Compose supplies
focus navigation, but controller traversal of every screen is still unverified
on real TV hardware.

### TV caption presentation

TV overlays contain only text and do not accept touches or remote focus. All
buttons, the drag handle, history actions and the translating/provider status bar
stay outside the video overlay. Actual speech, translation and history errors
remain visible as text and in the separate controls/session UI.

In the TV editor, **Style → Subtitles** selects centered white text with a shadow,
no panel or border, and a 64 dp height. It shows the current partial utterance or
latest final, following Original/Translation/Both. **Position → Center** centers
the window vertically; Bottom is still the default. **Text panel** retains the
selected history and background/theme with no inline controls. Mobile keeps its
existing bubble controls, inline settings and minimum size.

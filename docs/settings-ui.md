# Local and Cloud settings

Hearth 0.16.0 groups setup by where inference runs. Settings starts with Local and
Cloud tabs; the offline flavor exposes only Local. Browsing a tab does not select
an engine, upload content, or start a download.

- **Local:** speech, translation, voices, camera/screen OCR, downloads/imports,
  and the installed-model benchmark.
- **Cloud:** speech providers, text translation connections, and self-hosted voices.
- **App preferences:** appearance, advanced caption controls, and help are shared.
  Since 0.18.0 they are direct directory links, also reachable from the toolbar
  settings sheet; appearance and phone shortcuts have their own pages.

Speech, translation and voice setup use the same Local/Cloud tab layout. Settings links
open the matching tab. Model management contains only local categories. A model
may still require an initial download in the connected build; “Local” describes
where inference happens, not the origin of its files.

Each tab retains its own scroll position and saveable presentation state when
switching tabs or returning from another setup page. Unsaved credentials are
in-memory only and may be cleared when leaving their editor. Keys never enter
Compose saved state. Existing encrypted stores, model locations and provider
choices are retained; there is no configuration migration.

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
| `settings_voice_model_card.kt` | Shared voice source/license card |
| `settings_voice_picker_ui.kt` | Shared voice/language picker |
| `SettingsDestination.kt` | Flavor-aware settings destinations |

`TranslationHub`, `ModelsScreen`, and `VoiceSetup` orchestrate their existing stores
and the focused editors. `TranslatorChooser` remains the shared selector used in
feature pages and service overlays. Its scoped mode shows only the settings tab's
local models or cloud connections. “Use this translator across Hearth” remains an
explicit action; switching settings tabs cannot trigger it.

The local model library no longer toggles caption CC/translation mode when selecting
or importing a text model. Caption-specific bridge controls remain in caption setup.
Voice-server setup requires an explicit “Use voice server as custom default” action;
browsing Cloud alone cannot change playback or send preview text.

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
translate mode: the translation once it lands). The engine picker matches the
overlay's — Device voice (Android TTS), Supertonic 3 on-device, shared voice
settings, a self-hosted voice server, or the BYOK cloud voice (play build).
Voice gender (Follow voice choice / Female / Male) is exact on Supertonic
(F1–F5 / M1–M5 ids) and the cloud voice (nova/shimmer vs echo/onyx), mapped
best-effort on Android system voices, and ignored where a server does not
expose it. Loudness (0–100%) scales the utterance volume on every backend.
Both options also live in the overlay's settings sheet for live changes; the
speaker applies them on the next spoken line.

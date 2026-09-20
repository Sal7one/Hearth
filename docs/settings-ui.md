# Local and Cloud settings

Hearth 0.16.0 groups setup by where inference runs. Settings starts with Local and
Cloud tabs; the offline flavor exposes only Local. Browsing a tab does not select
an engine, upload content, or start a download.

- **Local:** speech, translation, voices, camera/screen OCR, downloads/imports,
  and the installed-model benchmark.
- **Cloud:** speech providers, text translation connections, and self-hosted voices.
- **App preferences:** appearance, advanced caption controls, and help are shared.
  They are collapsed under the same entry in either tab.

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
The Simple/Vita-inspired home and onboarding proposal is a separate future change;
this release retains the existing main navigation.

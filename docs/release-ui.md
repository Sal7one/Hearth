# Hearth release UI

Appearance offers System, Light and Dark in Settings. System is the first-install default.
The setting is saved in the app's private preferences and updates open app windows
through preference listeners. It does not change the bubble's independent opacity,
background or text controls.

The main caption page exposes Audio and Show as short, wrapping chip groups. The
processing engine remains a dropdown because its longer list would crowd the page.
Selected controls use Compose's standard accessibility semantics. Page headings and
status/error announcements have explicit TalkBack semantics; text and buttons wrap.
Start still waits for pending configuration writes, and all controls retain their
existing capture/readiness rules. Browsing conversation or model comparisons does
not silently start or stop audio.

The palette now supplies Material 3 container and inverse roles explicitly, so menus,
cards and snackbars match Hearth in both modes. Verify contrast against the actual parent surface; some semantic containers use
alpha and need checking in both themes.

## Integration

- `ui.theme.HearthTheme { ... }` observes the saved appearance automatically.
- `ui.theme.AppearanceSettings()` renders the compact setting.
- `ui.theme.rememberThemeMode()` exposes the same preference for system-bar styling.
- `FFmpegStudioTheme` delegates for compatibility with existing callers.
- `MainActivity` displays five Material navigation items: Captions, Talk, Translate,
  Camera and Settings. Each has a full descriptive accessibility label and selected state.
- `AppNavigation` keeps independent tab return paths using the existing numeric deep
  links. Back returns to the source; reselect/Done returns to that tab's root.
- `rememberSaveableStateHolder` preserves each screen's draft/scroll state. Only the
  current screen is composed, so camera/microphone/TTS controllers retain their
  disposal rules rather than continuing in invisible tabs. Caption capture remains
  explicitly managed by its foreground service.
- `CaptionHome(onModels, onCloud)` contains live caption controls; the old page-launch
  buttons and benchmark shortcut are removed. Benchmark is in Settings.
- Talk contains both Conversation and Face to face, with the existing shared history.
- The launcher references the generated A/ع mascot for both normal and round icons,
  with a padded adaptive foreground. See [artwork provenance](brand/README.md).
- Activities passing an explicit `ThemeMode.SYSTEM` should use `rememberThemeMode()`
  instead to respect user selection.

Visual verification should cover dark/light/system switching, relaunch persistence,
large font wrapping, TalkBack selected states and opening each new destination.
The integrated navigation and themes were exercised in releases 0.7–0.8.1; see
[device checks](device-checks.md). An auditory TalkBack pass remains outstanding.

Five-tab navigation and mascot checks are recorded in [0.11.0 validation](validation-v24.md).

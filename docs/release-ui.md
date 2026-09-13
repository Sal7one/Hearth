# Hearth release UI

Appearance offers System, Light and Dark in setup. System is the first-install default.
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
- `CaptionHome(onModels, onCloud, onConversation = ..., onBenchmark = ...)` exposes
  optional entry buttons when the respective callback is supplied.
- Activities passing an explicit `ThemeMode.SYSTEM` should use `rememberThemeMode()`
  instead to respect user selection.

Visual verification should cover dark/light/system switching, relaunch persistence,
large font wrapping, TalkBack selected states and opening each new destination.
The integrated navigation and themes were exercised in releases 0.7–0.8.1; see
[device checks](device-checks.md). An auditory TalkBack pass remains outstanding.

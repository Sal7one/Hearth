# Start overlays from Quick Settings

Hearth 0.17.0 adds **Live captions** and **Screen translation** tiles to Android's
notification shade. In Hearth, open **Settings → Phone shortcuts** and add each
tile. Android 13+ shows the system confirmation. On older phones, swipe down twice,
choose Edit buttons, and drag the Hearth tiles into the active area.

- **Live captions** uses the saved speech engine, audio source, CC/translation mode,
  languages and translator. Device audio requires Android's audio permission plus
  a fresh screen-sharing grant; microphone uses audio permission only.
- **Screen translation** uses Camera's OCR profile, source/target languages and
  translator, plus the reading overlay's saved trigger/settle/scan preferences.
  It asks for screen sharing and does not request microphone or camera access.
- Tapping an already-running overlay's tile recovers its controls. It does not
  request a second projection session. Active tiles say “Tap to show.”
- The tile asks for unlock on a locked phone. Cancellation never starts capture.

Before requesting capture, Hearth checks overlay permission, conflicting sessions,
saved model files/runtime availability, keys and translation directions/packs.
Hearth temporarily hides its own overlay windows while quickstart is open so
they cannot cover setup buttons. Closing quickstart restores the controls.
Setup failures open **Setup diagnostics** with the actual cause, settings links,
and **Check again & start**. Conflicting overlays require an explicit Stop & retry;
conversation/camera/benchmark workloads must finish releasing their models.

Checks are local: no provider request, paid quota check, model download or full
recognizer allocation. Speech asset checks inspect presence/size; normal runtime
opening still validates full model integrity. A successful setup check cannot
predict native load failures, unavailable provider quota/connectivity, protected
media capture, or the language of future automatically detected speech. Existing
service/overlay error reporting remains in effect for those runtime failures.

Android notification permission is requested if needed. Denial does not prohibit
capture; the tile and overlay remain recovery controls. Nothing bypasses Android's
capture consent or app-specific audio/video capture restrictions.

## Implementation

- `shortcuts/OverlayTileService.kt`: permission-protected system tile services,
  pending-intent activity launch on Android 14+, live running state while visible.
- `shortcuts/OverlayLaunchActivity.kt`: visible owner of checks, permission results
  and diagnostics. An isolated, excluded-from-recents task returns to the reader
  after consent. Pending consent is not relaunched on rotation or repeated taps.
- `shortcuts/OverlayPreflight.kt`: existing model/provider stores and capability
  checks, without duplicating inference or key storage.
- `shortcuts/OverlaySetupCheck.kt` and `SpeechAssetPresence.kt`: tested diagnostic
  collection, cancellation and bounded local file checks.
- `settings/settings_shortcuts_ui.kt`: add-tile controls and manual instructions.

Tiles are exported only with `BIND_QUICK_SETTINGS_TILE`; the launch activity is not
exported. FOSS keeps both tiles for local models and receives no network permission.
No Accessibility service or notification-listener permission was added.

Android references: [Quick Settings tile guide](https://developer.android.com/develop/ui/views/quicksettings-tiles)
and [Android 14 tile launch requirements](https://developer.android.com/about/versions/14/behavior-changes-14#tiles-launch).

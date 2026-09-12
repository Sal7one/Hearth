# 0.4.1 — recoverable tap-through

September 12, 2026. Tap-through previously applied NOT_TOUCHABLE to the entire
caption window, including its settings and drag controls. It now keeps a separate
56 dp touchable unlock handle managed by the existing caption foreground service.
Tap the handle to restore controls without moving the bubble; drag it to move the
captions. Accessibility exposes restore and directional movement actions. The handle
prefers space above/below the captions and stays within viewport bounds when dragged,
resized or rotated. No second foreground service or permission was added.

Enabling tap-through closes settings and shows live captions with a visible recovery
hint. The notification offers Restore controls while tap-through is active; it uses
the existing recovery action to disable tap-through and center the bubble. Hiding or
stopping removes the handle. A handle-creation/layout failure clears tap-through and
attempts to restore the main window's touch flags before exposing the actual error.

## Verification

Required Gradle tests, both QA Kotlin compilations and both QA assemblies passed.
App: 117 tests per six variants; common module: 58 per two variants; no failures or
errors. Two host tests exercise handle placement outside caption text and reachability
with edge drags, full-screen bubbles, rotation-sized and tiny viewports.
`python3 scripts/verify-release.py` passed for both APKs. No native changes.

Final APK installed on the authorized Samsung SM-S908E / Android 16:

- Enabled Tap-through from Appearance; settings closed and a touchable lock handle
  appeared above the captions.
- Dragged the handle upward/left; the caption bubble moved and remained on-screen.
- Tapped the handle; it disappeared and the normal toolbar returned. Settings opened
  at the prior scroll position.
- Enabled it again, expanded the caption notification, and tapped Restore controls.
  The handle disappeared and the interactive bubble centered.
- Stopped while tap-through was enabled; the service and handle were removed.
  Started again with that saved setting; the handle returned and tapping it restored
  controls. Stopped after the check, with tap-through off and no service remaining.

The notification recovery check leaves the phone's bubble anchor centered. Saved
height, source language, translation target and selected models are unchanged. No
cloud request, audio benchmark, full TalkBack pass or physical rotation test was added;
rotation/tiny-boundary placement is covered by the host geometry checks.

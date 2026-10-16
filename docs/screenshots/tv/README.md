# Hearth on Android TV

## Captured on the owner's TCL · 0.23.10

These are unedited 1920 × 1080 ADB screenshots of the installed Hearth app on
TCL Smart TV Pro, Android 14, ARMv7, Arabic interface, captured on 2026-10-16.
Build: 0.23.10 (83), cloud-capable QA package `com.sal7one.transiber.qa`.

The dashboard, setup tabs and editor were opened normally. No test harness,
mocked app state, replacement screen or test credential was used. Existing
settings were retained. The editor's sample captions and scenic background are
part of the app's built-in placement preview; the living-room illustration is
also bundled app artwork. These screens show the UI, not a live recognition run.

### TV dashboard

![Hearth dashboard captured directly on the TCL](tv-home-tcl-0.23.10.png)

### Speech setup

![Hearth setup tabs captured directly on the TCL](tv-setup-tcl-0.23.10.png)

### Overlay editor

![Hearth overlay editor captured directly on the TCL](tv-editor-tcl-0.23.10.png)

The PNG files are copied directly from `adb exec-out screencap -p`, with no
cropping, compositing or regenerated pixels. Their original hashes are recorded
in [TCL-SHA256SUMS](TCL-SHA256SUMS).

<details>
<summary>Archived emulator and UI-test captures</summary>

The older `tv-caption-overlay.png` and `tv-overlay-audio-*.png` images were
staged UI-test fixtures with synthetic caption state and a replacement demo
background. They are retained for test history and are no longer featured in
the README gallery. The 0.23.10 emulator layout captures also used a fake test
credential, without making speech or provider requests.

[Archived captures and reproduction notes](https://github.com/Sal7one/Hearth/tree/v0.23.10/docs/screenshots/tv)

</details>

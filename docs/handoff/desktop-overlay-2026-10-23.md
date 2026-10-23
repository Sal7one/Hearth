# Desktop overlay: 2026-10-23

Local continuation of `codex/hearth-desktop-kmp` after `945c1bf` / desktop RC4.
The local checkpoint tag is `desktop-v1.0.0-rc.5`. No GitHub runs, pushes,
workflow dispatches or releases were requested or performed during this work.

## Behavior and source of truth

The floating caption window previously had no drag handler. Its Top/Center/Bottom
controls aligned text inside a fixed window, and its advertised shortcut was not
wired. It now supports body/grip dragging, eight resize directions, actual display
placement, saved bounds and off-screen recovery. Geometry uses AWT logical pixels,
including negative monitor coordinates, and is saved after a 350 ms quiet period.

The caption defaults to centered subtitles with a dark text shadow. Soft panel
and cinema bar remain available; existing enum IDs and preferences still load.
Arabic and English use content direction, normal letter spacing, configurable
font size/scale, alignment and line limits. Original and partial captions retain
existing pipeline/pending-translation behavior.

Hover shows a compact toolbar: text size, original line, style, click-through,
controls, reset and hide. The surrounding hover fill is now optional. The owner's
follow-up request added a 75–150% toolbar scale and 90–720 point area height.
Width controls preserve the manual center; height controls preserve the bottom
edge. Edge dragging also changes those saved dimensions. Toolbar scale is capped
when a narrow caption window would otherwise clip its buttons.

The ⋯ control panel is an independent 340×310 utility window, so a 90-point caption
area cannot clip or trap its height controls. Its contents are also available on
the Overlay page. Both use `OverlaySettings`; `overlayPrefs` projects that bundle
into the existing `DesktopSettingsStore` DataStore. No second production profile,
engine configuration, provider or language state was introduced. A shared slider
callback capture was fixed so dragging uses the current preferences.

## Native ownership and shortcuts

`platform/NativeOverlayWindow.kt` uses JNA 5.17.0 for Hearth's own NSWindow mouse
handling/Spaces behavior or Windows extended window styles. Exact window titles
are resolved only inside the current NSApplication, never in another process.
Click-through readback is checked; failures are surfaced and unlock the setting.

AppKit calls run on Cocoa's main queue from a worker coroutine. They must never
synchronously wait there from the AWT event thread: AppKit accessibility can
simultaneously be waiting for AWT. A guard and host regression test enforce that
boundary; callback ownership includes a reachability fence. Window focus changes
return to AWT after the native call. Cancellation is propagated.

`SystemOverlayShortcuts` owns Carbon hotkey references/callbacks or a Windows
RegisterHotKey message thread. Partial registration rolls back; disposal unregisters
references, removes the callback, and terminates the Windows loop. The application
also owns a focused-app KeyEventDispatcher and a duplicate-command gate. The UI
reports native registration failure and retains main-page unlock/reset controls.
Carbon's pressed event is **5**, as verified in Xcode's `CarbonEvents.h`; **6** is
the released event. This was corrected during validation.

JNA's Apache-2.0 option, upstream license notice and full license text are included
under desktop resources and described in `THIRD_PARTY_NOTICES.md`. Android, JNI
packages, TTS module boundaries, inference runtimes and GPU binaries are unchanged.

Primary API sources:

- [JNA Carbon mapping](https://github.com/java-native-access/jna/blob/5.17.0/contrib/platform/src/com/sun/jna/platform/mac/Carbon.java)
- [Apple NSWindow mouse handling](https://developer.apple.com/documentation/appkit/nswindow/ignoresmouseevents)
- [Microsoft RegisterHotKey](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-registerhotkey)
- Local Xcode SDK `HIToolbox.framework/Headers/CarbonEvents.h` and AppKit `NSWindow.h`.

## Verification and limits

The required Android tests/QA Kotlin compilation and all six desktop suites pass.
The final full gate took 25 seconds (370 tasks); the explicit desktop-only rerun
also passed. Desktop UI: 81 tests, zero failures/errors, two existing skips.
Android: 701 tests per QA variant, zero failures/errors (existing skips: Play 3,
FOSS 12). Runtime fixture/model opt-in skips remain visible in their XML reports.
The packaged launcher/runtime/native/media/crypto/authenticated-loopback check
passes on this Mac. Native inference binaries were not changed by the overlay work.
Regression coverage includes negative monitors, unplugged displays, opposite-edge
resize constraints, placement, bounded fonts, corrupt/NaN preferences, height/width
stability, key modifiers, duplicate toggle suppression, reset preference retention,
actual DataStore reopen/merge, the AWT/native thread guard and intrinsic sizing of
the quick controls. All desktop screens and the real caption layer still render
in headless tests.

An isolated preview was used on this Mac, without loading engines/audio/network or
opening the owner's production DataStore. The owner saw and approved the new
appearance, then requested adjustable hover controls. Saved drag placement and
font/style changes were observed in its preview receipt. Native mouse-handling
readback for unlocked mode and hotkey registration succeeded. Live testing caught
and repaired both the initial menu intrinsic-layout crash and the synchronous
AWT/AppKit accessibility deadlock; the latter was confirmed by a thread dump.

**Do not overstate remaining proof:** the Mac locked before a final live check of
the independent utility window and corrected native hotkey delivery/click-through
unlock. Those need an unlocked Mac and physical-key testing. Windows code compiles,
but native dragging/click-through/global hotkey behavior still needs a Windows
run. Headless renders and registration success do not establish those behaviors.
Full-screen Spaces behavior and monitor hot-plug while already visible also need
owner checks. Restore/clamp on opening is covered by host tests.

The production Hearth PID 11381 was left running throughout. The hung disposable
preview PID 30756 was force-quit through Activity Monitor; only that test process
was selected. A later preview may still be open with an older snapshot when the
Mac unlocks. Restart the production app after finishing its live session to load
RC5; do not mistake the isolated preview's fixed sample sentence for live output.

Ignored evidence/logs are in `/tmp/hearth-overlay-*.log`,
`build/overlay-ui-check/state/preview.properties` and the Gradle XML reports.
The preview harness is explicitly invoked with
`Hearth --overlay-preview <isolated-directory>` and writes only its own
`preview.properties`; normal application startup does not enter that mode.

## Owner follow-up: stale processes and Hearth identity

The owner's later screenshot showed two processes: the old production PID 11381
and the disposable preview PID 33353. Both were quit through their UI, their
exit was checked, and one rebuilt production instance was launched. The preview
is no longer left running. No capture, model inference, download or voice playback
was started during this UI check.

The floating toolbar and caption-control window now use `hearth.png`. The toolbar
icon brings the real main window to Overlay settings; that action was exercised
in the live packaged app. Diagnostic sample captions have a distinct native
window title and a persistent Preview label. The preview never registers global
hotkeys, leaving those registrations to the production application.

The AppKit bridge explicitly clears the NSWindow's background, opaque bit and
window shadow, then reads back opacity/alpha before reporting success. The live
app completed those readbacks without an error and remained responsive. This
does not establish Windows native behavior or full-screen/monitor hot-plug cases.

Native distribution icons were previously unspecified. Explicit macOS ICNS and
Windows ICO assets now come from the existing Hearth artwork. Regenerate with
`scripts/desktop/generate-icons.py`; the macOS packaged checker verifies the actual
Info.plist icon and bytes. The ICO contains 16/32/48/64/128/256 pixel frames; Windows
installer execution still requires its matching host.

Final local checks: all six desktop suites plus createDistributable PASS (35 tasks,
36s), required Android tests/QA Kotlin compilations PASS (357 tasks, 2s), packaged
launcher/native/media/authenticated-loopback/icon check PASS. No C++ inference,
Android module/ABI configuration, production preferences schema or model data was
changed. Logs use `/tmp/hearth-overlay-branding-*`; generated UI evidence stays
under ignored `build/overlay-branding`. No GitHub Actions commands were used.

## White backing after a focus/render transition

The owner reported a solid white caption rectangle, grey when the hover shade
was visible, which disappeared after resizing its bottom-right corner. Caption
text is not selectable and the overlay has no Ctrl+A action or white panel style.
The resize recovery suggests stale backing/redraw state; the original triggering
sequence has not been reproduced, so this is not proof of its exact cause.

The former AppKit clear ran only on opening or changing click-through and used a
title lookup. `OverlayWindowPresentation` now coalesces focus, show, resize,
display, backing-property and renderer changes. It prepares Mac Swing backing
panes, sets focusability before taking the current Compose native handle, applies
AppKit transparency after presentation attributes, and requests a fresh frame.
All AppKit work still runs off AWT via the AppKit main queue. The native bridge
checks the exact handle against this process's window registry before messaging
it; two windows with the same title cannot redirect a repair. Windows retains its
renderer-specific transparency mechanism and receives the actual Compose HWND.
Windows native behavior has not been exercised by this Mac fix.

An opt-in real Mac window regression deliberately sets both native and Swing
backing opaque/white, then checks recovery without changing window size. It also
checks focus-transition recovery, click-through/unlock, a second same-title
window remaining untouched, and rejection of a missing native handle. This
tests actual AppKit/Swing state, not a mocked native flag or a screenshot's JPEG
background. It does not simulate a Metal device loss or establish every macOS
full-screen/monitor transition.

```sh
HEARTH_OVERLAY_WINDOW_TEST=1 JAVA_TOOL_OPTIONS=-Djava.awt.headless=false \
  ./gradlew -PhearthDesktopOnly=true :desktopApp:desktopTest \
  --tests '*DesktopOverlayWindowPresentationTest*'
```

The owner's running production app was Ready with its loopback server running.
It was left open. The corrected app was built separately at
`desktopApp/build/overlay-transparency/binaries/main/app/Hearth.app`; quit the
current instance before opening that build. No models, provider settings or
production preference files were replaced. The existing Android release/tag
and its assets were not modified.

`scripts/desktop/check-packaged.py --app-dir <directory-containing-Hearth.app>`
now supports validating an isolated package without overwriting the active app.
The default package path remains unchanged. Local checks: required Android tests
and QA compilations plus common-JNI/TTS native debug builds, all six desktop
suites (including the opted-in real Mac window regression), and the corrected
packaged launcher/native/media/loopback/icon check. Final logs use
`/tmp/hearth-overlay-recovery-*`; evidence is copied to ignored
`build/overlay-transparency/evidence`. No GitHub Actions commands were used.
The C++ host suites use `CXX=/opt/homebrew/opt/llvm@22/bin/clang++` with
ASan/UBSan, matching the previous ABI audit. The first attempt with the system
Apple compiler stalled inside its sanitizer test binaries; only those test
processes were terminated before rerunning with LLVM 22.

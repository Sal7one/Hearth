# Hearth 0.13.0 / code 26 — screen reading validation

Date: 19 September 2026. Device: Samsung **SM-S908E (S22 Ultra), Android 16**,
ARM64. This is the actually connected test phone, not the owner's previously
reported SM-S938B. QA artifacts are debug-signed, optimized builds.

> Follow-up: these installations used ADB and did not validate browser/My Files
> installation. The owner subsequently reported a Play Protect block. Version
> 0.13.1 removes the optional Accessibility service; see [v27](validation-v27.md).

## Gates

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa`: PASS, final run 2m19s.
- App unit tests: **209 per variant**, all six debug/QA/release × play/FOSS suites pass.
- Common JNI unit tests: **74 per variant**, debug/release pass. Counts across
  variants duplicate tests and are not claimed as unique coverage.
- `:common-jni:assembleDebugAndroidTest` + real-device instrumentation: **3 tests PASS**.
- `python3 scripts/verify-release.py`: both variants PASS, native dependencies,
  pinned artifacts, 16 KiB alignment, OCR vocabulary provenance and flavor permissions.
- No native implementation source changed in this slice; the prior OCR/native host
  suites are recorded in [v25 validation](validation-v25.md). Device tests now exercise
  that actual Android runtime rather than relying only on macOS ONNX inference.

## Phone OCR observations

Small generated fixtures, not manga quality or sustained performance benchmarks.
Times exclude model construction and are single warm inference observations from
the final three-test run. Expected fixture text was specified before inference.

| Engine / fixture | Output | Inference |
| --- | --- | ---: |
| Manga horizontal | 今日はいい天気ですね。 | 228 ms |
| Manga vertical | 今日はいい天気ですね。 | 223 ms |
| Meiki horizontal | 今日はいい天気ですね。 | 292 ms |
| Meiki vertical | 今日はいい天気ですね | 252 ms |
| Meiki blank | Empty, as expected | 225 ms |
| Paddle English | The train leaves at noon. | 101 ms |

Manga cancellation after inference rejects another request. Meiki's missing final
vertical punctuation is preserved as a known limitation, not silently normalized
into a claim of exact output. Initial device test setup could not see shell-staged
external files under Android 16 scoped storage; staging in the test app's private
files directory fixed the setup and all three tests then passed.

## Phone UI checks

- Installed the optimized cloud QA build over 0.11.0 without clearing application data.
- Downloaded Manga encoder/decoder using **Download & install** in the actual app;
  verified the card changed to Installed and files remained in Downloads/Hearth/models.
- Started the new overlay through Camera, granted fresh Android projection consent,
  opened a locally hosted, authored Japanese reading page in Brave.
- Drew the first bubble; captured image did not include Hearth's floating controls.
- Manga recognized `今日はいい天気ですね。`. Missing ML Kit Japanese pack was surfaced
  verbatim with source text retained, rather than empty translation/success.
- Selected installed **Hy-MT2 1.8B Q4_K_M** and restarted: local translation produced
  **“It’s nice weather today.”** Source appeared while translation was pending.
- Closed the panel and scrolled: page-change mode automatically read the next bubble
  `駅はどこですか。` and translated it to **“Where is the station?”** The prior
  translation was cleared while the new page was processing.
- History retained both pages. Final UI browses source/translation together, one
  page per History tap, to keep read-aloud and source associations clear.
- Pause changed the foreground notification to Paused. Notification exposed
  Translate/Resume/Stop, and Stop removed the capture service. A new session
  successfully requested consent and captured again.
- Rotating to landscape kept the handle visible and inside the display.
- Installed the final FOSS QA artifact over the same data. Android Share with text
  opened Type to translate in manual mode without starting inference. Tapping
  Translate used Hy-MT2 locally and produced `أين محطة القطار؟` for “Where is the
  station?” (observed output, not a claim of perfect semantic equivalence).
- Restored the final cloud-capable QA build, stopped screen capture, returned Volume
  Up/its optional accessibility service to off, restored automatic rotation, and
  removed the temporary native test APK and duplicate test staging files. Verified
  model downloads and the app's installed models were retained.
- Android bound the optional accessibility service with key filtering and only
  scroll/window event types; no node retrieval capability. Injected ADB Volume Up
  did not trigger the shortcut. A physical-button check is still required; this
  is not reported as a successful hardware shortcut test.

Remaining manual coverage: full TalkBack traversal, accessibility scroll-distance
in diverse readers, physical Volume Up, secure/DRM pages, Arabic/RTL layout,
rotation during inference, and long thermal sessions. Cross-app results currently
use a readable panel; bubble inpainting, per-reader presets and a glossary are not
implemented. See [screen reading](screen-reading.md).

## Artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| hearth-v65-reading-cloud.apk | 35,100,823 | `c088667f400d4833a4a1dc8fc7eba80ee139fe5b6af5c4cff4605aca0eb05e89` |
| hearth-v65-reading-offline.apk | 27,315,729 | `55c0468013201ddd6c02d5b85b2a3a88a5f4249115b91a1f40647cf687d03d80` |

Model weights, temporary fixtures and test APKs are ignored/local, not committed.
No GitHub release is published by this validation session.

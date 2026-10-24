# Android Screen translation device recovery — 2026-10-24

Published QA prerelease: [`v0.30.0-rc.10`](https://github.com/Sal7one/Hearth/releases/tag/v0.30.0-rc.10), Android **0.30.0 / code 106**.
Code/tag subject: `c3c961987524f8e5e9f3f601edbd5ec8050be854`.
[PR #8](https://github.com/Sal7one/Hearth/pull/8) merged into `main` at
`bc57bd722f60aab2b17a4716d6f40aaac6eb5460`; its feature branch was removed.
All 13 individual assets have matching GitHub SHA-256 digests and sizes.
The ten APKs carry the exact code/tag revision. The existing QA app ID and signer
are preserved; the final Play ARM64 APK is installed on the authorized phone.
No GitHub Actions or `gh run` commands were used.

This follows the [RC9 handover](android-screen-reader-controls-2026-10-24.md),
starting from `ca8e291` / code 105. Intermediate commits `d6dff81` and `042798d`
were unreleased test candidates with subsequently discovered bugs; they are not
qualified release artifacts. The final fringe correction and delivery proof below
supersede those checkpoints.

## Actual phone evidence

The owner authorized operating the connected **Samsung SM-G781B, Android 13**.
Tests used Chrome coffee articles, Latin Paddle OCR and pinned **QuickMT
English → Arabic**, using local CPU inference. These checks establish no new GPU,
other-device, other-language or manga-model result.

Code 105 reproduced black first captures and retries that highlighted text then
lost results; pausing passive sampling allowed Arabic output. Persistent capture
fixed that path, but further dense-page testing exposed two additional problems:

1. Excluding every translated rectangle left too little visible article to detect
   scrolling. Old translations could remain over a changed page.
2. Native outline/label rendering escaped its exclusion geometry after viewport
   rounding and MediaProjection downsampling. These self-generated motion events
   repeatedly cancelled translation on a static page. The earlier `042798d` phone
   candidate therefore remains explicitly unqualified for release.

The **projection-fringe prototype** then passed the following real checks:

- One manual clean capture at **03:28:43.478** produced complete Arabic. The
  displayed **2,919 ms is OCR time only**, not end-to-end translation latency.
- After opening options, Back and immediate dense-article scrolling, the real
  motion event at **03:30:31.441** had only **713** uncovered samples, below its
  1,024 minimum: primary motion correctly stayed false. The independent underlay
  channel saw **6,024** samples, **1,525** changed across **102 tiles**, and detected
  the changed article through the translucent translation layer.
- Exactly one subsequent clean capture occurred at **03:30:32.585**; fresh Arabic
  completed for the new viewport. Total clean captures were **1 → 2**. No further
  false motion events or clean captures appeared in that recorded observation.
- A further **91 seconds** of quiet observation kept capture count **2 → 2** and
  motion-event count **1 → 1**. In interactive mode, a swipe inside a translated
  paragraph kept Chrome foreground and options closed; a deliberate tap in that
  same paragraph opened its selected-text controls. Touch-through was restored
  and Back returned to Chrome.

Earlier code-106 checks also established repeated explicit reads after scrolling
settled; options Back returned to Chrome without a new capture (**5 → 5**);
area selection excluded Hearth's own handle/labels, Center → Read translated its
crop, and Back cancelled selection. The full-width red Stop had about **56
physical pixels** of bottom clearance; tapping it left no active MediaProjection.
Saved language/model selections survived an in-place QA update. Those checks
describe their tested prototypes; current host regressions cover those flows.
The final committed APK was reinstalled and independently checked as recorded below.

The actual UI downloaded approximately **12.3 MiB** of Latin OCR models via the
standard HTTPS fallback after aria2's OpenSSL legacy-provider failure. An existing
pinned QuickMT ZIP was imported through Android's file picker. This is evidence
for those paths, not broad downloader or throughput validation.

## Implemented corrections

- One persistent projection surface and three-slot ImageReader retain/close the
  latest image correctly. Serialized capture, explicit-tap priority and bounded
  black/blank initialization handling replace repeated surface attachment.
  Absolute RGBA reads preserve buffer position, padding and opaque screenshot pixels.
- Saved OCR/model/language/translator settings are revalidated at admission.
  Changed workers retire before replacement; repaired settings can retry within
  the same service. Lifecycle decisions use the controller's current state.
- Progressive errors do not prematurely finish a page. Only fully idle work
  completes; stale results/errors cannot repaint newer captures. Provider-local
  cancellation, including ML Kit task cancellation, leaves translation retry
  usable. Blank translations surface errors rather than empty success.
- Completed pages survive navigation; uncertain or cancelled partial geometry is
  verified again. Setup return, partial pause/resume and refresh-mode changes no
  longer strand cancelled highlights or adopt the wrong motion baseline.
- Ordinary motion excludes Hearth paint; a separate stable underlay signal reads
  the article contribution through a layer capped at **0.8 alpha in both touch
  modes**. Paint/content changes rebase safely, while identical paint restored
  after options retains its earlier stable article baseline. No periodic flashing
  or OCR polling was added.
- Exclusions now derive from actual rounded view-space rendering and conservatively
  cover antialiasing/projection fringes plus the handle shadow. Cleanup masks keep
  their tight source bounds as well as demonstrated projection coverage.
- Swipes do not become text-box clicks. Normal completion/errors do not open
  options. Application-level Back opt-in covers service windows; Stop has its own
  full-width row, scroll clearance and navigation/gesture inset padding.

## Host checks and evidence

The three new native-graphics regressions passed with the focused view/service/
underlay checks in **13 seconds**. They render actual outlines and labels, apply
window alpha and projection-size resampling, and check real pixels against motion
exclusions. Fractional RLE cleanup tests remain intact; thresholds were not weakened.

The final required gate passed:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
```

Log: `/tmp/hearth-reading-device-verified-gate.log`: **BUILD SUCCESSFUL in 3m 42s**,
506 tasks (47 executed, 459 up-to-date). Actual XML results: each of the six app
variants has **1,021 cases**, zero failures/errors, with three Play skips or fifteen
FOSS skips per variant. Current common-jni suites have 136 cases each, hearth-core
Android 46 each, and hearth-tts 68 each with two skips, across Debug/Release.
These total **6,626 Android case executions including 58 skips**; variant repeats
are not distinct tests. Unchanged module caches were accepted by Gradle; retired
`hearth-tts-*` results are excluded. No GitHub Actions or `gh run` commands were used.

Local evidence under ignored `build/reading-phone-qa/` includes
`fringe-phone-log.txt`, `fringe-phone-result.png`,
`fringe-auto-back-scroll-log.txt`, `fringe-auto-back-scroll.json`,
`fringe-auto-back-scroll.png` and `fringe-auto-after-scroll.png`.
`diagnostic-motion-log.txt` records the earlier self-paint failure; retain it as
regression evidence rather than a successful candidate result. Earlier baseline,
area, Back and Stop screenshots/logs and `host-ui/` renders remain in that folder.
The actual tested phone base APK is saved as `phone-validated-source.apk` there:
SHA-256 `e9c8e77532bed095485274170a269d63a652025f7dbb9c11adc8ad5e71977f79`.

## Final delivery record

The committed Play ARM64 APK was installed **in place**, preserving the saved Latin
reader, English → Arabic pair, QuickMT model and other preferences. Its installed
base-APK SHA-256 exactly matches the delivered file:

`fdfec4ea410480aa6ee545199af15710ff351d052ca69767d62eee483f79f993`.

It cold-started on the public coffee-preparation article with **one clean capture,
zero false motion events and actual Arabic output** (1,292 output characters in
the UI proof). Its displayed **2,896 ms** measures OCR only. Stop was fully visible
above the navigation bar and ended MediaProjection. Final device state: capture
stopped, touch-through enabled, Manual refresh selected, models/settings retained.

The final APK and the saved phone-tested prototype have **1,191 identical runtime
ZIP entries**, including DEX, native libraries, Android manifest, resources and
assets. Only VCS metadata and JAR signature/manifest records were excluded from
comparison. Their common payload digest is
`203632d14749267d7a12a76c5e9105a93393682364f95201ca3bfa0832f9cb53`.
This links the detailed manual/dense-scroll/91-second/gesture matrix to the
delivered runtime without confusing the prototype APK hash with the final hash.

Both QA flavors were repackaged after committing. `python3 scripts/verify-release.py`
passed all ten APKs: signer, current package/code, native ABI inventory, ELF/page
alignment, application-level Back opt-in and FOSS permission checks.
Post-commit build log: `/tmp/hearth-reading-device-commit-package.log`; verifier:
`/tmp/hearth-reading-device-verification.log`.

Release folder: `build/releases/v0.30.0-rc.10/`. Its individual files are ten QA APKs,
aria2 corresponding source, `SHA256SUMS` and `VALIDATION-RECEIPT.json`. The receipt
records exact source, test suites, APK hashes, actual-device matrix and payload
proof. Local supporting records are `final-device-result.json`,
`runtime-payload-proof.json`, `release-exact-log.txt`,
`release-exact-arabic-proof.xml`, `release-exact-stop-padding.png`, and
`github-assets-verified.json` under `build/reading-phone-qa/`. No combined APK ZIP.

These checks validate the stated CPU reader configuration and controls. They do
not establish other model/language quality, GPU throughput or predictive Back
gestures. QuickMT can repeat long passages, and the translucent layer can show
source-text ghosting; those quality/readability limits remain separate. Secure
screens retain Android's capture restrictions. Desktop/native sources and TTS
module boundaries are unchanged by this release.

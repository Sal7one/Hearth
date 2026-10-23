# Hearth post-handover audit — 22 October 2026

The owner requested an independent audit of the subsequent Android/desktop work
and explicitly rejected reintroducing per-family TTS modules. This audit started
at clean commit `b020548639121bef4e4a8db67eeb0ab98ba3bbd3`, whose production
subject is `9f2b93675adb1afc81bbe288fcce62867736b42e`, on
`codex/hearth-desktop-kmp` / [PR #4](https://github.com/Sal7one/Hearth/pull/4).

**The handover's “feature-complete” wording was too strong.** Several rendered
screens had broken actions or persistence. Source review found actual regressions
and the fixes below were implemented and tested. Headless renders prove layout,
not complete behavior or owner acceptance. This audit does not claim every model's
quality or every desktop feature has been proven.

## TTS structure retained

There is one Android TTS Gradle module, `:hearth-tts`, plus the shared
`:hearth-onnx-runtime` dependency used by speech and TTS. No adapter Gradle modules
were restored. The old `hearth-tts-*` directories remain source folders, preserving
source pins and paths used by native builds; they are not included projects.

The consolidation retained all 36 existing production Kotlin files, 16 existing
test files and 177 assets byte-for-byte without asset collisions. The old
manifests contained no components/permissions to lose. The merged SDK still
exports all seven adapters, with ARM64 native code only. x86/ARMv7 downloaded TTS
remains unavailable. System/cloud voices are separate paths.

## Confirmed defects repaired

| Area | Defect and resulting correction |
| --- | --- |
| Saved desktop UI settings | Navigation/theme/overlay screens submitted partial maps, but saving replaced the entire map. Atomic DataStore patch merges now preserve unrelated preferences and explicit empty values. |
| Pending original captions | The real floating overlay hardcoded the pending-original option to true. It now receives the saved choice; tests cover hidden, shown and translated captions. |
| Existing model import | The UI rewrite disconnected the verifier and import picker. Per-model file/directory import is restored, rejected files surface issues, verified paths appear installed, and activation remains a separate explicit Use action. Selected imported routes are reverified at launch. |
| Stage reselection | Clicking the already selected STT/MT/TTS engine erased its verified model, credentials and tuning. Same-engine selection now preserves the route. |
| Speech timing | Responsiveness wrote `windowMs`, which setup validation rejected. The option is now explicitly allowed and bounded to 1000–30000 ms. |
| Voice identity | Every local provider was presented as Supertonic with its ten styles and fixed sample-rate claims. Labels now use actual engine descriptors; Supertonic styles/settings remain specific to it, and model-dependent readiness is honest. |
| Read-aloud enable | A predicate intended to prefer Supertonic selected the earlier MOSS descriptor and could reset an existing style. Selection now prefers available Supertonic, then an available TTS engine, and preserves an existing route/style. |
| API-key field | Typed provider keys were rendered in plain text. Only the key field now uses password transformation/input semantics. A reference is labelled “Key configured”, not falsely asserted to be decrypted/stored. |
| Typed translation draft | Navigating to model/provider setup destroyed the draft. It is now held above screen navigation in RAM, without saving private text to disk. |
| Retry | The failure action only cleared a notice while the failed snapshot remained. Retry now retains the previous text/file/input kind in RAM, uses current repaired settings, creates fresh mic flows, blocks admission while active/cancelling, and clears retained input on close. |
| Default microphone | The UI's empty System default ID was parsed as an invalid device index. Empty IDs now normalize to the actual default at both UI and capture boundaries. |
| Ming integrity | Runtime accepted missing/partial `sha256.*` inventories. It now requires all 17 actual inputs, validates hash syntax/paths, verifies contents and uses component metadata/pins to invalidate the verification cache. Real smoke tests supply reviewed catalog pins rather than inventing expectations from tested files. |
| CT2 integrity/cache | One pinned file could authorize unverified model/tokenizer files; cache identity omitted some component changes. Runtime now requires the complete mode-specific model/config/tokenizer/vocabulary inventory and includes all pins/file stamps in cache identity. |
| Vosk selection identity | The expected selected tree pin was checked for syntax but not compared; native code accepted any whitelisted language tree. The selected tree is now explicitly compared using the existing bounded, cancellable, no-follow tree-hash contract. |
| Native teardown | A close failure could skip the remaining independent owners. Cleanup now attempts every owner once and retains the first error plus suppressed subsequent failures. |
| Speech language declaration | Phonon/TranscribePsy could accept unsupported explicit language selections while their decoder used another language. Supported-language checks now run before native creation, normalize regional tags and retain Auto without inventing a detected language. |
| Merged SDK shrinker contract | Consolidation dropped seven adapters' exported JNI/callback keep rules. The single AAR now exports the complete contract again; the application's broad keep rules had masked this standalone-consumer failure. |
| Ming native build guard | Consolidation removed the exact prebuilt inventory/SHA preBuild check. The single module now runs it for ARM64 builds and tests healthy, changed, missing and extra binary cases. No native binary/pin was replaced. |
| Supertonic availability | It was hardcoded available after all TTS became ARM64-only. SDK and app now report the actual load result/reason and fail before model work on unsupported or missing runtimes. |
| HTTP range cleanup | Rejected status/Content-Range responses leaked the body stream before returning to the caller. They are now closed; accepted response ownership stays with the caller. |
| SDK artifact verification | The old verifier accepted any nonempty native inventory and did not check the merged shrinker/API contract. It now verifies all 14 ARM64 libraries, adapter classes, JNI/callback rules, no weights, no app/STT/MT class leakage, and the four-ABI shared ORT archive. Negative archive fixtures cover the consolidation regressions. |
| CI source coverage | Desktop path filters watched Supertonic but omitted other shared adapter source folders. All `hearth-tts-*/src/**` changes now trigger desktop validation. The SDK archive regression tests are also run in Android CI. |
| Documentation | Removed stale instructions describing separate adapter projects/AARs, corrected the release-note size inconsistency, and recorded this audit rather than treating earlier render/CI claims as current acceptance. |

## Validation performed after fixes

The combined final Gradle invocation passed in 1m12s (526 tasks; incremental):

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin \
  :hearth-core:desktopTest :desktop-runtime:test :desktop-data:test \
  :desktop-media:test :desktop-integration:test :desktopApp:desktopTest \
  :hearth-tts:assembleRelease :hearth-onnx-runtime:assembleRelease \
  :app:assemblePlayQa :app:assembleFossQa :desktopApp:createDistributable
```

Real-model environment flags were explicitly supplied for the three affected
runtime routes. The tests used existing ignored fixtures and performed no model
download, cloud request, microphone capture, audio playback or owner-device work.

- Android: 698 tests per six app variants, no failures; expected Play/FOSS skips.
- Shared core: 10 desktop tests and the corresponding Android core tests pass.
- Desktop runtime: 83 tests, no failures/errors, 25 opt-in/platform skips. Enabled
  QuickMT/Ming/Vosk model tests ran; the other model tests remain opt-in.
- Desktop data: 17 tests, no failures, one optional fixture skip.
- Desktop integration: 18 HTTP/SSE/lifecycle tests, no failures.
- Desktop media: actual native media-conversion test passes.
- Desktop UI/controller: 36 tests, no failures, two render-only opt-in skips.
- Merged TTS: 68 tests in each Debug/Release suite, no failures, two host/platform
  skips per suite; native-integrity fixture task passes.
- Common and speech C++ host suites pass under LLVM22 ASan/UBSan, including real
  JNI exception checks and 105 speech/168 Whisper failure checks.
- `:common-jni:externalNativeBuildDebug` passed all configured Android ABIs in the
  first fix gate. No C++/Rust sources or shipped prebuilt binary pins changed.
- Both Play/FOSS QA builds produced the ten APKs; `verify-release.py` passed
  signature, identity/version, ABI/native contents, alignment and permission checks.
- `verify-tts-aars.py` passes actual AARs: 14 ARM64 TTS libraries, four shared ORT
  libraries; restored consumer rules and all seven adapter APIs are present.
- Four SDK archive negative/positive host tests and eight desktop Python tests pass.
- Desktop catalog generation and handoff metadata validation pass.

The actual packaged Mac launcher/JRE now checks all 16 complete local ports in
the bundle, not only the original Whisper/MT/Supertonic trio. All loaded
successfully; native media decoding, audio/EC crypto and authenticated loopback API
checks also passed. This local native bundle was compiled for CPU; these checks do
not establish GPU execution or performance. All 31 native/license bundle file
hashes match their existing manifest. Native source receipt:
`6d8061ddf3cc67d92b40ddfd36c6298ee3326357f4bdec25427c39f9232f7334`.

The [new execution receipt](../research/post-handover-audit-smoke-2026-10-22.json)
records actual production-path QuickMT Arabic translation, Vosk English
transcription and non-silent Ming speech. Measurements occurred during parallel
Android compilation; do not use these elapsed times as performance benchmarks.

Ignored local evidence is under `build/audit-2026-10-22/`: `baseline.log`,
`fixes-gate.log`, `final-gate.log`, `common-host.log`, `speech-host.log`,
`packaged-self-check.log`, `verified-apks.log`, `verified-aars.log`.

## CI, release and remaining limits

GitHub evidence was checked independently. Contrary to the handover's stale
checkpoint, Android and ABI emulator CI passed at `9f2b936`; both Mac jobs also
passed there. The Windows runtime-staging job failed and Windows remains parked.
Those runs precede this audit's fixes. Read the new PR checks for the audit commit
instead of carrying old green status forward.

No merge, publication, version bump, signing change or release-asset replacement
was performed by this audit. The existing Android 0.28.0 draft targets `9f2b936`
and does not contain these source fixes; rebuild its assets/provenance before
publishing. The desktop packaged app image was validated locally; no new DMG or
Windows installer was published.

Owner overlay/window, phone/FOSS import, listening/quality, corpus accuracy and
sustained S25/S22 measurements remain unperformed here. Windows/NVIDIA support is
not newly claimed. Existing source-tree runtime build scripts still emit their
legacy per-family SHA receipt paths; current release validation uses the actual
runtime/source JSON hashes, and Ming's restored build guard uses its relocated
pin file. Moving the producer paths should be paired with a native rebuild and
fresh provenance rather than editing compiled-source receipts by hand.

Selected external model paths are rechecked at launch. Import-without-activation
and microphone selection remain session state; this audit does not add a durable
registry for every unselected external directory or persist unstable numeric
JavaSound device indices. Typed drafts intentionally remain private RAM state.

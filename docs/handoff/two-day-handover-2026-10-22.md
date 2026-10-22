# Hearth — two-day handover (2026-10-20 → 2026-10-22)

**Subsequent audit:** read [post-handover audit](post-handover-audit-2026-10-22.md)
for confirmed regressions, fixes and new validation. Earlier feature-complete/CI
statements below describe the historical checkpoint.

**Written:** 2026-10-22 21:40 Asia/Riyadh  
**Branch:** `codex/hearth-desktop-kmp`  
**Code/release subject:** `9f2b93675adb1afc81bbe288fcce62867736b42e`  
**PR:** <https://github.com/Sal7one/Hearth/pull/4> — open, base `main`, mergeable, **UNSTABLE** (old CI rollup; Windows red)  
**Draft release:** <https://github.com/Sal7one/Hearth/releases/tag/untagged-3e7bbdecbff3537ead0b> — **not published**  
**Release target:** `9f2b93675adb1afc81bbe288fcce62867736b42e`  
**Android artifact:** 0.28.0 / versionCode 94 / QA test signature / package `com.sal7one.transiber.qa`

This is the comprehensive handoff for the work done by the current agent and the
preceding agents from **2026-10-20 14:24** through **2026-10-22 19:41** (80 commits).
Read this first, then the authoritative narrower documents listed in §14.

> **Authorship caveat.** Every commit is authored as “Saleh Alanazi” because the
> agents use the repository’s configured git identity. That is **not** evidence that
> the owner personally wrote or approved those changes. The workstream split below is
> reconstructed from commit clusters and session history; git cannot name individual
> agents. No owner approval exists for the original eight-module TTS structure or for
> Windows support.

> **Docs-only note.** This handover file was added after the release subject. The
> code and draft release remain bound to `9f2b936`; the handover does not change the
> APKs, source ZIP or provenance.

---

## 1. Executive state

| Area | Current state | Evidence / limit |
| --- | --- | --- |
| macOS arm64 desktop | Feature-complete and release-verified | 17/17 headless screens, overlay HTTP/SSE, full native bundle, real-model smokes |
| macOS Intel desktop | CI builds/tests pass | Green at `7c3be14`; not owner visual-tested |
| macOS packaged installer | **Not validated** | No packaged app in this tree; `check-packaged.py` not run |
| Windows desktop | **Parked by owner** | CI red by design; do not claim support |
| Desired always-on-top overlay | Implemented/headless-tested | Real window still needs owner eyeball check |
| Android 0.28.0 QA APKs | 10 artifacts built, verified, draft release | Test-signed, not production-signed, **not published** |
| Android TTS module shape | **One `:hearth-tts` module** | Adapter sources, one CMake, one jniLibs set, one test set |
| Android TTS ABI | **ARM64-only, including Supertonic** | x86/ARMv7 APKs contain no TTS wrappers |
| Active ABI consistency | Root active-ABI list applied to app, common-jni, TTS | Clean-build proof in §7.3 |
| CI at latest commit | **Not waited on** | Last observed green desktop/Android/emulator run predates the final Android commits |
| Release | Draft only | Publish only after owner overlay check |
| Model weights/keys | Not committed | Downloads/imports only |

---

## 2. Repository and delivery state

- Working tree was clean at handoff; no uncommitted source changes.
- **80 commits** since `2026-10-20 00:00`; first relevant commit `09de08f`
  (2026-10-20 14:24), last `9f2b936` (2026-10-22 19:41).
- The branch contains Android, the KMP desktop workspace, the shared TTS SDK,
  native dependencies, release tooling and docs.
- The PR body describes the desktop/Ming work; the latest PR comments record the
  module/ABI corrections and local evidence.
- **No desktop tag, desktop release or merged `main`** exists.
- **No model weights, signing keys or provider credentials** are committed.
- `main` remains separate; PR #4 is the delivery vehicle.

### 2.1 Commit clusters

| Window | Workstream | Representative commits |
| --- | --- | --- |
| Oct 20 afternoon | Model-expansion foundation, TTS SDK, compact translation, 0.27.3 prep | `09de08f`, `539e0c0`, `d5f1396`, `2ecb473`, `ecd1dc0`, `76d8125`, `5583f97`, `1b1a27c`, `8ecd650`, `ba77c13`, `3bb02ed` |
| Oct 21 early | KMP desktop workspace and Windows staging fix | `13429dd`, `1768964`, `e6e62bf` |
| Oct 21 evening | Desktop UI rebuild/port, 17 screens, shared pipeline/JNI proof | `d02dd75` … `d32f85e`, `857cb55`, `7fae392`, `7fffdb7`, `ce60d51`, `3fd9941` |
| Oct 21 late → Oct 22 early | Engines, model mapping, desktop native proofs, Ming, docs | `fcd2159` … `cb19b8a` (see Appendix A) |
| Oct 22 afternoon | Audit fixes and CI hardening | `c0cc896`, `876784c`, `6ed7728`, `69960c1`, `45d4945`, `7c3be14`, `6e61b55` |
| Oct 22 evening | TTS ABI fix, one-module collapse, active-ABI centralization | `6bfbae4`, `25ebadc`, `9f2b936` |

---

## 3. What was built — desktop KMP

The desktop product is a KMP workspace sharing portable contracts with Android while
keeping Android screens/services intact.

### 3.1 Modules and boundaries

| Module | Responsibility |
| --- | --- |
| `hearth-core` | Portable `PipelineContract`, session snapshots, one active heavy job, four admitted jobs, ordered captions/events, exactly-once finals, bounded downstream queue, joined cancellation, shared text chunking |
| `desktop-runtime` | Real Whisper, Qwen3-ASR, TranscribePsy, Nemotron, Vosk, sherpa families, llama.cpp GGUF and CTranslate2 translation, TTS owners, system voices, JavaSound capture, strict native/model integrity, resident caches |
| `desktop-media` | AVFoundation (macOS), Media Foundation (Windows), bounded PCM WAV decoding; macOS Security.framework credential helper; no FFmpeg/editor |
| `desktop-data` | DataStore settings, portable setups, OS credentials, catalog derivation, exact-size/SHA downloads and imports |
| `desktop-integration` | Authenticated loopback HTTP jobs, bounded PCM streaming, SSE, cancellation/audio downloads, uploaded media, read-only OBS browser-source capability |
| `desktopApp` | Compose workspace/models/providers/integration screens, independent stage controls, floating captions, file/text/audio outputs, settings/installer entry points |
| `scripts/desktop` | Pinned native dependency provisioning/builds, catalog generation, media helpers, Windows CRT staging, packaged-launcher checks |
| `.github/workflows/desktop.yml` | macOS arm64, macOS Intel and Windows x64 build/test/package jobs |

Kotlin 2.2.0; Compose Multiplatform 1.9.0; JDK 17; NDK 27.0.12077973; CMake 3.22.1;
Android compile/target SDK 36; min SDK 28. `-PhearthDesktopOnly=true` excludes
Android projects for desktop-only hosts.

### 3.2 UI and captions

- Desktop UI was rebuilt onto the approved design and then ported screen by screen:
  Home, Live captions, overlay studio, Models, presets/downloads, speech settings,
  translation/voice, appearance, file locations, connections, integrations,
  typed translation, and companion screens.
- **17/17 desktop screens** and the floating caption layer were rendered headlessly
  from the real Compose UI with a clean profile, real catalog and real capabilities.
- The old rejected desktop UI was deleted; presentation was split into section files.
- The overlay page and live-caption HTTP/SSE path were tested headlessly.
- **Still pending:** the real always-on-top, undecorated, click-through window has not
  been visually inspected by the owner. Do not describe this as fully accepted.

### 3.3 Desktop pipeline behavior

- Independent STT/MT/TTS stage choices.
- One heavy job at a time; at most four admitted jobs; visible overload.
- Ordered captions/events; exactly-once final text; bounded downstream queue.
- Joined cancellation.
- Captions can show the original while translation is pending.
- Local model downloads/imports are explicit, exact-size and SHA-verified; no model
  call silently downloads weights.
- Cloud keys stay in OS credentials; no provider keys are sent to model/file hosts.

### 3.4 Platform honesty

- macOS arm64 is the verified host.
- macOS Intel CI builds/tests pass, but the owner visual check is still macOS arm64.
- Windows is build-wired and CI-red; **not validated**.
- No NVIDIA CUDA or Windows GPU backend.
- Installers are unsigned previews; no signing/notarization identity.
- No packaged macOS app/installer validation exists in this tree.

---

## 4. Engine coverage and real-model evidence

This table is the accurate high-level state; detailed commands/receipts are in
`docs/desktop-native-verification.md`, `docs/handoff/validation.md` and the JSON
receipts under `docs/research/` / `docs/models/`.

### 4.1 Local STT

| Engine | State / proof |
| --- | --- |
| Whisper | Pinned whisper.cpp formats; real macOS transcript exact with Metal GPU; multilingual Arabic/English; `.en` models English-only; live is bounded utterance-windowed, not acoustic-token streaming |
| Qwen3-ASR | Real 4.65 s audio, exact transcript, 406 ms decode (RTF 0.081), 2.0 s load; 5550 ms full engine path |
| TranscribePsy 250M EU | Exact English transcript; 75 ms warm decode; 4561 ms engine path |
| Nemotron 3.5 ASR | Exact lower-case transcript; 573 ms decode; 6784 ms engine path |
| Vosk Kaldi | Desktop runtime 0.3.42; six models; ZIP/tree-hash install; EN exact, DE/FR/RU/ZH app-path smokes; Spanish quality/repeat stability unverified |
| Moonshine | 10/10 pinned variants; tiny.en/base.en exact; ar/es/ja/ko/uk/vi/zh non-empty native-script transcripts; each verifies all four pinned components |
| Omnilingual CTC 300M | Exact English transcript through sherpa bridge; 220 ms warm |
| Phonon 2 | Exact English transcript; 117 ms warm |

### 4.2 Translation

| Engine | State / proof |
| --- | --- |
| llama.cpp GGUF | 14 catalog models; EuroLLM/Riva cards; explicit verified GGUF configuration; production prompt matches publisher template |
| CTranslate2 QuickMT | EN→AR real output; 111 ms native / 4771 ms engine path |
| CTranslate2 SMaLL-100 | Catalog/runtime path; production weight checks recorded |
| EuroLLM 1.7B | Pinned Q4 EN→AR/AR→EN fixtures; six sentences each; not a corpus score |
| Riva-Translate | Corrected Q4 twelve-case/5-safety receipt; both directions; cancellation/no partial reset; no corpus/GPU speed claim |

### 4.3 Local TTS

| Engine | State / proof |
| --- | --- |
| Supertonic 3 | Shared SDK; ten styles; CPU; closest to the original Android voice path |
| MOSS-TTS Nano 100M | 48 kHz WAV; direct JNI 61,440 samples; peak 0.516/RMS 0.0996; 99% non-zero |
| Ming-3.0 local | 44.1 kHz; 61,740 frames; 87% non-zero; 850 MB pinned bundle; EN/ZH; one-shot |
| OmniVoice | 24 kHz; EN/AR non-silent; RTF 4.1–5.3 warm (one 14.1 outlier under host load); app path 4.26–5.25 |
| Magpie 357M | 22.05 kHz; EN/AR non-silent; direct RTF 1.10–1.91; bounded 31 MB range install; catalog install proof 1.115 s |
| Chatterbox V3 | 24 kHz; EN/AR non-silent; first-use graph/Perth derivation proven; RTF 4.68 warm / 22.97 cold; experimental |
| OuteTTS 1B | Technical PASS **but deliberately not shipped**: NC-SA + Llama terms and missing required attribution; Apache-2.0 0.6B is future work |

### 4.4 End-to-end

- Speech + voice: `COMPLETED segments=1 audio=1`.
- Speech + translation + voice: `COMPLETED ms=6182 segments=1 audio=1`.
- Vosk tree pin persisted in desktop setups.
- Shared `HearthSpeechBackend` C ABI v2 used by desktop adapters.
- JNI handle ownership pinned by tests; failed backend construction now releases the
  `dlopen` handle/session.
- Desktop staging verifies native SHA-256; Ming verifies 17 per-file pins once at the
  engine boundary, relinks stale parts, and normalizes `en-US` / `zh-Hans`.

---

## 5. Ming: desktop and Android

### 5.1 Desktop

- One-shot local `ming-local` engine, 44.1 kHz mono, English/Chinese.
- ~850 MB bundle downloaded/imported outside the repo; 17 pinned parts; real catalog
  install proof produced 61,740 frames.
- Rust JNI (`libming_jni.dylib`) built from pinned fusor-ai/ming; 1–8 threads with
  card default 6.
- `ming-wasm` CLI proof exists and is documented; JNI was chosen over WASM for the
  product path.
- Reloads the bundle per sentence; no native streaming/mid-synthesis cancel.

### 5.2 Android

- Android `hearth-tts-ming` adapter, now folded into `:hearth-tts`.
- arm64 dotprod + scalar DSOs; 16 KB page alignment; 7 voices; EN/ZH; 1–4 threads.
- Off by default behind a Voice settings switch; ~850 MB bundle, meaningful
  missing-component count, download/import actions, explicit one-shot and 300-character
  limits.
- Real device proof (owner-authorized SM-G781B): in-APK `System.loadLibrary`/JNI under
  SELinux; 88,200 frames; +dotprod/4 threads RTF 2.06–2.12; peak RSS 1.02–1.28 GB;
  scalar RTF ~34; four AudioTrack runs (English + Chinese) in the earlier 0.27.3 UI
  proof; persistence and flag-off gating verified.
- Latest local 0.28.0 debug `MingLocalDeviceTest`: **OK**, 12.029 s,
  `MING_ANDROID_APP frames=88200 peak=0.46887207`.
- Remaining owner checks: human listening/quality, FOSS-on-device import, live-caption
  read-aloud.

---

## 6. Android 0.28.0 — release and artifact state

### 6.1 Identity

- Version **0.28.0**, versionCode **94**.
- QA package `com.sal7one.transiber.qa`; debug/test certificate, same delivery
  identity as prior QA builds. **Not production-signed.**
- Ten APKs: Play/FOSS × arm64-v8a, armeabi-v7a, universal, x86, x86_64.
- Current sizes (actual local artifacts):

| Flavor | arm64 | ARMv7 | universal | x86 | x86_64 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Play QA | 113.6 MB | 48.5 MB | 201.1 MB | 54.5 MB | 53.6 MB |
| FOSS QA | 105.5 MB | 41.2 MB | 172.7 MB | 45.9 MB | 45.2 MB |

> The release body’s first recommendation table still says Play universal **201.2 MB**
> while the complete table says **201.1 MB**. The actual final artifact is 201.1 MB;
> this is a cosmetic doc inconsistency in the draft, not an artifact mismatch.

### 6.2 Draft release assets

15 assets:

- 10 QA APKs
- `INSTALLING.md`
- `SHA256SUMS`
- `BUILD-PROVENANCE.json`
- `hearth-0.28.0-source.zip`
- `hearth-0.28.0-validation-evidence.zip`

`BUILD-PROVENANCE.json` binds `sourceCommit=9f2b936…`, version 0.28.0/code 94,
QA signing, no model weights. Source and validation ZIPs were regenerated at this
commit. The draft is **not published**; no desktop release/tag exists.

### 6.3 TTS module collapse (the “eight modules” correction)

The original Android TTS structure was one Gradle project per family:

```
hearth-tts, hearth-tts-supertonic, hearth-tts-magpie, hearth-tts-omnivoice,
hearth-tts-moss, hearth-tts-oute, hearth-tts-chatterbox, hearth-tts-ming
```

That came from a standalone SDK/AAR packaging pattern, not an app requirement. There
is **no recorded owner approval** for it. Only `hearth-tts-ming` was added by the
latest session; it followed the existing pattern instead of challenging it.

Current shape:

```
:app
:common-jni
:hearth-core
:hearth-tts              ← core + all seven adapters
:hearth-onnx-runtime     ← one shared ONNX runtime
```

- `settings.gradle.kts` has **one** TTS entry.
- The seven old `hearth-tts-*` directories are now **source folders** referenced by
  `:hearth-tts` source sets; their Gradle build files are deleted. They are not
  modules on Gradle sync.
- `:hearth-tts` owns one CMake project, one jniLibs set, one test source set, all
  adapter assets, and the app dependency.
- `:hearth-tts` AAR: **8,460,797 bytes / 14 native libraries**.
- `:hearth-onnx-runtime` AAR: **26,499,331 bytes / 4 ABIs**.
- `scripts/verify-tts-aars.py` now checks the two real archives.
- The TTS SDK docs describe one `hearth-tts` AAR + shared ONNX AAR, not eight.

A future cosmetic move can physically relocate the adapter source directories under
`hearth-tts/adapters/`; it is not required for the Gradle module shape.

### 6.4 TTS ABI state

- **All TTS adapters are ARM64-only**, including **Supertonic** (the earlier miss).
- x86/ARMv7 APKs contain **no TTS wrappers/runtimes**. They do still contain the
  shared speech stack and the shared ONNX runtime where applicable.
- ARM64 APKs contain wrappers + prebuilt family runtimes + Ming DSOs + ONNX.
- `scripts/verify-release.py` has an explicit ARM64-only TTS wrapper/runtime set and
  checks that each APK has exactly the expected libraries.
- The x86 APKs were reduced accordingly; no unusable JNI wrapper is shipped.

### 6.5 Active ABI synchronization

There is one root active-ABI source in `build.gradle.kts`:

1. Android Studio’s `android.injected.build.abi`
2. `-PhearthAbis=...` (comma-separated)
3. all four release ABIs by default

Applied to:

- `:app` `ndk.abiFilters` and `splits.abi.include`
- `:common-jni` `ndk.abiFilters`
- `:hearth-tts` `abiFilters` + CMake, intersected with ARM64

Clean-build proof (`clean` before each):

| Active ABI | `common-jni` native outputs | `hearth-tts` native outputs |
| --- | --- | --- |
| `arm64-v8a` | only `arm64-v8a` | only `arm64-v8a` |
| `x86` | only `x86` | **none** |

This fixes the reported “common-jni and TTS out of sync in Android Studio” problem.
Important IDE note: after switching active ABI, run **Sync Project with Gradle Files**
and **Clean Project** once; old `build/.cxx` outputs can otherwise make the previous
ABI look active. With no active ABI selected, app/common-jni build all four while TTS
builds ARM64 only — a capability boundary, not a stale task.

---

## 7. CI, release engineering and audit fixes

### 7.1 CI state — read carefully

The PR currently displays a rollup from the **16:41–17:08 UTC** window:

- macOS arm64: **success** 20m38s
- macOS Intel: **success** 16m36s
- Android build: **success** 19m13s
- emulator x86/x86_64/armeabi-v7a: **success**
- Windows: **failure** (parked/expected)

That run belongs to the `7c3be14` era and **predates** the later Android ABI fixes
(`6bfbae4`, `25ebadc`, `9f2b936`). The latest commits were **not CI-waited**. Do not
claim the latest commit is CI-green without rerunning. PR is mergeable but unstable.

### 7.2 Desktop/CI fixes landed

- **Cold cargo cache:** removed `--offline` from the Chatterbox BPE build; `--locked`
  still pins crates. Fresh macOS runners now fetch their locked crates.
- **Optional native libraries:** desktop handle-ownership tests skip cleanly when a
  platform bundle lacks optional Qwen/CT2/backend libs; staged-but-broken still fails.
- **Linux Android CI:** ownership tests skip when no desktop native bundle exists.
- **MOSS Metal probe:** CPU MOSS stage excluded from the shared Metal probe; clean
  `--stage moss --metal` passes.
- **Integration race:** media-upload traversal tolerates the intentional deletion
  race; repeated local runs pass.
- **Handoff validator:** accepts 17 translation records (minimum five families);
  research-only models get an explicit `status=research`/no-runtime block.
- **Release verifier:** validates 10 APKs, signatures, package/version, ABI/ELF,
  16 KB alignment, permissions, pinned native hashes, Ming JNI exports, and now the
  ARM64-only TTS set.

### 7.3 Audit fixes specific to this session

- Desktop Ming: absolute symlink fallback, stale-part relink, locale normalization,
  one-time verification of all 17 pins, host test for replacement/pin mismatch.
- Backend JNI constructor now releases `dlopen` handle/session on failure.
- Desktop TTS controls no longer leak Supertonic speed/steps/voice IDs to other local
  engines; Supertonic-only controls hidden; steps capped 2–12.
- Android TTS module collapse and active-ABI consolidation.
- Documentation corrections (71 cards, 31 native entries, 8 ownership tests, Vosk
  0.3.70 vs desktop 0.3.42, Ming EN+ZH proof, macOS-arm64-only proof).

---

## 8. Verification ledger — what actually passed

### 8.1 Latest local gates (commit `9f2b936`)

| Gate | Result |
| --- | --- |
| `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin` | PASS |
| `:hearth-tts:test` (all adapter tests merged) | PASS |
| `:app:assemblePlayQa :app:assembleFossQa` | PASS |
| `python3 scripts/verify-release.py` | PASS, all 10 QA APKs |
| `python3 scripts/verify-tts-aars.py` | PASS, two AAR boundaries |
| Active-ABI arm64 clean native build | `common-jni` arm64; `hearth-tts` arm64 |
| Active-ABI x86 clean native build | `common-jni` x86; `hearth-tts` none |
| Device `MingLocalDeviceTest` | OK, 12.029 s, frames 88200 |
| `scripts/desktop/generate-catalog.py --check` | 71 cards |
| `scripts/validate-model-handoff.py` | 15 records / 80 components |
| Desktop full suite on macOS arm64 | PASS (earlier full gate) |
| macOS/Android CI | Green at `7c3be14`; latest commits not awaited |

### 8.2 What is *not* proven

- Latest-commit CI (post `7c3be14`).
- Owner visual inspection of the real desktop overlay.
- Packaged macOS/Windows installer behavior.
- Owner human listening/quality on the phone.
- FOSS-on-device model import.
- Live-caption read-aloud on the phone.
- Windows native/runtime support.
- S25/S22 sustained heat, battery, latency and quality.
- A corpus-level WER/chrF score is not claimed for any engine.
- No ASan coverage on this macOS 26.5 host: the ASan speech runner hangs at ASan
  initialization; non-ASan `speech_test` passes 105 checks.

### 8.3 Historical CI/validation facts

- At the 0.27.x baseline: 674 app tests per Play/FOSS config; 136 common-jni;
  9 TTS core; 7 Supertonic; 10 Chatterbox; 3 each for Magpie/OmniVoice/MOSS/Oute.
- 0.27.1/0.27.2/0.27.3 validation is documented in `validation.md`,
  `settings-back-validation.md` and the JSON receipts; it is not re-created by the
  current Android module collapse.
- `verify-release.py` passed all 10 APKs before and after the TTS changes.
- `verify-tts-aars.py` previously checked eight AARs; it now checks two.

---

## 9. Docs and release notes

Updated in this two-day window, among others:

- `docs/handoff/desktop-final-2026-10-22.md` — authoritative desktop merge handoff.
- `docs/handoff/desktop-kmp-2026-10-21.md` — historical pre-redesign checkpoint.
- `docs/handoff/validation.md` — model/SDK validation baseline.
- `docs/desktop-native-verification.md` — exact real-model smokes/commands.
- `docs/desktop-engine-matrix.md`, `docs/desktop-engine-sharing.md`,
  `docs/desktop.md`, `docs/ming-3.0.md`, `docs/ming-android-device.md`,
  `docs/ming-android-ui.md`.
- `docs/android-abis-and-modules.md` — one-module shape, ABI matrix, module history,
  Android Studio sync/clean guidance.
- `docs/tts-sdk.md` — one `hearth-tts` AAR + shared ONNX runtime.
- `docs/releases/0.28.0.md` — draft release body and sizes.
- `docs/handoff/models/*.json` — handoff metadata, including 12 research-only
  translation entries with explicit no-runtime status.
- `docs/handoff/desktop-final-2026-10-22.md` records the module collapse and CI
  distinction; it also contains the owner checklist.

### 9.1 Release body inconsistency to fix before publishing

`docs/releases/0.28.0.md` top recommendation table says **Play universal 201.2 MB**;
the complete table and actual artifact are **201.1 MB**. Fix the body (and re-upload
the draft if desired) before publishing. Do not treat this as a build failure.

---

## 10. Known gaps and constraints

1. **Windows is parked.** Do not “fix” it opportunistically, claim support, or make a
   macOS merge conditional on the Windows job.
2. **No packaged desktop installer validation.** No packaged app/installer exists in
   this tree; `check-packaged.py` was not run.
3. **Owner overlay check outstanding.** Headless renders and HTTP/SSE are not the same
   as a real always-on-top window.
4. **Latest CI not green-waited.** Re-run before merge; the visible PR rollup is old.
5. **Release draft not production-signed.** QA debug/test certificate only.
6. **TTS x86 absence is intentional.** All TTS adapters are ARM64-only; do not
   reintroduce wrapper-only x86 packaging.
7. **Old adapter source directories remain** as source folders. They are not Gradle
   modules; a cosmetic physical move is optional.
8. **Ming is experimental**, one-shot, EN/ZH, ~1 GB, reloads per sentence.
9. **Chatterbox/Magpie are experimental/slow**; no live-reader claim.
10. **Vosk Spanish** quality/repeat stability is unverified.
11. **OuteTTS 1B not shipped** due to NC-SA/Llama terms and missing attribution.
12. **No corpus WER/chrF or phone performance claim.**
13. **No desktop tag/release** and no installer signing/notarization.
14. **No model weights/keys** committed; downloads/imports only.

---

## 11. Owner actions / next steps

### Before merge

1. Open the real desktop overlay window on macOS; verify always-on-top, undecorated,
   placement, readability and click-through.
2. Optional but recommended: install a FOSS APK, enable read-aloud on live captions,
   and listen to Ming English/Chinese; import models offline if testing FOSS.
3. Run the CI workflows again at `9f2b936` (or the current head) if CI evidence is
   required; do not rely on the stale rollup.
4. If the overlay is correct, merge PR #4; Windows can remain parked.

### After merge

1. Publish the 0.28.0 QA draft only if desired; it is test-signed.
2. Fix the 201.2→201.1 release-note line if publishing.
3. Optionally move the adapter source folders physically under `hearth-tts/adapters`.
4. Treat the Android TTS SDK as one AAR + shared ONNX AAR in any external integration.
5. Keep Windows parked until the owner explicitly resumes it.

### Do not

- Publish the draft before owner acceptance.
- Commit models, signing keys, `local.properties` or build outputs.
- Re-add per-family Gradle modules.
- Re-add x86 TTS wrappers without real runtimes.
- Claim Windows/macOS installer/quality support that was not tested.
- Use adb/device automation unless the owner explicitly asks.

---

## 12. Restart commands

```sh
# Android host gate
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin

# QA APKs + full verifier
./gradlew :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py

# One-module TTS tests / AAR boundaries
./gradlew :hearth-tts:test
./gradlew :hearth-tts:assembleRelease :hearth-onnx-runtime:assembleRelease
python3 scripts/verify-tts-aars.py

# Active ABI (application + common-jni + TTS)
./gradlew :app:assemblePlayQa -Pandroid.injected.build.abi=arm64-v8a
./gradlew :app:assemblePlayQa -Pandroid.injected.build.abi=x86
./gradlew :common-jni:clean :common-jni:externalNativeBuildDebug -Pandroid.injected.build.abi=x86
./gradlew :hearth-tts:clean :hearth-tts:externalNativeBuildDebug -Pandroid.injected.build.abi=arm64-v8a

# Desktop
python3 scripts/desktop/prepare-dependencies.py
python3 scripts/desktop/build-native.py --stage all
./gradlew -PhearthDesktopOnly=true :hearth-core:desktopTest :desktop-runtime:test \
  :desktop-data:test :desktop-media:test :desktop-integration:test :desktopApp:desktopTest
python3 scripts/desktop/generate-catalog.py --check

# Metadata
python3 scripts/validate-model-handoff.py
```

For Android Studio ABI switching: **Sync Project with Gradle Files** + **Clean
Project** once; old `.cxx` outputs can otherwise look stale/wrong.

---

## 13. Files and terms

### Important files

- `settings.gradle.kts` — one `:hearth-tts`; shared `:hearth-onnx-runtime`.
- `build.gradle.kts` — root active ABI (`hearthActiveAbis`).
- `app/build.gradle.kts` — app ABI filters/splits; `:hearth-tts` dependency.
- `common-jni/build.gradle.kts` — active ABI.
- `hearth-tts/build.gradle.kts` — merged source sets, CMake, ARM64 intersection.
- `hearth-tts/src/main/cpp/CMakeLists.txt` — one CMake project adding all adapter
  CMake subprojects.
- `hearth-tts/provenance/jniLibs/*-SHA256SUMS` — moved family runtime hashes.
- `scripts/verify-release.py` — APK delivery gate.
- `scripts/verify-tts-aars.py` — two-AAR SDK boundary gate.
- `.github/workflows/android.yml` — one-module builds and SDK checks.
- `scripts/desktop/*` — pinned native toolchain and desktop packaging.
- `docs/handoff/desktop-final-2026-10-22.md` — desktop merge authority.
- `docs/android-abis-and-modules.md` — ABI/module authority.
- `docs/tts-sdk.md` — SDK integration authority.
- `docs/releases/0.28.0.md` — draft release body.

### Terms

- `HearthSpeechBackend` C ABI v2 — shared native speech contract.
- `MING_ABI 1` — Ming JNI ABI.
- `parts/` — Ming flat bundle hardlink/symlink staging.
- QA — test/debug-signed APK flavor/build type, package `.qa`.
- `:hearth-tts` — the single Android TTS library after consolidation.
- Active ABI — Android Studio-injected ABI, overridable with `-PhearthAbis=...`.

---

## 14. Workstream attribution and process lessons

### What other agents did (best-effort reconstruction)

- **Oct 20 agents:** model-expansion foundation, reusable TTS SDK, compact
  translation, Magpie/Ming adapter groundwork, 0.27.3 prep, Android settings/UX fixes.
- **Oct 21 agents:** KMP desktop initial workspace, Python/Windows staging fix,
  approved-design UI rebuild/port, 17-screen/overlay renders, shared JNI/pipeline
  proofs, model mapping and GGUF expansion.
- **Oct 22 early agents:** Qwen3-ASR, CTranslate2, TranscribePsy, Nemotron, Moonshine,
  MOSS, Ming desktop/Android, Omnilingual/Phonon, Vosk, OmniVoice, Magpie, Chatterbox,
  docs/handoff, 0.28.0 APK draft, release packaging.
- **Oct 22 late (current session):** audit fixes, CI hardening, handoff validator,
  final desktop/CI handoff, x86 TTS wrapper fix, one-module TTS collapse, active-ABI
  centralization, release refresh, local verification.

### Process lessons

1. **Module proliferation was wrong for the app.** Separate AAR projects are a
   packaging choice, not an app architecture. One `:hearth-tts` is correct.
2. **Never hardcode an ABI in a module that participates in IDE builds.** Use one
   active-ABI source and intersect capability.
3. **Do not ship a JNI wrapper without its runtime.** x86 TTS wrappers were unusable;
   ARM64-only is honest.
4. **Owner visual/listening acceptance cannot be replaced by headless renders or
   synthetic PCM.**
5. **CI state must be tied to a commit.** The visible PR checks are stale relative to
   the final commits; report that explicitly.
6. **Keep the release draft unpublished** until the owner accepts the overlay and
   decides about Android publication.
7. **Do not infer approval from git author identity.**

---

## Appendix A — commit ledger by window

The full 80-commit ledger is available with:

```sh
git log --since='2026-10-20 00:00' --reverse --date=iso --pretty=format:'%h %ad %s'
```

Grouped key commits:

### Oct 20 — foundation and 0.27.3

`09de08f` reusable TTS foundation; `539e0c0` ARM64 speech/Magpie/Ming adapters;
`d5f1396` local voice-family gating; `2ecb473` QuickMT tokenization/weight checks;
`ecd1dc0` bilingual runtimes; `76d8125` readiness/CI; `5583f97` release grouping;
`1b1a27c` settings back/scroll; `8ecd650` APK signer cert parsing; `ba77c13` 0.27.3
settings; `3bb02ed` emulator ABI probes.

### Oct 21 — desktop workspace, UI and native proof

`13429dd` KMP workspace; `1768964` Windows staging handoff; `e6e62bf` whisper-vulkan
snapshot; `d02dd75` Home/foundation; `a3ce6c5` Live captions; `383f506` overlay studio;
`ed1d85f` model library; `e14adc1` presets/downloads; `a0ed1bd` speech settings;
`726feee` translation/voice; `b60e5ce` appearance/files; `f4a4d1c` connections;
`f33c6f7` integrations/typed translation; `54237f5` companion screens/delete old UI;
`b4adede`/`4a07e88`/`d32f85e` section splits; `857cb55` Whisper real smoke; `7fae392`
speech+voice; `7fffdb7` speech+MT+voice; `ce60d51` JNI ownership tests; `3fd9941`
native verification doc; `fcd2159` GGUF translators; `0f5a7eb` model map; `5297cc4`
14 GGUF + ONNX plan; `1790055` ONNX blockers.

### Oct 22 — engines, Ming, docs, release, audit, module/ABI

`3eb4649` Qwen3-ASR; `9c769df` CT2; `c0e1b01` TranscribePsy; `5093c8f` Nemotron;
`02bf208` Moonshine bridge; `a542924` shared contract docs; `b84a5b5` Windows CRT test;
`76910f2` MOSS; `22c9eee` hosted Ming; `77b92a5` Omnilingual/Phonon; `f9a8097` Vosk;
`7c2e50d` Vosk tree pin; `4adce60` desktop Ming; `3e87403` Ming install; `af0a362`
Android Ming JNI; `db26ee5` OBS overlay; `e137593` caption layer; `29add52` ming-wasm
proof; `24e5ea9` Android device proof; `308bfd9` 17 screens; `1a2f41e` Android Ming;
`ba4d449` Moonshine variants/OmniVoice; `6305faf` Android Ming gate; `05c39bb` UI
proof; `f875d7c` attestations/Ming DSO; `2b5ff21` Magpie; `4c447c9` unstripped Ming;
`4390f7e` Oute hold; `b7e5c81` Chatterbox; `8f6129f` NeMo/Magpie stage; `7692841`
desktop handoff; `9969b83` 0.28.0 APK draft; `cb19b8a` notes; `c0cc896` CI hardening;
`876784c` Ming/backend/TTS audit; `6ed7728` integration race; `69960c1` MOSS Metal;
`45d4945` optional-lib skip; `7c3be14` handoff validator; `6e61b55` green CI/docs;
`6bfbae4` x86 TTS wrapper fix; `25ebadc` one-module collapse; `9f2b936` active ABI.

---

## Appendix B — current version/tool pins

- Kotlin 2.2.0; Compose Multiplatform 1.9.0; JDK 17.
- Android compile/target SDK 36; min SDK 28; NDK 27.0.12077973; CMake 3.22.1.
- Android app 0.28.0 / code 94.
- Ming fusor-ai 0.1.2 / `MING_ABI 1`; 44.1 kHz; EN/ZH.
- Desktop Vosk 0.3.42; Android Vosk 0.3.70 (documented difference).
- ONNX Runtime 1.20.0 (shared AAR).
- Catalog 71 cards.
- TTS AAR 8.46 MB / 14 native libs; ONNX AAR 26.5 MB / 4 ABIs.
- Release draft: 15 assets; source commit 9f2b936; QA signature; unpublished.

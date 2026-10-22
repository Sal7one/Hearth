# Hearth desktop — final handoff (2026-10-22)

This is the authoritative state of `codex/hearth-desktop-kmp` / PR #4.
The older [desktop KMP handoff](desktop-kmp-2026-10-21.md) is historical; its
"work stopped / owner rejected UI / Windows needs validation" header is
superseded by this file.

## Merge position

- **macOS arm64: feature-complete and release-verified.**
- **macOS arm64, macOS Intel and Android CI are green** on the code commit
  (`macos-15` 20m38s, `macos-15-intel` 16m36s, Android 19m13s). Windows is the only
  red job and is **parked by the owner**; do not block a macOS merge on it or
  describe Windows as working.
- A **0.28.0 / code 94 Android QA APK draft** exists (10 signed APKs + source,
  checksums, provenance and validation evidence); it is not published. Publish it
  after the desktop overlay check if desired.
- **Owner acceptance still required:** open the real always-on-top overlay window.
  All 17 screens and the overlay caption layer are headless-rendered; the actual
  window is the only desktop visual check not performed.
- No desktop tag or release was created. The Android 0.28.0 QA draft is separate
  and unpublished. Models are downloads, never committed.

## What is in the branch

| Area | State |
| --- | --- |
| Local STT | Whisper, Qwen3-ASR, TranscribePsy, Nemotron 3.5, Vosk, Moonshine 10/10, Omnilingual, Phonon |
| Translation | llama.cpp GGUF and CTranslate2 QuickMT/SMaLL-100/EuroLLM/Riva catalog paths |
| Local TTS | Supertonic, MOSS-TTS Nano, Ming-3.0 local, OmniVoice, Magpie, Chatterbox V3 |
| Ming local | Desktop JNI + Android `hearth-tts-ming`; 850 MB bundle; English/Chinese; one-shot |
| Android Ming UI | Real device flow: enable, bundle staging, missing count, four AudioTrack runs, Chinese, persistence, gating |
| Captions/overlay | 17/17 headless desktop renders, caption layer render, real overlay HTTP/SSE |
| Catalog | 71 cards, generated from engine-owned pins |
| Release gate | `verify-release.py` PASS on all 10 QA APKs, including pinned Ming DSOs |
| Native build | `prepare-dependencies.py` + `build-native.py --stage all`; Magpie built bit-identically at the pinned NeMo/ggml revisions |

## Measured proofs

- Ming desktop: 61,740 frames, 44.1 kHz, real 850 MB install.
- Ming Android: in-APK `System.loadLibrary`/JNI under SELinux; 88,200 frames in-app;
  `+dotprod`/4 threads RTF **2.06–2.12**; peak RSS **1.02–1.28 GB**; scalar RTF 34;
  Whisper transcribes the generated English correctly.
- Vosk: EN exact; DE/FR/RU/ZH app-path smokes; Spanish quality caveat.
- Omnilingual/Phonon: exact English transcript.
- Moonshine: 10/10 variants, native-script transcripts; Arabic/Korean/Japanese/Chinese
  caveats recorded.
- OmniVoice: 24 kHz, EN/AR non-silent, RTF 4.1–5.3 warm.
- Magpie: 22.05 kHz, EN/AR non-silent, RTF 1.10 warm; bounded 31 MB range install.
- Chatterbox: 24 kHz, EN/AR non-silent, first-use graph/Perth derivation proven,
  RTF 4.68 warm / 22.97 cold; experimental.
- OuteTTS 1B: technical PASS, **not shipped** due to NC-SA + Llama terms and missing
  required attribution. Receipt in `docs/research/oute-desktop-smoke-2026-10-22.json`.

## Commands used for final verification

```sh
python3 scripts/desktop/prepare-dependencies.py
python3 scripts/desktop/build-native.py --stage all
./gradlew -PhearthDesktopOnly=true :hearth-core:desktopTest :desktop-runtime:test \
  :desktop-data:test :desktop-media:test :desktop-integration:test :desktopApp:desktopTest
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
./gradlew :app:assemblePlayQa :app:assembleFossQa
python3 scripts/desktop/generate-catalog.py --check
python3 scripts/verify-release.py
```

The opt-in real-model smokes are documented in
[desktop-native-verification.md](../desktop-native-verification.md); they never
download or play audio by default.

## Post-audit fixes and pipeline hardening

- `DesktopNativeOwnershipTest` now skips cleanly on hosts without a staged desktop
  native bundle (Linux Android CI) and fails loudly if a staged bundle cannot load.
- The Chatterbox pinned byte-BPE build no longer uses `--offline`, so a fresh CI
  runner fetches its locked crates instead of failing with a misleading
  "no matching package" error. `--locked` still pins `Cargo.lock`.
- `build-native.py --stage all` was verified end to end on macOS arm64, including
  Magpie, Ming, Vosk, OmniVoice and Chatterbox; Magpie's NeMo/llama.cpp pins are
  provisioned by `prepare-dependencies.py`.
- Desktop Ming staging now uses absolute symlinks when hardlinks are unavailable,
  relinks replaced/stale parts, normalizes `en-US`/`zh-Hans`, verifies the 17
  SHA-256 pins once at the engine boundary, and has a host test for replacement
  and pin mismatch. Android already had the equivalent checks.
- The desktop backend JNI releases its `dlopen` handle and any session when the
  constructor throws; previously a failed backend load leaked the library.
- Desktop TTS controls no longer pass Supertonic speed/steps/voice IDs to other
  local engines; the voice picker hides Supertonic-only controls, defaults to the
  engine default voice, and caps steps at 2–12.
- `DesktopIntegrationServerTest.regularFiles` tolerates the intentional deletion
  race during media-upload admission/cleanup, removing the Linux CI flake.
- The CPU MOSS stage is excluded from the shared Metal probe; the macOS
  `--metal` build no longer tries to link `metal_device.mm` into a target that
  does not link Metal/Foundation.
- Optional desktop libraries absent from a platform bundle (Intel macOS has no
  Qwen3-ASR/CT2/backend bridge) now skip their handle tests; a present-but-broken
  library still fails.
- The handoff validator accepts the expanded 17-entry translation inventory
  (minimum five families, research-only entries carry an explicit no-runtime block).

**Verification:** Android host tests/QA compiles, both QA APK sets,
`verify-release.py` (10 APKs), the full desktop suite, `generate-catalog.py
--check`, and the desktop script unittests pass. CI is green for macOS arm64,
macOS Intel and the Android build; Windows remains red by design and parked.

## Owner checks before merge

1. Open the desktop overlay window and confirm always-on-top, undecorated,
   placement, readability and click-through.
2. Optional: install a FOSS APK on the phone, enable read-aloud on live captions,
   and listen to Ming English/Chinese.
3. If the window is correct, merge PR #4; Windows can remain parked.

## Known constraints

- Ming is experimental, one-shot, English/Chinese only, ~1 GB, and reloads the
  bundle per utterance.
- Chatterbox and Magpie are experimental and slow; no live-reader claim.
- The release verifier checks APKs; no packaged macOS installer (`check-packaged.py`)
  was validated because no packaged app exists in this tree.
- Windows CRT staging is still the known CI failure; leave it parked.

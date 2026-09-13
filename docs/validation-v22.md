# Hearth 0.10.0 / v22 validation

Date: 2026-09-13. Scope: shared Android/native/server read-aloud, Type to translate,
traveler/camera/caption wiring, model distribution, flavors and release contents.
The original large Hearth repository was inspected/read only and remains unchanged.

## Automated checks

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin`: PASS.
  188 app tests per flavor/build type (one intentional opposite-flavor skip each),
  72 common-jni tests each in debug/release; no failures/errors.
- Typed controller tests execute the production scheduler with controlled engines:
  debounce, one latest pending edit, no stale output, background/resume, cancellation,
  closure, local lease release and actual inference errors.
- New voice catalog/import tests check pinned assets and cancelled/unsafe imports;
  native text/WAV tests check Arabic/NFKD, surrogate-safe chunks and PCM bounds.
  Existing benchmark WAV tests pass through the extracted shared parser.
- New remote voice tests check capabilities, unsupported languages/voices, HTTPS
  input, credential redaction, no redirects, offline rejection, response bounds,
  coroutine cancellation and closed connection ownership.
- Python bridge: **8 tests PASS**, including real local HTTP protocol exchanges,
  auth, model/language/voice rejection, busy/recovery, error propagation, and mocked
  Qwen/Chatterbox/Fish adapter API contracts. No model inference is claimed by these
  mocked adapter tests.
- Common host suite: five utility programs PASS; speech 50 checks PASS;
  Vosk loader/symbol isolation PASS; OCR geometry/CTC 10 checks PASS;
  new voice input/timing bounds PASS under ASan/UBSan. Speech packaging 5 tests PASS.
- Android native debug build, both minified QA APKs and both unsigned release APKs
  pass. Artifact verification checks both flavors, exact vendor binary hashes,
  dependency closure, 16 KiB ELF alignment, model/code notices and permissions.
  No extra ONNX DSO is added; the existing public 1.20.0 runtime serves Supertonic.

## Actual native model execution

Pinned Supertonic 3 revision `3cadd1ee6394adea1bd021217a0e650ede09a323`, F1,
5 quality steps, speed 1.0. The production C++ adapter synthesized both English
and Arabic on the development host using ONNX Runtime 1.30.0. English generated
1.998 seconds of audio in 0.348 seconds; Arabic generated 4.260 seconds in
0.645 seconds, **excluding load**. A cancelled session rejected subsequent synthesis.
These are short host smoke checks, not Android latency or comparative benchmarks.

Owner-authorized device checks used a Samsung **SM-S908E (S22 Ultra)**,
Android 16 / API 36, arm64. This is not the SM-S938B in an older owner crash report.
The device uses the APK's ONNX Runtime 1.20.0. Imported the full publisher-layout
Supertonic ZIP from `Downloads/Hearth/models` through the app file picker; it verified
and installed the engine and all ten styles. The original ZIP remains in that
public folder. No custom manifest or computer-side packaging tool was required.

## Device observations

- Supertonic English preview loads, synthesizes, plays and completes without a native
  crash. Android AudioFlinger records the app's 44,100 Hz float mono track and its
  removal after playback.
- Type to translate: “Where is the nearest train station?” automatically produced
  “أين تقع أقرب محطة قطار؟” through the already installed ML Kit packs.
  Both original and Arabic translation Play actions ran through Supertonic; Stop
  followed by another Play recovered. No language fallback or native crash was seen.
- Switched to installed Android voices and played the Arabic translation through
  the same UI. The traveler conversation created a typed English→Arabic turn,
  retained both texts, and its Play action used the shared player. Face to face
  retained the turn and speaking status with the opposite side rotated.
- Opened Voices from typing Options, changed the engine, then Back returned to the
  typing page with the same draft and regenerated translation. New native/model
  resources are released when leaving their owning page; the draft uses saved
  composition state, not a persistent text-history file.
- Camera imported the Russian sample, recognized its original text (205 ms reported
  OCR time), translated it through installed ML Kit packs, and Play translation
  activated the shared Android voice player. Original/result controls remained visible.
- Installed foss QA over play without uninstalling. Its voice page offered Android
  and Supertonic only, retained the imported engine/styles, and ran Supertonic
  preview. No self-hosted or download action was exposed. The cloud-capable QA
  build was restored afterward; installed models and history were preserved.
- The typing page was visually inspected in the phone's dark theme and enlarged
  display configuration. Source/result text, swap, options, copy and Play/Stop were
  readable; UI automation also found the named controls and language descriptions.

## Limits and remaining validation

- **Qwen3-TTS, Chatterbox and Fish are implemented self-hosted adapters, not tested
  Android-native models.** Their upstream synthesis packages/weights were not run
  in the Android APK or provisioned for live-server synthesis in this session.
  The adapter contracts and transport were tested; a deployment must validate its
  own resolved package/model versions, voices and actual output quality.
- Paid cloud translations were not called with new credentials. The typing page
  reuses existing provider adapters; transport/protocol regressions passed, and
  actual local translation was exercised. The older OpenAI-compatible caption TTS
  path remains separately configured; it was not live-tested against a paid account.
- The new voice is loaded per utterance and reused within its chunks, then closed.
  Cold startup and joint ASR/translation/TTS thermals remain distinct from warm
  synthesis speed. No all-language, long-running battery or blind voice-quality
  study is claimed. Qwen Base excludes Arabic; Supertonic excludes Chinese.
- Full TalkBack traversal and accessibility-device testing remain a manual release
  check. The shared controls include labels, selected semantics, scalable text and
  live error announcements; UI hierarchy inspection is not a TalkBack certification.
- For microphone captions, audible read-aloud can enter the microphone again;
  use headphones. Traveler modes stop listening before speaking a completed turn.

See [voice setup, sources and architecture](voices.md). Model weights, research
checkouts and test audio are ignored and excluded from the source archive.

## Publication checks

Both QA and unsigned release artifacts passed `scripts/verify-release.py` for native
closure/hashes/alignment, the packaged voice notices and flavor permissions.
Gitleaks 8.30.1 found no leaks in the existing 29-commit history or staged source-only
snapshot. Research checkouts, model weights, local SDK/signing data and generated
artifacts are not tracked. Source is archived from Git, not the working directory.
The QA APKs use debug signing; production signing and GitHub publication remain
separate release actions.

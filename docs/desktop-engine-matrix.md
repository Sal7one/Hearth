# Desktop engine matrix

What each model family needs before it can run on macOS/Windows like it does on
Android. Status is what has been *executed*, not what is planned.

Last updated when Moonshine tiny.en passed through the shared speech backend bridge on macOS arm64.

## Working today on desktop

| Family | Models | Desktop engine | Verified |
| --- | --- | --- | --- |
| Whisper (ggml) | 27 | `hearth_desktop_whisper` (whisper.cpp, Metal) | real speech → text, `DesktopWhisperRealSmokeTest` |
| Qwen3-ASR (sherpa-onnx) | 1 | `hearth_desktop_qwen3_asr` (static sherpa-onnx 1.13.7, CPU ONNX Runtime 1.20.1) | real speech → text, `DesktopQwenRealSmokeTest`: 4.65 s audio, 406 ms decode, exact transcript (macOS arm64) |
| Supertonic 3 (TTS) | 1 | `hearth_supertonic_jni` (ONNX Runtime) | capability + synthesis path via `DesktopSupertonicRealSmokeTest` (needs model dir) |
| GGUF translation | 14 | `libtransiber_translation` (llama.cpp) | MiLMMT-46 proven EN→AR in `DesktopFullPipelineRealSmokeTest` |
| CTranslate2 translation | 3 downloadable cards (7 pinned specs) | `hearth_desktop_ct2` (static CTranslate2 4.8.3 + SentencePiece 0.2.0 + Rust BPE) | QuickMT EN→AR real translation, `DesktopCt2RealSmokeTest`: `يفتح المتحف في الساعة التاسعة صباحًا.` (111 ms) |
| TranscribePsy (ggml/parakeet) | 1 | `hearth_desktop_backend_speech` + `libhearth_transcribepsy.dylib` (static parakeet/ggml) | real speech → text, `DesktopTranscribePsyRealSmokeTest`: exact 4.65 s transcript, 75 ms warm decode |
| Nemotron 3.5 (NeMo GGML) | 1 | `hearth_desktop_backend_speech` + `libhearth_nemotron.dylib` (static NeMo-Speech.cpp 0.1.0 + SentencePiece) | real speech → text, `DesktopNemotronRealSmokeTest`: exact 4.65 s transcript, 573 ms decode |
| Moonshine tiny.en (sherpa-onnx) | 1 | `hearth_desktop_qwen3_asr` + the shared `hearth_desktop_backend_speech` bridge (`hearth_moonshine_backend_v1`) | real speech → text, `DesktopMoonshineRealSmokeTest`: exact 4.65 s transcript, 32 ms decode; no new engine code |
| MOSS-TTS Nano 100M | 1 (14 pinned files) | `hearth_desktop_moss` (static SentencePiece 0.2.0 + the shipped ONNX Runtime 1.20.1) | real 48 kHz non-silent WAV, `DesktopMossRealSmokeTest`: 20,481 samples / peak 0.516 / RMS 0.0996 through the app path; direct JNI 61,440 samples |

## Missing on desktop

The shared ABI and the family-versus-model rule are documented in
[docs/desktop-engine-sharing.md](desktop-engine-sharing.md).

| Family | Models | Android implementation | What a desktop port needs |
| --- | --- | --- | --- |
| sherpa-onnx speech — remaining Moonshine ×8, Omnilingual, Phonon | 10 | `qwen_backend.cpp` already exports all three backend entry points | one dylib and one bridge already exist; each remaining family needs only model pins, a catalog card, an engine branch and a real-audio smoke |
| Vosk | 6 | `common-jni/src/main/cpp/vosk` | build Vosk/Kaldi for macOS + Windows; no prebuilt exists in this repo |
| CTranslate2 pair coverage | 3 desktop cards: QuickMT EN↔AR, SMaLL-100 (100 languages) | `common-jni/src/main/cpp/translation/ct2` + `Ct2Catalog.kt` | four Jisr 50M specs are pinned but `downloadEnabled=false`, and the requested EN↔DE/ES/FR/RU/ZH pair models are not present or pinned in this repo |
| TTS — Chatterbox, Magpie, OmniVoice, Oute | 4 | `hearth-tts-*` modules | each family has its own architecture and runtime; MOSS is the first shared desktop TTS runtime |

## What Qwen3-ASR proved

- The pinned sherpa-onnx checkout (`HEARTH_QWEN_REVISION`,
  `210f340bcfdfd5b9ad6b24245e77a934d6c28f1b`) compiles statically for macOS arm64
  against the ONNX Runtime 1.20.1 dylib the desktop bundle already ships for
  Supertonic. The link probe and a real decode both ran; no static ORT copy is linked.
- `onnx/onnx_engine.cpp` compiles for macOS unchanged and is linked into the Qwen
  dylib as the shared ONNX framework, but the Qwen path itself uses the existing
  `speech/backends/qwen_backend.cpp` sherpa adapter.
- The Android backend needed no source change: the desktop JNI wraps its C ABI with
  the same lease/retire handle discipline as Whisper.
- parakeet.cpp (TranscribePsy), NeMo-Speech.cpp (Nemotron) and the sherpa family
  all run through one lease-based backend bridge that dlopens a hash-verified
  backend dylib and dlsyms an explicit entry symbol. Moonshine tiny.en is proven;
  Omnilingual and Phonon already export their entry points and need model cards and
  smokes, not new C++ engines.

## Port checklist for one external-SDK family

1. Extend `scripts/desktop/build-native.py` to build the external SDK statically,
   compile the backend against the staged ORT SDK, and emit hashed entries in
   `manifest.json` (done for sherpa-onnx in the `onnx` stage).
2. Reuse the existing `DesktopBackendSpeechNative` bridge: pass the backend dylib,
   the entry symbol, and any encoder/decoder/tokenizer paths. Only write a new JNI
   wrapper when the SDK does not expose a `HearthSpeechBackend` C ABI.
3. Register the engine id in `DesktopEngines.capabilities()` and reuse the
   `DesktopBackendSpeechEngine` path.
4. Add catalog entries with pinned hash + size and the correct `engineId`.
5. Prove it: opt-in smoke test with a real model and real audio, asserting the
   transcript — the same bar used for Whisper and MiLMMT.

Each family ships as its own commit with its own proof; no engine is added to the
catalog before it has a passing smoke.

## Not claimed

- Windows for any of the above. No Windows host has run this code.
- Qwen3-ASR auto language detection. The upstream C API does not populate the
  detected language without the Android build patch; the desktop engine reports an
  explicit source language and leaves Auto unknown. Transcription still works.
- The desktop CTranslate2 catalog has three downloadable cards from the pinned
  `Ct2Catalog` (QuickMT EN→AR and AR→EN, SMaLL-100); it is not the 14-pair matrix.
  The four Jisr specs are pinned but download-disabled, and no EN↔DE/ES/FR/RU/ZH
  CT2 pair pins exist in the repo. Windows/x64 skips the `ct2` stage.
- The Android catalog and the desktop catalog intentionally differ: Android can run
  families desktop cannot yet execute, and listing an unrunnable model would be a
  worse lie than a shorter list.

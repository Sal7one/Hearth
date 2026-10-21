# Desktop engine matrix

What each model family needs before it can run on macOS/Windows like it does on
Android. Status is what has been *executed*, not what is planned.

Last updated when the translation catalog was expanded to 8 models.

## Working today on desktop

| Family | Models | Desktop engine | Verified |
| --- | --- | --- | --- |
| Whisper (ggml) | 27 | `hearth_desktop_whisper` (whisper.cpp, Metal) | real speech → text, `DesktopWhisperRealSmokeTest` |
| Supertonic 3 (TTS) | 1 | `hearth_supertonic_jni` (ONNX Runtime) | capability + synthesis path via `DesktopSupertonicRealSmokeTest` (needs model dir) |
| GGUF translation | 14 | `libtransiber_translation` (llama.cpp) | MiLMMT-46 proven EN→AR in `DesktopFullPipelineRealSmokeTest` |

## Missing on desktop

| Family | Models | Android implementation | What a desktop port needs |
| --- | --- | --- | --- |
| ONNX speech — Nemotron | 1 | `speech/backends/nemotron_backend.cpp` | compile `IEngine` framework + backend for macOS, ORT link, JNI surface, Kotlin binding, catalog |
| ONNX speech — Qwen3-ASR | 1 | `speech/backends/qwen_backend.cpp` | same |
| ONNX speech — TranscribePsy | 1 | `speech/backends/transcribepsy_backend.cpp` | same |
| ONNX speech — Moonshine ×9, Omnilingual, Phonon | 11 | **no backend file found** in `common-jni/src/main/cpp/speech/backends` | backend has to be written before any port |
| Vosk | 6 | `common-jni/src/main/cpp/vosk` | build Vosk/Kaldi for macOS + Windows; no prebuilt exists in this repo |
| Marian pairs | 14 | `common-jni/src/main/cpp/marian` (CT2 + SentencePiece) | CTranslate2 build for macOS + Windows, SentencePiece, JNI |
| TTS — Chatterbox, Magpie, Moss, OmniVoice, Oute | 5 | `hearth-tts-*` modules | each family has its own architecture and runtime; five separate engines |

## Why the ONNX block is the right next step

- ONNX Runtime is **already** in the desktop bundle (`libonnxruntime.1.20.1.dylib`)
  because Supertonic depends on it, so the runtime dependency is solved.
- Three backends already exist in C++ (Nemotron, Qwen3-ASR, TranscribePsy) — but each
  one pulls an external SDK that only exists under `research/`:

  | Backend | Missing SDK (verified by compiling it for macOS) |
  | --- | --- |
  | Nemotron | `nemo_speech/asr.h` — NeMo-Speech.cpp |
  | Qwen3-ASR | `sherpa-onnx/c-api/c-api.h` |
  | TranscribePsy | `parakeet/engine.h` |

  `onnx/onnx_engine.cpp` itself compiles cleanly for macOS, so the framework is
  portable; the real cost is building those three C++ projects for macOS and
  Windows before any family can be ported.
- The remaining ONNX families (Moonshine, Omnilingual, Phonon) need their backend
  written first — a larger task than a port.

## Build probes (what was actually tried)

| Target | Result |
| --- | --- |
| `onnx/onnx_engine.cpp` for macOS | compiles to an object, no changes |
| `nemotron_backend.cpp` | stops at `nemo_speech/asr.h` (not in repo, only under `research/`) |
| `qwen_backend.cpp` | stops at `sherpa-onnx/c-api/c-api.h` (not built) |
| `transcribepsy_backend.cpp` | stops at `parakeet/engine.h` (not built) |
| `research/sherpa-onnx` configure | 56 MB tree that fetches asio, cargs and other dependencies over the network via `FetchContent`; not a quick local build |

Conclusion: no ONNX speech engine can be added without first building one of those
external C++ projects for macOS and Windows. Each is its own multi-hour task with
its own toolchain pitfalls, and none of them has been built in this repo yet.

## Port checklist for one ONNX family

1. Extend `scripts/desktop/build-native.py` to compile `onnx/onnx_engine.cpp` plus
   the chosen backend against the staged ORT SDK and emit `*.payload` entries with
   hashes in `manifest.json`.
2. Add a desktop JNI wrapper (`DesktopOnnxSpeechNative`) with the same lease-based
   handle discipline as `DesktopWhisperNative`.
3. Implement `SpeechEngine` over it in `desktop-runtime` and register the engine id
   in `DesktopEngines.capabilities()`.
4. Add catalog entries with pinned hash + size and the correct `engineId`.
5. Prove it: opt-in smoke test with a real model and real audio, asserting the
   transcript — the same bar used for Whisper and MiLMMT.

Each family ships as its own commit with its own proof; no engine is added to the
catalog before it has a passing smoke.

## Not claimed

- Windows for any of the above. No Windows host has run this code.
- The Android catalog and the desktop catalog intentionally differ: Android can run
  families desktop cannot yet execute, and listing an unrunnable model would be a
  worse lie than a shorter list.

# Sharing desktop engines

Desktop engines are shared at three layers. The rule is: **share the contract and
the bridge; build a new dylib only when the SDK family is new.**

## 1. The speech contract

`common-jni/src/main/cpp/speech/backend_abi.h` defines a C-only ABI:

```c
HearthSpeechBackend { create, destroy, push, next, finish, reset, last_error, execution_info }
```

No C++ objects, exceptions or STL cross the dylib boundary. Audio is borrowed for
the duration of `push`; result strings are borrowed until the next call; one
instance is serialized by its session. That is the entire contract an engine
family has to implement.

The desktop side wraps it once in `desktop-runtime/src/main/cpp/backend_speech_jni.cpp`:

- `dlopen` an absolute, hash-verified backend path
- `dlsym` an explicit entry symbol (`hearth_speech_backend_v1` by default, or e.g.
  `hearth_moonshine_backend_v1`)
- validate ABI version, struct size and backend id
- pass model/encoder/decoder/tokenizer/language/threads/endpointing in
  `HearthSpeechConfig`
- own a monotonic handle through `LeaseRegistry`, collect only `is_final` text,
  cancel/retire on destroy

Kotlin has one `DesktopBackendSpeechNative` binding and one
`DesktopBackendSpeechEngine`; every backend that implements the C ABI reuses both.
`DesktopEngines` only adds a descriptor and a create/transcribe branch.

## 2. Families versus models

| Layer | Shared? | Examples |
| --- | --- | --- |
| C ABI + session/handle discipline | yes, once | `HearthSpeechBackend`, `LeaseRegistry` |
| Desktop JNI bridge | yes, once | `hearth_desktop_backend_speech` |
| Kotlin engine + verification | yes, once | `DesktopBackendSpeechEngine` |
| SDK / dylib | per family | sherpa-onnx, parakeet, NeMo-Speech.cpp, Vosk, one TTS runtime |
| Entry point | per family | `hearth_moonshine_backend_v1`, `hearth_speech_backend_v1` |
| Model package, pins, catalog card, UI id | per model | Moonshine ×10, Nemotron 3.5, Vosk en/de/… |

The current sherpa family is the clearest example. `qwen_backend.cpp` already
contains the Qwen, Moonshine, Omnilingual and Phonon adapters. The desktop dylib is
the same one; only the entry symbol and the config paths change:

```
hearth_desktop_qwen3_asr.dylib
  ├─ hearth_speech_backend_v1      → Qwen3-ASR
  ├─ hearth_moonshine_backend_v1   → Moonshine (tokens/encoder/decoder)
  ├─ hearth_omnilingual_backend_v1 → Omnilingual CTC
  └─ hearth_phonon_backend_v1      → Phonon
```

Moonshine was added with **zero new C++ engine code**: one export, ten catalog
archive cards, one engine branch and a real-audio smoke per variant. The engine
verifies the selected card's four component pins (tokens/encoder/decoder/LICENSE).

## 3. Why not one dylib for everything

Parakeet, NeMo, sherpa and Supertonic each statically embed their own ggml or ONNX
Runtime. Linking them into one dylib duplicates symbols, versions and memory, and
makes a license audit harder. One dylib per SDK family, one C ABI between them, is
the stable shape. The bridge can load any number of backends in one process.

NeMo-Speech.cpp now has two desktop consumers: Nemotron ASR and Magpie TTS. They
share a repository, not a dylib. Magpie's adapter builds the pinned TTS C ABI and
the desktop JNI together in `libhearth_desktop_magpie.dylib` (one static ggml from
llama.cpp `bd4f514d`); it is never linked with `libhearth_nemotron.dylib`, and the
ASR bridge is not involved. A Magpie call is `DesktopVoiceCache → VoiceEngine`,
not `HearthSpeechBackend`.

## 4. Adding a family vs adding a model

**New model in a known family** (e.g. another Moonshine or Vosk language):

1. Add the pinned archive/files to `scripts/desktop/generate-catalog.py`.
2. Add the card to `desktop-models.json` (generated).
3. Add the engine branch only if the file layout/config differs.
4. Add a real-audio smoke and claim only languages actually smoked.

No native build, no JNI, no Kotlin engine class.

**New family** (e.g. Vosk or one TTS runtime):

1. Build the SDK pinned/static for macOS arm64; keep it in its own dylib.
2. Implement `HearthSpeechBackend` (or `VoiceEngine` for TTS) as a thin adapter or
   JNI wrapper over the SDK's C API.
3. Export one entry symbol and stage the dylib + notices through
   `scripts/desktop/build-native.py`.
4. Register the engine id and add catalog cards.
5. Prove one model with real audio before adding the rest.

**New runtime within a TTS family** is a family task because each runtime has its
own model architecture; the shared `VoiceEngine` contract is the only commonality.

## 5. Why the earlier commits looked one-off

They were packaging, not engine rewrites:

- CT2 reused `ct2_jni.cpp` + `ct2_model.h`; only a desktop CMake target was new.
- TranscribePsy and Nemotron reused their Android backends unchanged.
- Moonshine reused the entire sherpa backend; only an entry symbol was new.

The per-engine work was the SDK build, model pin, catalog card, UI id and smoke.
Those are real, but they are not C++ engine work — and they are what to budget per
family, not per model.

## 6. Checklist for a family bridge

- [ ] C ABI adapter or existing `HearthSpeechBackend` entry point
- [ ] pinned source revision and model pin (bytes + sha256)
- [ ] static or self-contained dylib, minos 13.0/13.3, one exported entry symbol
- [ ] notices aggregated and staged in the bundle manifest
- [ ] backend id and entry symbol passed from Kotlin
- [ ] model verifier and catalog card
- [ ] real-audio smoke with transcript/timing
- [ ] no runtime downloads, no silent-success fallback

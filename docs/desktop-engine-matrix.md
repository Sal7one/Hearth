# Desktop engine matrix

What each model family needs before it can run on macOS/Windows like it does on
Android. Status is what has been *executed*, not what is planned.

Last updated after the MagpieTTS and Chatterbox Multilingual V3 desktop families passed real non-silent proofs on macOS arm64.

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
| Moonshine (sherpa-onnx) | 10 | `hearth_desktop_qwen3_asr` + the shared `hearth_desktop_backend_speech` bridge (`hearth_moonshine_backend_v1`) | real speech → text, `DesktopMoonshineRealSmokeTest`: 10/10 variants through the bridge and the desktop engine; exact English transcript on tiny.en and base.en, non-empty native-script transcripts for ar/es/ja/ko/uk/vi/zh (33–618 ms decode, 143–244 ms load); no new engine code |
| Omnilingual CTC 300M (sherpa-onnx) | 1 | same dylib/bridge (`hearth_omnilingual_backend_v1`) | real speech → text, `DesktopSherpaFamiliesRealSmokeTest`: exact 4.65 s transcript, 220 ms warm decode |
| Phonon 2 (sherpa-onnx) | 1 | same dylib/bridge (`hearth_phonon_backend_v1`, joiner as `conv_frontend`) | real speech → text, same test: sentence-case transcript, 117 ms warm decode |
| Vosk (Kaldi) | 6 | `hearth_vosk` + pinned official v0.3.42 `libvosk.dylib` (thin arm64), one `vosk-local` engine | EN exact transcript; six app-path language smokes (en/de/fr/es/ru/zh); Spanish quality/repeat stability unverified. Android ships Vosk 0.3.70 through its existing AAR/JNI path; same six model cards, different runtime version and transport. |
| MOSS-TTS Nano 100M | 1 (14 pinned files) | `hearth_desktop_moss` (static SentencePiece 0.2.0 + the shipped ONNX Runtime 1.20.1) | real 48 kHz non-silent WAV, `DesktopMossRealSmokeTest`: 20,481 samples / peak 0.516 / RMS 0.0996 through the app path; direct JNI 61,440 samples |
| Ming-3.0 TTS 0.5B (local) | 1 (17 pinned files) | `libming_jni.dylib` (pinned ming-ffi over fusor-ai 0.1.2) | real 44.1 kHz non-silent WAV, `DesktopMingLocalRealSmokeTest`: 61,740 frames / peak 14,658 / 87% non-zero; English/Chinese only; Android has the same JNI behind an off-by-default flag and a real in-app/UI proof. Desktop threads 1–8 (card default 6); Android caps at 4. |
| OmniVoice | 1 (2 pinned GGUF files) | `hearth_desktop_omnivoice` (static omnivoice.cpp `1a468d8b` + ggml `cc5df49a`, CPU, 24 kHz) | real non-silent WAV through the desktop engine, `DesktopOmniVoiceRealSmokeTest`: EN 1.84 s / peak 0.936 / RMS 0.173 / 39,294 of 44,160 non-zero; AR 1.83 s / peak 0.905 / RMS 0.142 / 38,286 of 43,920 non-zero; 32 MaskGIT steps, RTF 5.25 cold EN (includes load) and 4.26 warm AR, 32 steps, on a busy host; repeats measured 4.1-5.3, with one 14.1 under heavy unrelated build load |
| MagpieTTS Multilingual 357M | 1 (2 pinned GGUF files + 31,076,864 B authenticated tokenizer ranges) | `hearth_desktop_magpie` (static NeMo-Speech.cpp `caa3f47c` + llama.cpp/ggml `bd4f514d`, CPU, 22.05 kHz) | real non-silent WAV through the C ABI, `magpie_probe`: EN 1.393 s / peak 0.5549 / RMS 0.0942 / 30,503 of 30,720 non-zero / RTF 1.91; AR 2.508 s / peak 0.3139 / RMS 0.0433 / 54,716 of 55,296 non-zero / RTF 1.10 (4 threads, includes first-call load); desktop engine `DesktopMagpieRealSmokeTest`: EN 1.486 s / peak 0.441 / RMS 0.0805 / 4.15 cold, AR 2.229 s / peak 0.486 / RMS 0.0606 / 0.97 warm at 2 threads; catalog install `DesktopMagpieCatalogInstallTest` verifies the two GGUF files (local cache in this run) plus the downloaded range-extracted tokenizer and synthesizes non-silent 22.05 kHz WAV |
| Chatterbox Multilingual V3 (TTS) | 1 (11 pinned files, 1,593,714,936 B) | `hearth_desktop_chatterbox` (static Rust byte-BPE 0.22.2 + pinned chatterboxcpp `1a9a4fc0`, shared ONNX Runtime 1.20.1, mandatory native Perth) | real 24 kHz non-silent WAV through the desktop engine, `DesktopChatterboxRealSmokeTest`: EN 1.16 s / 27,840 samples / peak 0.827 / RMS 0.132 / 26,959 non-zero; AR 1.92 s / 46,080 / peak 0.818 / RMS 0.132 / 38,282 non-zero; RTF 28.9 cold EN (includes load) and 4.83 warm AR; direct C ABI probe EN peak 0.74-0.93 / RMS 0.130-0.132, AR peak 0.64-1.05 raw float (the WAV writer clamps) / RMS 0.108-0.109; direct JNI PASS |

## Missing on desktop

The shared ABI and the family-versus-model rule are documented in
[docs/desktop-engine-sharing.md](desktop-engine-sharing.md).

| Family | Models | Android implementation | What a desktop port needs |
| --- | --- | --- | --- |
| CTranslate2 pair coverage | 3 desktop cards: QuickMT EN↔AR, SMaLL-100 (100 languages) | `common-jni/src/main/cpp/translation/ct2` + `Ct2Catalog.kt` | four Jisr 50M specs are pinned but `downloadEnabled=false`, and the requested EN↔DE/ES/FR/RU/ZH pair models are not present or pinned in this repo |
| TTS — Oute | 1 hold | `hearth-tts-*` modules | OuteTTS 1B was proven technically but is **not shipped** (NC-SA + Llama terms and missing required attribution). The 0.6B Apache-2.0 variant is a separate future conversion. |

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
  backend dylib and dlsyms an explicit entry symbol. The sherpa family runs ten
  Moonshine variants, Omnilingual and Phonon through that one bridge; a new model
  is pins, a card and a smoke, not a new C++ engine.

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

## What the OmniVoice proof shows

- `hearth-tts-omnivoice` shipped a C ABI (`ov_*`, ABI v4) and a JNI adapter; the
  desktop stage links the same pinned runtime sources and adds one direct-link
  `omnivoice_jni.cpp` with no model or Python dependency.
- OmniVoice statically owns its own ggml build (`cc5df49a`) in
  `libhearth_desktop_omnivoice.dylib`. It is not linked into whisper, parakeet,
  NeMo or sherpa, and those dylibs are not linked into it. No ONNX Runtime is
  involved.
- Every asset is a direct pinned download (2 files, 945,284,608 B); there are no
  derived assets. The generator is noncommercial and the codec has separate
  community terms; both are recorded in the card and notices.
- Audio is natively 24 kHz mono. The smoke also writes an explicitly derived
  48 kHz compatibility WAV with 2x linear interpolation; it is never reported as
  native engine output.

## What the Magpie proof shows

- Magpie is a second NeMo-Speech.cpp desktop consumer, but a separate dylib from the
  Nemotron ASR backend. `libhearth_desktop_magpie.dylib` contains the pinned TTS C ABI
  (`caa3f47c`), one static llama.cpp/ggml build (`bd4f514d`), the six JNI entries and
  nothing else exported; `otool -L` shows only system libraries.
- The earlier "≈2.12 GB" rejection was the full `.nemo` source inventory. The actual
  desktop install is 678,563,360 B: two direct GGUF files plus two authenticated byte
  ranges (31,076,864 B) from the archive. The embedded 1.44 GB `model_weights.ckpt` is
  discarded and never installed. The `.nemo` itself is never downloaded whole.
- The catalog card now installs that layout: a bounded TAR walk extracts only the ten
  final tokenizer members, authenticates the concatenated range payload, and treats
  earlier append-only copies as superseded. A host that ignores the range request
  fails loudly.
- Audio is natively 22.05 kHz mono. No resampling, normalization or derived WAV is
  produced by the engine. The proof WAVs are direct engine PCM16.
- CPU only. The direct 4-thread probe measured RTF 1.91 (EN) and 1.10 (AR) including
  first-call load; the 2-thread desktop engine path measured 4.15 cold and 0.97 warm.

## What the Chatterbox proof shows

- The prior rejection rationale was a toolchain observation, not a hard blocker:
  the mandatory Perth watermark is a PyTorch `.pth.tar`, and this host's default
  `python3` (3.15) has no `torch`. `python3.14` has torch 2.12.0, and
  `scripts/voice/export-perth.py` converted the pinned checkpoint to the exact
  recorded `perth-encoder.bin` (37,387,276 B, sha256 `02101c6f…`). On the desktop
  product this step is not a runtime dependency at all: the pinned pure-JVM
  `ChatterboxPerth` converter performs the same non-pickle conversion, and the
  desktop engine derives the watermark from the downloaded source on first use.
- `scripts/voice/chatterbox/prepare-graph.py` reproduces the Android graph
  rewrite byte-for-byte: 228,633 B source (`91846c24…`) to 228,453 B
  (`60f14516…`) by removing only the 90 empty trailing GQA inputs. No ONNX
  Python tooling is involved.
- The dylib statically owns the Rust byte-BPE (tokenizers 0.22.2, built from the
  repo's pinned crate) and links only `libonnxruntime.1.20.1.dylib`, `libc++`
  and `libSystem`. It does not dlopen a second Chatterbox runtime, ship Python,
  or copy another engine's runtime into itself.
- The generated WAV is real and non-silent, and the engine always applies Perth
  before the first PCM callback; there is no raw-vocoder fallback.
- The publisher conditional decoder contains random-noise nodes, so PCM and the
  derived peak/RMS are not bit-reproducible run to run; the speech-token sequence
  was stable for the same text, and every run stayed non-silent.
- It is slow: the C ABI probe measured RTF 15.4 for 1.16 s of English and 8.25
  for 1.6 s of Arabic with 2 threads, and the app-path cold English runs measured
  25.6-28.9 because they include the ~22-27 s session load. This is not a real-time
  reader and no throughput claim is made.

## Not claimed

- Windows for any of the above. No Windows host has run this code. The Chatterbox stage is macOS arm64 only.
- Chatterbox quality, pronunciation, speaker preference or watermark detectability. Non-silence only proves execution. The engine applies Perth, but this work did not run Perth detection on the output.
- Chatterbox real-time use. RTF measured 4.8-28.9 through the app path and 8.2-15.4 through the direct C ABI on an Apple M4 Pro; it is not claimed as a live reader. Only Arabic and English frontends are exposed; the other upstream languages are not advertised.
- OmniVoice quality, pronunciation, fixed speaker identity or gender. Voice designs
  are requested attributes; the default is `design-female`. RTF is ~4-5 at the
  upstream default of 32 MaskGIT steps on Apple M4 Pro, so it is not a real-time
  reader; the 8-step path measured RTF ~0.83 in the scratch probe but is not the
  shipped default.
- Moonshine word accuracy. All ten variants produced non-empty transcripts from real
  speech, but the Japanese and Chinese outputs are spaced per token, the Korean
  output carried the tokenizer's `▁` prefix, and the Arabic output contained
  recognition errors. The language on each card is what the package transcribes,
  not a measured word error rate.
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

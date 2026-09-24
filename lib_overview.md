# common-jni library overview and review log

Living record of the native/Kotlin library review. Append new findings; do not
delete resolved ones. Mark them **Resolved (commit)** so the history stays
auditable.

- Reviewed tree: `codex/model-phase` @ `46d42b7` (after the Omnilingual CTC /
  Marian pivot pull). Started 2026-09-23.
- Scope: `common-jni` (C++/NDK, C ABI, JNI, Kotlin library) plus the app code
  that drives the audio/translation pipeline.
- Evidence rules: every item cites code, a static binary inspection, an
  upstream source, or a recorded phone/host result. **Proven** means shown from
  code or artifacts. **Hypothesis** means the mechanism is real but the size of
  the effect needs measurement.
- Initial pass: no Gradle or full host suites were run. Phase 0 on
  2026-09-24 ran the host, Gradle, QA-build and release-verifier checks in §6.
  No device or paid provider was used.
- The original `ffmpegmakercustom` repo is not on this machine at the given path.
  The only copies found are a 2025-09-06 git clone
  (`~/AndroidStudioProjects/whisperIME/ffmpegmakercustom`) and an April 2026
  non-git snapshot (`~/Downloads/ffmpegmakercustom-main`). Neither has
  `media_engine.h`, `MediaEditKit.kt`, `latest_handover.md` or the media test
  runner, so the FFmpeg media engine is **not reviewed** here (§9).


## 0. Start here — handoff for the next agent (updated 2026-09-24)

**Focus order (owner, 2026-09-24):** utils (`common/`, `jni/`), `speech/`,
`translation/`, `audio/`, `ffmpeg/` come first. **Nothing is dropped.**
Everything else stays recorded and tracked in the *Later queue* below, with
its findings and its unread files, and is reviewed after the focus areas.
Whisper is in the Later queue because it is heavy for real-time mobile use
(candidate for a future notes-transcription app); so are Vosk and the legacy
`IEngine`/`EngineRouter`/`SttEngine` stack.

**Branch and state:** `main` now contains the model phase, common-jni audit
and Whisper reset branches through merge `d4ab6ec` (pushed 2026-09-24).
The old `hearth-branding` branch was already an ancestor of main.
`8f1cac9` adds the stateful native resampler, `df44925` removes the duplicate
JNI resampling, `8d4d48f` extends bounded WAV parsing, and `5f2af68` applies
release native optimization flags. `c3d8b64` and `e2071dc` make active STT JNI
validation failures visible; `5094530` adds native model-tree integrity coverage.
The `f863476` segmenter experiment was
reverted by `e9903e6` because the pinned Qwen runtime must be rebuilt with it.

**Environment:** the original reviewer's Mac needed Homebrew Clang because
its Xcode-beta ASan hung (Log E1). On this Mac that path is absent; an
ASan hello-world probe and both host suites passed with
`CXX=/usr/bin/clang++`. Use the compiler verified on the current host.
Run the AGENTS.md gates before each code commit; no device or paid calls.

**Rules for this file:** append findings with evidence (file:line, probe,
upstream source); mark fixed items **Resolved (commit)** instead of deleting; never drop an item —
move it between the focus areas and the Later queue;
add a Log line for every session.

### Status by priority area
| Area | Read so far | Still to read | Top open items |
|---|---|---|---|
| **utils** (`common/`, `jni/`) | Everything (§11), plus U2/U3/U4/U9/U10/U11/U12 fixes and host tests | Nothing | U8 FFmpeg legacy path, U13 consolidation, U12 log-callback wiring (owner decision); U2/U3/U4/U9/U10/U11/U12 attachment and U14 host coverage resolved below |
| **speech/** | Native ABI, session, segmenter, endpoint budget, Qwen/Moonshine/Omnilingual/Nemotron adapters, speech JNI; Kotlin `SpeechSession`, `LiveSpeechProcessor`, `SpeechModels`, `SpeechModelPackage`, `SpeechTranslation`; app publisher/local-model adapters; `speech_smoke.cpp` and runtime scripts | Full caller audit of upstream `6ad8305`; remaining app/runtime integration paths | F2 async windowed decode, F1 ggml ARM variants, H1 ABI v2 (sample rate, interrupt, capabilities), H2/H3/H4, H13/H14, U21 |
| **translation/** | `text_model.h`, `translation_jni.cpp`, Marian tokenizer/engine/JNI, `CaptionTranslationBridge`; U23 bounded-read/ID and U24 spin fixes with host tests | Upstream `TranslationSourceEvidence.kt` (31) + bridge diff; `LocalTranslationSession.kt` (46), `TranslationCatalog.kt` (107), `MarianTranslationSession.kt` (36); app `MarianCascade.kt` (67, new pivot routes), `TranslationLayer.kt` (246), `LocalTranslationModels.kt` (27); `translation_smoke.cpp` (69); `scripts/translation/*` (75) | F1, F9, U23 malformed charsmap/golden ids, U24 model shapes/timer |
| **audio/** | `common/audio_utils.h`, `audio_gate.h`, `ring_buffer.h`, `core/vad.cpp`, Kotlin `audio/` (all), capture service | App `BenchmarkAudio.kt` (58) | U16 configurable capture/ShortArray overflow observability, U21 VAD chunk dependence, U17 native/Kotlin API consolidation; U16 read failure/timeline, U10 and U17 WAV parsing resolved |
| **ffmpeg/** | Nothing: not in this repo | A current Git checkout is available on this host at `/Users/salehalanazi/ZCodeProject/ffmpegmakercustom`; inspect after audio, respecting its ADD-only contracts | §9 questions |

### Later queue (tracked, not the current focus — nothing dropped)
Every item keeps its finding, evidence and status; it is reviewed and fixed
after the focus areas.

| Area | Open findings | Still to read (approx. lines) |
|---|---|---|
| Whisper (`whisper/whisper_engine.cpp`, Kotlin `engine/whisper/`) | F3 (patched A), F5, F6, H12; F11 resolved (`7b44a00`), U9 resolved (`b50952e`) | `whisper_engine.cpp` batch, `detectLanguage`, finalize and cancel paths (~780); `WhisperEngine.kt` (~355) |
| Vosk (`vosk/`, Kotlin `engine/vosk/`) | U25; U9 resolved (`b50952e`) | rest of `vosk_engine.cpp` (init, batch, loader ~450); `VoskEngine.kt` (~430) |
| Legacy engine stack (`router/`, `common_jni_bridge.cpp`, Kotlin `engine/`, `core/`) | F7, U8, U13 (two engine contracts), H7 | rest of `stt_jni.cpp` (~850), rest of `common_jni_bridge.cpp` (~680); Kotlin `SttEngine`, `SttEngineFactoryImpl`, `RealtimeSttSession`, `CommonJni` (rest), `EngineCapabilities`, `SttTypes`, `AudioModels`, `ProcessingConfig` |
| ONNX STT engine (`onnx/onnx_engine.cpp`) — no current consumer | none yet | all (~490); Kotlin `OnnxEngine` (~270) |
| `core/` model_loader / session / JNI (only the VAD binding is exercised) | U12 attachment resolved (`27b5e0f`), U21 (VAD) | `model_loader.cpp`, `session.cpp`, `core_jni.cpp` (~650); Kotlin `core/` (`ModelLoader`, `VadDetector`, `LanguageDetector`, ~615) |
| TTS stack (`tts/`) — no current consumer | U27 (keep as an optional module or remove: owner decision) | all native (~1,600) and Kotlin `tts/` (~870) |

### Recommended next actions, in order
1. **Continue the focus areas:** utils U8 FFmpeg path/U13; speech H4 only with
   a coupled Qwen runtime rebuild, then F2/H1; translation U23 malformed
   charsmap and official token goldens, U24 model shapes/deadline timer, F9;
   audio U16/U17 native/Kotlin API/U21; then FFmpeg from the current checkout.
   U2/U3/U4/U9/U10/U11/U12 attachment, U14 host coverage, and the bounded-read/ID
   and ORT-spin portions of U23/U24 are resolved below. Keep one test and the
   required gates per fix.
2. **Build after the owner chooses the variant strategy:** F1 — per-CPU ggml variants for `libhearth_nemotron.so` and
   `libtransiber_translation.so` (the largest speed lever for both speech and
   translation); F4 — build type.
3. **Design:** F2 async windowed decode; H1 speech ABI v2; U13
   consolidation (one registry, one UTF-8 decoder, one stateful native
   resampler, one VAD, one JNI helper set, one error channel); U18/U20 one
   integrity and JSON contract with shared golden vectors.
4. **Tests to add:** `audio_utils` conversions and resampler continuity,
   `ring_buffer`, `lifecycle_gate`, `native_registry`, model-integrity tree
   walk, `buffer_view` plane packers, Marian tokenizer golden ids, JSON parser
   fuzz corpus.

---

## 1. Architecture map (actual boundaries)

```
 app (Hearth)                         Kotlin library (common-jni)                     JNI / C ABI                         Native runtimes
 ─────────────────────────────────    ───────────────────────────────────────────    ──────────────────────────────     ─────────────────────────────────────
 CaptionCaptureService                                                                                                   Android AudioRecord / MediaProjection
   AudioRecord 16 kHz mono PCM16,                                                                                        (platform does downmix + SRC)
   800-sample (50 ms) blocking reads
        │
 CaptionEngineController.pushAudio ─┬─► LiveSpeechProcessor.tryAccept  ─► SpeechSession ─► speech_jni.cpp ─► SpeechNativeSession ─► HearthSpeechBackend v1 (dlopen, RTLD_LOCAL)
        │                           │    (bounded 48000-sample queue,       (Mutex, IO      (LeaseRegistry,     (JSON config,           ├─ libhearth_qwen.so: sherpa-onnx 210f340
        │                           │     fail-loud on overflow)            dispatcher)     PCM16 LE decode)    offset/finite checks,   │    + static ORT 1.27.1: Qwen3-ASR,
        │                           │                                                                          exactly-once IDs)       │    Moonshine, Omnilingual CTC
        │                           │                                                                                                  │    (UtteranceSegmenter + AudioGate,
        │                           │                                                                                                  │     decode runs INSIDE push)
        │                           │                                                                                                  └─ libhearth_nemotron.so: nemo-speech
        │                           │                                                                                                       ffa38cb + ggml (streaming RNNT)
        │                           └─► audioChannel(cap 4, DROP_OLDEST) ─► WhisperEngine / VoskEngine ─► router/stt_jni.cpp ─► EngineRouter ─► whisper_engine.cpp (whisper.cpp 19ceec8 +
        │                                (legacy SttEngine path)              (SttEngine API)             (SET_ERROR,           (NativeRegistry)    ggml, compiled INTO libcommon_jni;
        │                                                                                                  thread_local scratch)                    worker thread, 60 s ring buffer)
        │                                                                                                                                         vosk_engine.cpp ─► VoskApi dlopen libvosk.so
        │                                                                                                                                         (own static libc++, RTLD_LOCAL)
 poll loop (100/500/700 ms) ─► promoteFinal ─► CaptionTranslationBridge.offer ─► CancellableTextTranslator
                                               (cap 3 + 1 in flight, 20 s age)   ├─ MarianTranslatorEngine ─► marian_jni.cpp ─► MarianEngine ─► libonnxruntime.so 1.20.0 (shared)
                                                                                 └─ LocalTranslationSession ─► libtransiber_translation.so (own JNI) ─► TextModel ─► llama.cpp 64e9bce + ggml
 overlay (history, partial, translation)
 CommonJni (object) ◄── JNI_OnLoad RegisterNatives (30 methods; FFmpeg-era stubs) ── common_jni_bridge.cpp
```

### What already builds on a host (macOS/Linux)
| Unit | Evidence |
|---|---|
| `speech/speech_session.*`, `utterance_segmenter.h`, `endpoint_budget.h`, `qwen_language.h`, `backend_abi.h` | `run_speech_tests.sh` (ASan/UBSan) |
| `common/` cancel_token, pipe_progress, job_future, json_options, pcm_buffer_range, lease_registry, json_utils, engine_interface | `run_common_utils_tests.sh` |
| OCR geometry, voice bounds, Vosk loader fixture | `run_ocr_tests.sh`, `run_voice_tests.sh`, `run_vosk_api_tests.sh` |
| Qwen/Moonshine/Omnilingual backend DSO (macOS branch in `scripts/speech/qwen.cmake`) | `speech_smoke.cpp`, `scripts/speech/run-host-smoke.sh` |
| llama.cpp `TextModel` | `scripts/translation/build-runtime.sh host` |
| whisper.cpp core (not `whisper_engine.cpp`) | `scripts/benchmark/whisper-host` |

### Android/JNI-coupled
`jni/*`, every `*_jni.cpp`, `common_jni_bridge.cpp`, `common/jni_utils.h` (a JNI
header that lives in `common/`), `common/logging.cpp` (`android/log.h`),
`core/*` (links `android`), plus capture and service code in Kotlin.
`whisper_engine.cpp` is portable C++ but is only built by the Android CMake.

### Duplicate or diverging responsibilities
| Concern | Implementations | Consequence |
|---|---|---|
| Speech session stack | New versioned C ABI (`SpeechNativeSession`) vs legacy `EngineRouter`/`SttEngine` (Whisper, Vosk, ONNX) | Different threading, error, overload and text-encoding rules for the same product feature |
| Overload policy | Fail loudly (`LiveSpeechProcessor`), silent 200 ms drop (Kotlin `audioChannel` DROP_OLDEST), silent drop after 60 s backlog (Whisper native) | Users see an error for one engine and invisible lag or gaps for another (F5) |
| Invalid UTF-8 from decoders | `JsonUtils::stringify` → U+FFFD (speech ABI) vs `jni::utf8ToJString` → throw (legacy) | A truncated Whisper partial ends the whole session (F3) |
| ONNX Runtime | `libonnxruntime.so` 1.20.0 (Marian, OCR, voice) and a static ORT 1.27.1 inside `libhearth_qwen.so` | About 2× ORT code size and two independent intra-op thread pools |
| ggml | whisper-vendored `19ceec8` (in libcommon_jni), llama.cpp `64e9bce`, nemo-speech's copy | Three copies, none built with ARM dot-product kernels (F1) |
| VAD | Energy `AudioGate` (segmenter/Whisper/Vosk/ONNX); `core/vad.cpp` + `VadDetector.kt` (no app consumer) | Music counts as speech for windowed backends (H3) |
| Resampling | Native linear `AudioUtils::resampleInto` (stateless per chunk), Kotlin `Pcm16Resampler`, `BenchmarkAudio` | No shared, stateful, anti-aliased SRC for non-16 kHz sources |
| Error channel | thread-local `last_error` (speech ABI), `SET_ERROR` (stt_jni), JSON strings, Java exceptions | Hard to wrap consistently for a non-Android host |

---

## 2. Ranked findings

Priority: **P0** = crash, data loss or a wrong result in normal use. **P1** =
likely user-visible failure or a large speed loss. **P2** = robustness, library
hygiene, or a smaller speed loss.

### F1 · P1 · Proven · ggml runtimes are built without ARM dot-product / fp16 / i8mm kernels
- **Where:** `scripts/translation/CMakeLists.txt:11` (`GGML_NATIVE OFF`, no
  `GGML_CPU_ARM_ARCH`); `scripts/speech/build-runtimes.sh:86` (same);
  `common-jni/src/main/cpp/CMakeLists.txt:120-142` (globs ggml sources directly,
  bypassing `ggml-cpu/CMakeLists.txt`, which is where ARM `-march` flags come
  from). `CMakeLists.txt:72` defines `GGML_USE_NEON`, which ggml doesn't use for
  kernel selection.
- **Evidence (static disassembly of the shipped prebuilt binaries at 46d42b7):**

  | binary | `sdot` | `udot` | `smmla` | fp16 `fmla .8h` |
  |---|---|---|---|---|
  | `libtransiber_translation.so` (llama.cpp) | 0 | 0 | 0 | 0 |
  | `libhearth_nemotron.so` (ggml) | 0 | 0 | 0 | 0 |
  | `libhearth_qwen.so` (ORT, runtime dispatch) | 1068 | 149 | 320 | 2694 |
  | `libonnxruntime.so` | 1672 | 104 | 472 | 1262 |

  Vendored `ggml-cpu/CMakeLists.txt:171-215`: with `GGML_NATIVE=OFF` and neither
  `GGML_CPU_ARM_ARCH` nor `GGML_CPU_ALL_VARIANTS`, no `-march` is added. In
  `ggml-cpu-impl.h:304-316`, without `__ARM_FEATURE_DOTPROD`, `ggml_vdotq_s32`
  is emulated with `vmull_s8` + `vpaddlq`. Whisper inside libcommon_jni follows
  the same source logic (not disassembled, since no build exists on disk).
- **Consequence:** Nemotron Q8_0 (the Easy-setup live default) and every GGUF
  translator run baseline NEON int8 paths on phones that support
  `sdot`/`i8mm` (S22/S25). Recorded phone numbers that include this cost:
  Nemotron 24.1 s warm for about 45 s of FLEURS audio (RTF ≈ 0.53); Hy-MT2 Q4
  8.8 s/sentence; MiLMMT 22.7–25.8 s per six sentences.
- **Minimal fix:** do not simply add `-march=armv8.2-a+dotprod`; minSdk 28
  arm64 still includes ARMv8.0 cores (A53/A72/A73), which would get SIGILL.
  Options, cheapest first:
  1. Build each ggml plugin twice (baseline, and `armv8.2-a+dotprod+fp16`,
     optionally `armv8.6-a+i8mm`). Choose the file in `loadBackend()` with
     `getauxval(AT_HWCAP) & HWCAP_ASIMDDP`, `HWCAP_ASIMDHP` and
     `getauxval(AT_HWCAP2) & HWCAP2_I8MM`. The versioned ABI already isolates
     plugins, so only the file name changes.
  2. Or build ggml with `GGML_BACKEND_DL=ON GGML_CPU_ALL_VARIANTS=ON` and ship
     the per-arch `libggml-cpu-*.so` variants (upstream's own runtime scoring).
- **Regression test:** extend `verify-release.py` so the `v82` variants must
  contain `sdot` and the baseline must not. Add a host unit test for the
  hwcap → file-name selection function. Run a phone A/B on the same FLEURS pack
  (§3).
- **Expected benefit (hypothesis, measure):** faster Q8_0/Q4 matmuls, mostly in
  prefill and encoder. Token-by-token decode is partly memory-bound, so gains
  there will be smaller.

### F2 · P1 · Proven mechanism · Windowed ASR decodes inside `push()`; a 3 s queue turns a sustainable load into a fatal error
- **Where:** `speech/backends/qwen_backend.cpp:78` (`push` → `segmenter.push` →
  `decode()` at `:49`, synchronously);
  `common-jni/.../speech/LiveSpeechProcessor.kt:78` (fails the stream when the
  queue exceeds its limit); `CaptionEngineController.kt:554`
  (`maxQueuedSamples = 48000`, the maximum `LiveSpeechProcessor` allows);
  `SpeechModels.kt:68` (`maxUtteranceMs = 4000`).
- **Trigger:** Qwen/Moonshine/Omnilingual on device audio. Capture continues
  while one JNI `push` runs a whole-utterance decode, so the queue fills with
  audio.
- **Failure:** if decoding a ≤4.2 s utterance (4 s plus 200 ms pre-roll) takes
  more than about 3 s of wall time, `tryAccept` fails with
  `"Speech inference cannot keep up: N samples pending; queue limit 48000"`. The
  poll loop then reports the error and captions stop. The effective failure
  point is a per-utterance RTF of about 0.7, not 1.0, so loads that would keep
  up on average are still killed.
- **Evidence:** phone smoke — Qwen3-ASR 0.6B warm 17.6 s for about 45 s of
  audio (RTF ≈ 0.39, about 1.6 s per 4 s utterance: within budget).
  `QWEN3_ASR_1_7B` is a declared, importable profile at about 2.8× the
  parameters, so roughly 4.4 s per utterance would fail. Slower phones hit the
  same limit with 0.6B.
- **Minimal fix (library):** move decode off the push path inside the adapter.
  `push` only segments; completed utterances go to a bounded queue (for
  example, 2) decoded on an adapter-owned thread; `next()` returns completed
  results; `finish()` waits for the queue to drain; `reset()`/`destroy()` cancel
  and join. The ABI stays v1 (`push`/`next` semantics are unchanged: results
  were already only guaranteed "after push"). Overload then means "too many
  pending utterances", reported as a verbatim error once the bound is exceeded.
- **Regression test:** `speech_test.cpp` fake windowed backend with a 3.5 s
  decode per 4 s utterance → 60 s of simulated audio with no failure and
  bounded memory. Sustained RTF > 1 → one explicit overload error, with no
  silent drop. Kotlin: `SpeechFoundationTest` with a `FakeDriver` whose push
  blocks when an utterance completes.
- **Benefit:** time-to-first-word unchanged; stops fatal stops at RTF 0.7–1.0;
  lets 1.7B and slower phones run with explicit lag instead of failing.

### F3 · P1 · Proven path, model-dependent trigger · A Whisper partial ending inside a UTF-8 character ends the caption session — **patched (§6)**
- **Where:** `whisper_engine.cpp:578` (`p.max_tokens = 96` for streaming);
  upstream `whisper/upstream/src/whisper.cpp:7368` stops a segment at the token
  cap with no character-boundary check; `whisper_engine.cpp:599`
  `sanitizeTranscript` only trims whitespace; `router/stt_jni.cpp:292/307` →
  `jni::utf8ToJString` rejects the text (`utf8_utils.h:120`: `"UTF-8 ends inside
  a code point"`) → `WhisperEngine.kt:243` wraps it as `SttError.ProcessingFailed`
  → `CaptionEngineController.kt:823` calls `reportError("Transcription: …")` and
  `break`s out of the loop.
- **Trigger:** CJK/Arabic byte-level tokens split across the 96-token cap (most
  likely in repetition loops), or across a segment boundary. Segments are joined
  with a space, so a split character stays invalid.
- **Consequence:** live captions stop with
  `Transcription: UTF-8 ends inside a code point` (or `invalid continuation
  byte`).
- **Inconsistency:** the speech ABI path already substitutes U+FFFD through
  `JsonUtils::stringify` (`json_utils.cpp:451-455`), so only the Whisper path
  fails.
- **Fix:** §6 patch A.

### F4 · P2 · Proven · QA/release native code is built as `RelWithDebInfo`; the "Release" flags never apply
**Partially resolved (`5f2af68`):** Android `RelWithDebInfo` now applies
`-O3`, hidden visibility, per-function/data sections and link-time section
garbage collection while retaining build-side debug symbols. The generated
`compile_commands.json` shows `-O3` after CMake's default `-O2`; both QA APKs
build and pass `verify-release.py`. Enabling CMake IPO failed with
`clang++: error: invalid linker name in argument '-fuse-ld=gold'` under NDK r27
and CMake 3.22, so Android LTO is explicitly deferred. No phone speed gain is
claimed without measurement.
- **Where:** `common-jni/src/main/cpp/CMakeLists.txt:48-62` puts `-O3`,
  `-fvisibility=hidden`, `-ffunction-sections -fdata-sections`,
  `--gc-sections -s` and LTO under `*_RELEASE` or `CMAKE_BUILD_TYPE STREQUAL
  "Release"`.
- **Evidence:** AGP 8.12.1 `CreateCxxVariantModelKt` bytecode maps variant names
  ending in `release` to `RelWithDebInfo` (`ldc "RelWithDebInfo"` after
  `endsWith("release")`). `qa` uses `initWith(release)` with
  `matchingFallbacks += "release"`, so `common-jni` builds its `release` variant.
  NDK r27's `flags.cmake` adds nothing for RelWithDebInfo, so CMake's default
  `-O2 -g -DNDEBUG` applies.
- **Consequence:** whisper.cpp/ggml, Marian and the utils ship at `-O2`, without
  section GC or LTO, even though the comments say otherwise. Visibility is still
  correct because `exports.map` is a version script (`CMakeLists.txt:405-415`).
- **Fix:** pass `-DCMAKE_BUILD_TYPE=Release` for the release build type in
  `common-jni/build.gradle.kts` (`buildTypes.release.externalNativeBuild.cmake.arguments`),
  or duplicate the flags into `*_RELWITHDEBINFO`. Keep `-g` if symbolized native
  crash reports are wanted (AGP strips packaged libraries anyway).
- **Test:** `verify-release.py` asserts a marker (for example,
  `.comment`/`DW_AT_producer` containing `-O3`, or no `.debug_*` sections after
  `-s`), and checks `libcommon_jni.so` size against a budget.
- **Benefit:** small, broad CPU gain (measure); smaller binary.

### F5 · P1 · Proven mechanism · Whisper/Vosk overload is silent: up to 60 s of hidden lag, then dropped audio
- **Where:** `whisper_engine.cpp:1013-1021` (discards the oldest audio above 60 s
  pending with only `LOG_W`; the counter never leaves native code);
  `CaptionEngineController.kt:334` (`Channel(capacity = 4, DROP_OLDEST)` in front
  of the engine).
- **Trigger:** Whisper streaming cost above real time. Partials re-decode a tail
  of up to 6 s at every ≥1 s of new audio, on top of promotes, so a base/small
  model on a mid-range phone can exceed RTF 1 even when batch RTF is below 1.
- **Consequence:** promotes decode from the oldest end, so finals can trail the
  video by up to a minute and then silently skip. Partials stop once pending
  reaches promote+partialMax (14 s). The app has no backlog metric for this path
  (the local path shows `Audio queue x.xs`).
- **Fix:** add a stats call (for example, `nativeGetStreamingStatsWhisper` →
  pending, dropped, last inference ms). Surface a notice or error when pending
  exceeds a small bound (for example, 5 s). Use one overload policy for both
  stacks, preferably `LiveSpeechProcessor`'s explicit failure or an explicit
  "skipped N s" caption marker.
- **Test:** a native test seam that fakes inference time
  (`runInference` behind an interface) → assert pending stays bounded and a
  drop sets a visible counter. Kotlin poll test shows the metric.
- **Benefit:** no invisible lag; comparable behavior across engines.

### F6 · P1 · Hypothesis (strong mechanism) · Whisper per-window peak normalization applies up to about 79 dB of gain
- **Where:** `whisper_engine.cpp:699-704` scales every window so its peak reaches
  0.9 when the peak is in `(1e-4, 0.5)`, so gain can reach 9000×.
- **Trigger:** quiet background noise, room tone, or music tails that pass
  AudioGate (−45 dBFS) or the "voice since promote" rule. Batch mode uses the
  same `runInference`.
- **Evidence:** host smoke (`docs/benchmark-host-smoke-2026-09-23.md:12`) —
  Whisper tiny emitted `[музыка]` on both silence clips. The app's
  benchmark normalization (below 0.5 → 0.9) mirrors the native rule.
- **Fix (behavior change; owner decision):** cap the gain (for example, ≤10×)
  and normalize only when window RMS is above a noise floor, or drop
  normalization; Whisper's log-mel front end is already scale-tolerant.
- **Test:** host whisper bench with −70 dBFS pink noise and quiet speech; count
  false positives and WER before and after on the FLEURS pack.

### F7 · P2 · Proven · One eager `JNI_OnLoad` couples every feature to FFmpeg-era `CommonJni` registrations
- **Where:** `common_jni_bridge.cpp:662-694` (30 `RegisterNatives` entries,
  including `nativeExtractAudio*`, `nativeHasFFmpeg`, `nativeDecodeAudio`) and
  `:719` (weak `hearth_media_set_java_vm`). `:724-737`: if `CommonJni` can't be
  found or any entry mismatches, `JNI_OnLoad` returns `JNI_ERR`, and
  `System.loadLibrary("common_jni")` then fails for Speech, Marian, OCR, voice and
  TTS alike.
- **Mitigation today:** `consumer-rules.pro` keeps all of
  `com.sal7one.common_jni.**`. That prevents the failure, but every consuming
  app ships all the dead Kotlin classes (F8), because R8 cannot remove them.
- **Fix:** move `CommonJni` to ordinary `Java_…` exports, or register lazily
  from `CommonJni`'s own `init`. Delete the FFmpeg stubs. Narrow keep rules to
  classes with `native` methods and JNI-called callback types.
- **Test:** a minimal consumer sample (or an instrumented test) with R8 that uses
  only `SpeechRuntime`/`MarianTranslatorEngine`; the library must load.

### F8 · P2 · Proven · Dead code and utils without tests in a library meant for reuse
- **Native headers with no production consumer:** `audio_decoder.h`,
  `audio_format.h`, `audio_types.h`, `cpu_affinity.h`, `image_engine.h`,
  `image_types.h`, `image_utils.h`, `mmap_model.h`, `secure_memory.h`,
  `stt_mode.h`, `jni/transactional_cache.h`. Test-only: `job_future.h`,
  `job_system.h`, `json_options.h`.
- **Production-used but no direct host test:** `audio_gate.h` (partly covered
  through the segmenter in `speech_test.cpp`), `audio_utils.h`, `ring_buffer.h`,
  `buffer_pool.h`, `lifecycle_gate.h`, `sha256.h`, `model_integrity.h`,
  `verified_model_file.h`, `utf8_utils.h`.
- **Kotlin classes that reference only themselves** (no library or app use):
  `RealtimeSttSession`, `ChunkPacing`, `NativeHandle`, `LanguageDetector`,
  `SttEngineFactoryImpl`. No app use and no tests: `MicRecorder`, `VadDetector`,
  `ModelLoader`, `OnnxEngine`, `TtsEngine`/`BaseTtsEngine`/`TtsNative`,
  `PerfMetrics`, `NativeError`/`NativeException`, `AudioGateConfig`,
  `ProcessingConfig`, `EngineCapabilities`, `JsonInterop`, `SttTypes`,
  `AudioModels`, `SpeechTranslation`, `AudioExtractionProgressCallback`.
- **FFmpeg remnants** (AGENTS forbids reintroducing FFmpeg): `stt_jni.cpp`
  `#if WITH_FFMPEG` blocks and the `FfmpegAudioPipeline_*` symbols,
  `AudioExtractionProgressCallback`, `CommonJni.nativeExtractAudio*`,
  `hearth_media_set_java_vm`.
- **Fix (owner decision; tracked):** delete, or move to an excluded `legacy/`
  directory or an optional module. Add the JNI
  cross-reference check (`scratchpad/jni_xref.py` logic) to CI so every Kotlin
  `external` resolves to a symbol or a registration.

### F9 · P2 · Hypothesis · Local caption translation spends compute on work it will discard
- **Where:** `CaptionTranslationBridge.kt:53` (checks age before inference) and
  `:69` (discards the result if it's older than 20 s); FIFO order, capacity 3
  (`:21`). `translation_jni.cpp:46` fixes llama at 2 threads;
  `text_model.h:82` clears the KV cache and re-prefills the full chat envelope
  and instruction on every line.
- **Mechanism:** at 8.8 s/sentence (Hy-MT2 Q4, phone) and one final every ~4 s,
  requests that start at age > ~11 s are computed and then thrown away. FIFO
  also favors the oldest line over the one on screen.
- **Fix:** skip at dequeue when `age + ewma(inference) > maxAge`; serve the
  newest line first (a stale line stays CC only, as it does today). Reuse the
  KV cache for the fixed prefix (`llama_memory_seq_rm` from the prefix length).
  Measure 2 vs 4 threads with ASR running concurrently.
- **Test:** existing bridge host tests plus a fake translator with a fixed
  8 s latency and 4 s arrivals: count discarded-after-compute lines (target 0)
  and the p95 age of published lines.

### F10 · P2 · Proven · One NaN/Inf frame permanently poisons `AudioGate` — **patched (§6)**
- **Where:** `common/audio_gate.h:93-100` and `:141-146`. With `rms = NaN`,
  `rms > smoothedRms_` is false and `smoothedRms_` becomes NaN, so every later
  comparison is false and the gate reports silence forever. Inf pins it to
  "active". The segmenter's alpha = 1 doesn't help, because `0 * NaN = NaN`.
- **Reachability:** the speech ABI path rejects non-finite samples first
  (`speech_session.cpp` push). The Whisper/Vosk/ONNX float entry points
  (`nativePushAudioFloatWhisper`, …) do not validate, and the gate is a public
  utility.
- **Fix:** §6 patch B (non-finite frames are inactive and never enter the IIR
  state) plus the first direct AudioGate host tests.

### F11 · P1 · Proven · Whisper "Clear captions" is silently ignored during speech; a reset can also stop streaming — **patched (§6 D)**
- **Where:** `whisper_engine.cpp` `safeReset()` (was: `tryPause()` first, then
  `stopWorker()`/`startWorker()` while still paused); `WhisperEngine::reset()`
  only logs `"reset() rejected while engine is busy or closing"` and returns
  `void`; `stt_jni.cpp` `nativeResetWhisper` is `void`; `WhisperEngine.kt:265-270`
  returns `Result.success`; `CaptionEngineController.kt:1320` calls
  `legacy?.reset()?.getOrThrow()` on Clear.
- **Failure 1 (common):** the streaming worker holds a `LifecycleGate`
  Operation for every partial/promote inference, and `tryPause()` requires
  `active == 0`. A Clear during speech is rejected, the old ring buffer (up to
  60 s) stays, and old speech keeps appearing in the cleared history. The app
  reports success. This violates the M01 goal "No old-session translation
  appears" for Whisper.
- **Failure 2 (race):** the replacement worker was started while the Pause was
  still held (`closing == true`). If its first `wait_for` predicate
  (`workerStop || lifecycle.isClosing()`) ran before `safeReset` returned, it
  `break`s and exits. Whisper then produces no partials or finals for the rest
  of the session, with no error.
- **Fix (D):** take `transitionMutex` (like every other transition), join the
  worker first so its Operation is released, pause, reset, release the pause,
  then start the worker. It still returns `false` only when a batch/finalize
  call is active or the engine is closing.
- **Review finding (2026-09-24):** D needs changes before treating F11 as
  resolved. `stopWorker()` sets `workerStop`, but the decode abort callback
  calls `checkCancelled()` (`whisper_engine.cpp:603-605,646-652`), which does
  not observe that flag. `safeReset()` joins before `tryPause()` (`:331-349`),
  so Clear can wait for a whole in-flight decode. `finalize()` calls
  `stopWorker()`/`startWorker()` without `transitionMutex` (`:1054-1055,1115`),
  racing reset's join/restart for native callers outside the Kotlin dispatcher.
  **Resolved (`7b44a00`, integrated 2026-09-24):** the worker's decode callback
  now observes a separate stop signal during Clear/release, while finalize
  drains the in-flight decode. Reset and finalize serialize on the transition
  mutex. JNI returns a checked reset result and Kotlin reports rejection.
  `InferenceStopSignal` has direct host coverage. A native model-free reset
  lifecycle test seam remains open in the Later queue.

### F12 · P2 · Proven · 74 of 118 exported JNI functions have no exception guard
- A C++ exception crossing JNI calls `std::terminate` (process abort). Most
  unguarded entries are trivially non-throwing (flags, atomic cancel). These can
  throw: `nativeReset{Whisper,Vosk,Onnx}` (thread creation and locks;
  Whisper's is now guarded, patch E), `nativeDetectLanguage` (whisper inference
  and allocation, `stt_jni.cpp:491`), `nativeCreate{Whisper,Vosk,Onnx}`
  (allocation), `core_jni.cpp` `nativeGetSegments`/`nativeFinalize` (string
  building), and TTS `nativePushText`/`nativeSetVoice`/`nativeLoadCustomVoice`.
- **Fix:** one wrapper (for example, `template<class R, class F> R jniEntry(JNIEnv*, R onError, F)`)
  used by every export, plus a CI check (the scan used here: brace-matched body
  must contain the guard) so new exports can't regress.

### Additional findings (lower priority)
- **H1 · P2 · Proven · Speech C ABI gaps for other hosts** (`backend_abi.h`):
  sample rate and channels are implicit (16 kHz mono). There is no
  `interrupt()`, so Stop waits for the full decode under the exclusive lease
  (`speech_jni.cpp:95`). There's no capability query, so Kotlin hard-codes
  per-profile capabilities (`SpeechModels.kt`) and native repeats the language
  checks (`speech_session.cpp`). `last_error` is thread-local and not
  per-instance (safe because calls are serialized, but it must be read before any
  other call on that thread). Extend through the existing `size` fields; see §4.
- **H2 · P2 · Hypothesis · Hard 4 s cuts mid-word** in `utterance_segmenter.h:48`
  during continuous speech (video/podcast), with no overlap and no search for a
  quiet frame. Fix: when reaching the maximum, cut at the lowest-energy 20 ms
  frame in the last ~500 ms and carry the remainder forward. Test: synthetic
  continuous tone with one dip → cut at the dip; FLEURS concatenation WER A/B.
- **H3 · P2 · Hypothesis · Energy-only VAD for windowed backends.** Music and
  noise above −45 dBFS keep the segmenter "active", so Qwen/Moonshine/
  Omnilingual decode music in 4 s slices (CPU, heat, hallucinations).
  sherpa-onnx (already the runtime in `libhearth_qwen.so`) has a Silero VAD C
  API; evaluate it behind the segmenter interface. Measure CPU and false
  captions on music-heavy video.
- **H4 · P2 · Proven · Segmenter capacity ping-pong**
  (`utterance_segmenter.h:44`): `swap(preRoll_)` hands the reserved
  `maximum_`-sized buffer to `preRoll_` every other utterance, so `utterance_`
  reallocates as it grows. Fix: `utterance_.assign(preRoll_)`. Small effect.
  **Open, with build coupling proved 2026-09-24:** `f863476` implemented the
  source fix and an allocation-counting host test, but the shipped Qwen backend
  is a pinned prebuilt `libhearth_qwen.so`. `scripts/speech/stage-runtimes.py:62-64`
  hashes this header, and `scripts/verify-release.py:30-32` rejects a changed
  header without the rebuilt binary. Commit `e9903e6` reverted the source-only
  change; the host test had passed, and `verify-release.py` passes after the
  revert. Reapply H4 with a pinned Qwen runtime rebuild/stage and new hashes.
- **H13 · P2 · Proven by reading · One bad local speech install blocks all model selection.**
  `app/.../caption/LocalSpeechModels.kt:37-47` maps every manifest through
  `require`/`JSONObject` without isolating a corrupt package; `select()` calls
  `list()`, so a damaged unrelated install prevents selecting a healthy one.
  Preserve the actual corruption error for the affected package, but list
  other verified installs and expose a repair/remove action. Add a JVM fixture
  with one corrupt and one valid package.
- **H14 · P3 · Proven by reading · Speech smoke tool dereferences missing JSON fields.**
  `speech/tests/speech_smoke.cpp:15-22` parses any JSON object, then dereferences
  `profile` and `roles` without null/type checks. A malformed package can crash
  this host diagnostic instead of printing an error. Validate the manifest or
  use the package verifier before building the inference config; add a malformed
  fixture test.
- **H5 · P2 · Proven · Model files are re-hashed on every open**
  (`SpeechModelPackage.kt:53`, full SHA-256 of every asset), then native code
  re-opens them by path. It's deliberate integrity, but it adds seconds of start
  latency for GB-scale models. Option: cache
  `(dev, ino, size, mtime_ns, ctime_ns) → sha` in app-private storage and
  re-hash only when any field changes. `ctime` can't be set by user space, so an
  edit forces a re-hash. Measure load vs verify separately.
- **H6 · P2 · Proven · Marian copies the growing decoder KV back into
  `std::vector`s each step** (`marian_engine.cpp:719`), O(n²) bytes per
  sentence. Minor next to compute; IOBinding or preallocated max-length buffers
  would remove it.
- **H7 · P3 · Proven · `clampSampleCount` silently truncates** a count larger
  than the array (`stt_jni.cpp:62-80`), turning an invalid argument into partial
  success, which contradicts AGENTS ("never convert a failure into success").
  Throw `IllegalArgumentException` instead.
- **H8 · P3 · Proven · Stateless linear resampling per chunk**
  (`AudioUtils::resampleInto`, used by the Whisper push paths for non-16 kHz
  input): no anti-alias filter and per-chunk rounding drift. The doc comment
  admits it; it's off the live path (AudioRecord delivers 16 kHz). Provide one
  stateful polyphase SRC before any caller streams 44.1/48 kHz.
- **H9 · P3 · Proven · `RingBuffer::push_back(p, 0)`** on an unallocated buffer
  takes `&buffer_[0]` of an empty vector (UB; traps under libc++ hardening).
  Unreachable from current JNI callers (`count > 0` is enforced).
- **H11 · P2 · Hypothesis · Native SHA-256 is scalar only** (`common/sha256.h`)
  and re-hashes whole Whisper/Vosk models on every native load
  (`verified_model_file.h`, `model_integrity.h`). ARMv8 SHA-2 instructions
  (`HWCAP_SHA2`) typically run several times faster than portable C++. Add a
  runtime-dispatched `sha256h`/`sha256su` block function; the new known-answer
  test (patch C) guards it. Measure verification time alone for a 466 MB model.
- **H12 · P2 · Proven (by reading) · Whisper start reads the model three times and
  hashes it twice.** `FileModelProvider.getModelPath()` (`ModelProvider.kt:218`,
  also `:130`, `:259` for the other providers) fully re-hashes the file in Kotlin
  on every load; native `VerifiedModelFile::open` then hashes it again
  (scalar SHA-256, H11) and whisper.cpp parses it through the same fd. The
  native check is strictly stronger (fd-bound, TOCTOU-safe), so for Whisper the
  Kotlin pass is redundant: skip it (pass the pinned digest straight to native),
  or cache `(dev, ino, size, mtime, ctime) → digest` as in H5. Measure load time
  for a 466 MB model before and after.
- **H10 · P2 · Hypothesis · CPU oversubscription during captions + translation.**
  Nemotron has no thread knob (default threads), Marian runs 4 ORT threads
  **with ORT's default spin-waiting** (correction 2026-09-24: the
  `allow_spinning=0` entry is applied only when env `MARIAN_ORT_SPIN=0`, which
  nothing sets; see U24), llama runs 2 and Whisper up to 8, all on big.LITTLE
  cores. Measure per-thread CPU time and try pinned or smaller pools.

---

## 3. Timing and memory plan

Rule: a host timing is not an Android result. Every run records device, build
variant, runtime revisions (`runtime-build.json`), model SHA-256 and thermal
state at start.

### 3a. Live local captions + translation (implementable in this repo)
- **Same input:** replay a fixed 16 kHz mono WAV (a FLEURS concatenation plus a
  10-minute podcast plus a music bed) into `CaptionEngineController.pushAudio`
  at real-time pace (50 ms blocks), bypassing AudioRecord. Use the same file for
  every model and build.
- **Trace points** (`android.os.Trace`/`ATrace_beginSection` + Perfetto;
  counters with `ATrace_setCounter`, API 29+):
  `capture.block` (sample index, wall time) → `speech.enqueue` →
  `speech.push` (JNI entry/exit, samples, utterance flush flag) →
  `speech.decode` (windowed backends: utterance samples, decode ms) →
  `speech.final` (utteranceId, audioEndSamples, wall) → `mt.offer` →
  `mt.start` → `mt.end` → `overlay.publish`.
  Counters: `speech.pending_samples`, `speech.rtf_window`, `mt.queue`,
  `mt.discarded`, `whisper.pending`, `whisper.dropped`.
- **Derived metrics (p50/p95 per utterance):**
  - Time to first word = first non-empty partial wall − speech onset wall. Onset
    is the segmenter/AudioGate onset sample converted to capture wall time.
  - Stable source delay = final publish wall − wall(audioEndSamples).
  - Stable translation delay = translation publish wall − wall(audioEndSamples).
  - Backlog slope = linear fit of `pending_samples` over time (samples/s; must
    be ≤ 0 over 10 minutes).
  - Discarded-after-compute translation count.
- **CPU/memory/thermal:** per-thread `utime`/`stime` from `/proc/self/task/*/stat`
  at 1 Hz (name native threads). `VmRSS`/`VmHWM` from `/proc/self/status`;
  `Debug.getNativeHeapAllocatedSize()`; heapprofd for allocation peaks during
  model load and the first 60 s. `PowerManager.getThermalHeadroom(10)` and
  `currentThermalStatus` at 1 Hz; a 30-minute soak with the screen on. GPU is not
  used by these runtimes (CPU only).
- **Comparisons:** F1 baseline vs dot-product variant; F2 synchronous vs async
  windowed decode; F4 `-O2` vs `-O3`; F9 FIFO vs newest-first. Three runs each,
  alternating order, cooling to the same thermal headroom between runs.

### 3b. Long FFmpeg audio extraction / 3c. video edit/export
Not in this repository (FFmpeg is excluded here by AGENTS). The plan cannot be
tied to real code. See §9 for the questions that need the original repo. When
that repo is available, instrument per-stage: demux packets/s, decode
frames/s, filter/resample frames/s, encode/mux packets/s, queue depths between
stages, PTS monotonicity and drift (output duration − input duration), peak
native RSS, progress-callback cadence, cancellation latency (cancel → return),
and partial-output cleanup. Use the same 2 h input file for every build.

---

## 4. Portability plan (staged, small)

1. **Host core now.** Add a host CMake preset that builds a static
   `hearth_core` (speech_session, segmenter, endpoint budget, json_utils, the
   tested common utils, marian_engine against ORT) and registers every existing
   host test with CTest. CI on macOS and Linux runs ASan/UBSan, plus TSan for
   `LeaseRegistry` and the (future) async windowed decoder.
2. **Remove Android from `common/`.** Move `jni_utils.h` to `jni/`. Put
   `logging.cpp` behind a sink function pointer (Android sink = `__android_log`,
   host sink = stderr). Nothing else in `common/` should include `jni.h` or
   `android/*`.
3. **Narrow public C API (`hearth.h`), versioned by `size` fields:**
   - Opaque handles: `hearth_speech*`, `hearth_translator*`.
   - `hearth_speech_open(const hearth_speech_config*, hearth_speech** out,
     hearth_error* err)`. The config carries `sample_rate`, `channels`, sample
     format and paths. Ownership: the caller owns the config; the library copies
     what it keeps.
   - `push(h, const void* pcm, size_t frames)` borrows the input for the
     duration of the call. `poll(h, hearth_speech_event* events, size_t cap,
     size_t* n)` returns strings that stay valid until the next
     poll/reset/close on that handle. `finish`, `reset`, `close`.
   - `interrupt(h)` is thread-safe and callable during `push`/`finish`.
   - Errors: a `hearth_status` enum plus the message copied into a
     caller-provided `hearth_error` (no thread-locals, no exceptions across the
     ABI).
   - Backends register through a static table (required on iOS) or `dlopen`
     (Android and desktop), behind the same `HearthSpeechBackend` struct.
4. **Adapters.** The Android JNI layer becomes a thin mapper onto `hearth.h`.
   A desktop CLI and tests call it directly. iOS later links an xcframework of
   `hearth_core` with statically registered backends (no `dlopen`).

---

## 5. Sound areas (with evidence)
- **Handle lifetime:** `LeaseRegistry` gives monotonic, never-reused handles;
  retire invalidates immediately and the exclusive lease waits for in-flight
  calls. Sanitizer host tests exist (`lease_registry_test.cpp`); it's used by
  speech, OCR, Marian, llama and voice.
- **Speech ABI validation:** the ABI version, struct sizes, backend id and
  pinned revision are checked on load (`speech_session.cpp` `loadBackend`).
  Results are bounded (≤64 per drain, text ≤32 KiB), offsets must be
  contiguous, samples finite, and utterance/revision IDs exactly-once.
  Duplicate partials are suppressed.
- **Text robustness in the ABI path:** `JsonUtils::stringify` substitutes
  U+FFFD, and `jni::utf8ToJString` is a strict converter (it rejects overlongs,
  surrogates and values above U+10FFFF).
- **llama adapter:** user text is tokenized without parsing special tokens (no
  control-token injection); there's a 20 s deadline through the abort callback;
  truncated output is never published; the log capture is bounded.
- **Caption translation bridge:** bounded (3 + 1), stale-generation
  suppression, reported drops; the model is prepared once, outside the age
  window.
- **16 KiB pages:** `verify-release.py:68-70` asserts every packaged `.so` has
  LOAD alignment ≥ 16384. The runtimes link with `-z max-page-size=16384` and
  `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`; `useLegacyPackaging = true`.
- **Symbol isolation:** version scripts plus `--exclude-libs,ALL`, and
  `RTLD_LOCAL` plugins. `verify-release.py` checks translation exports and that
  libcommon_jni has no `libvosk.so` NEEDED entry.
- **Conversion kernels:** the NEON and scalar PCM16 ↔ float paths agree (both
  truncate); alignment checks use natural `alignof`, so JNI arrays pass;
  squares accumulate in int64.
- **Capture:** AudioRecord delivers 16 kHz mono PCM16, so the platform does the
  downmix and SRC and there's no app mixing code. Silence is passed through; the
  processor assigns contiguous offsets itself.
- **Whisper streaming:** pushes never run inference; a promoted region is
  decoded once (overlap de-duplication); the partial window is ≤6 s for the
  caption geometry; `audio_ctx` shrinks with the window.

---

## 6. Patches in this review (branch `review/common-jni-audit`)

**A. UTF-8 repair for decoder text (F3).**
`common/utf8_utils.h`: new `repairUtf8(std::string_view)`. It keeps valid
UTF-8 unchanged, drops a truncated final character (decoder token-cap
artifact), and replaces each other maximal ill-formed subpart with U+FFFD
(Unicode §3.9 practice, the same as Java's UTF-8 decoder used by the llama
path). Consumer: `whisper_engine.cpp` `sanitizeTranscript`. Host test:
`common/tests/utf8_utils_test.cpp`.

**B. AudioGate non-finite safety (F10).**
`common/audio_gate.h`: a non-finite frame level is treated as an inactive frame
and never enters the smoothing state. Host test:
`common/tests/audio_gate_test.cpp` (the first direct AudioGate test: threshold
edges for int16 and float, ZCR ceiling, hysteresis, reset, NaN and Inf
recovery, active regions).

**C. SHA-256 and digest-helper known-answer tests (tests only).**
`common/tests/sha256_test.cpp`: NIST "", "abc", 448-bit, 896-bit and one
million 'a'; padding boundaries at 55/56/63/64/65/119/120 bytes (references
from Python hashlib); every split point of a 256-byte message; byte-at-a-time;
`digest()` non-destructive; `reset()`; lowercase-only `ExpectedSha256` parsing
(matches Kotlin `[0-9a-f]{64}`); `verifySha256` match and mismatch.

**D. Whisper reset (F11).** `whisper_engine.cpp` `safeReset()`: serialize on
`transitionMutex`, join the worker before `tryPause()`, and start the new
worker after the pause is released.

**E. JNI guard on `nativeResetWhisper` (F12).** `stt_jni.cpp`: wrapped in
`JNI_TRY_CATCH_BEGIN`/`JNI_TRY_CATCH_END_VOID`.

Status: committed by the owner as `1768a12` on `review/common-jni-audit` (pushed); upstream `6ad8305` merged on top as `91b2a3c`. Results:
- `run_common_utils_tests.sh` with `CXX=/opt/homebrew/opt/llvm/bin/clang++`
  (ASan+UBSan): all 8 tests PASS, including `utf8_utils` (28 checks) and
  `audio_gate` (25 checks).
- `run_speech_tests.sh` (same compiler): `speech_test` 53 checks PASS (the
  segmenter consumes AudioGate).
- `sha256_test`: 538 checks PASS (ASan+UBSan, Homebrew clang 22).
- `whisper_engine.cpp` (patches A and D) and `router/stt_jni.cpp` (E): NDK r27
  clang `--target=aarch64-linux-android28 -fsyntax-only -Wall -Wextra`,
  0 errors and no new warnings. Also host `-fsyntax-only` against the vendored
  whisper headers. **Not run:** Gradle, `externalNativeBuildDebug`,
  QA APK builds, `verify-release.py`, and a device check.

**Independent Phase 0 review on `02aa830` (2026-09-24):** A **approve**
(`repairUtf8` produces valid UTF-8 for strict JNI conversion); B **approve**
(NaN/Inf never enter the smoothing state); C **approve** (known-answer and
padding-boundary coverage); D **change requested** (F11's in-flight-decode
wait and unsynchronized finalize above); E **approve as a narrow JNI exception
guard** (invalid-handle/reset-failure reporting remains open under F11/U8).
The accidental `.vscode/settings.json` is outside this review.

On this Mac, the ASan hello-world probe passed with Apple Clang 17; the
Homebrew Clang path from the earlier machine is absent. With
`CXX=/usr/bin/clang++`, `run_common_utils_tests.sh` passed all **9** programs
(including UTF-8 28, AudioGate 25, SHA-256 538 checks) and
`run_speech_tests.sh` passed **57** checks. The combined
`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:common-jni:externalNativeBuildDebug :app:assemblePlayQa :app:assembleFossQa`
passed (exit 0). `python3 scripts/verify-release.py` passed: Play QA
44,618,710 bytes and FOSS QA 36,693,765 bytes, 16 KiB alignment and
permissions verified. No APK was delivered and no device was used.

**Upstream `6ad8305`: approve for the scoped change.**
`speech/backends/nemotron_backend.cpp:65-78` now compares primary language
codes instead of treating `ru-RU` and `ru` as mixed; the new
`speech/source_language.h` is covered by four host assertions in
`speech_test.cpp:38-41`. `TranslationSourceEvidence.kt:7-30` only recovers
missing/uncertain ASR tags when exactly one supported translator direction
matches the caption's non-Latin script; explicit language metadata wins.
The bridge and helper JVM tests cover the Russian path, ambiguous Cyrillic,
Latin text, target mismatch and explicit-source priority. This is a
conservative fallback, not proof that mixed-language captions translate well.
The merged tree compiled and passed the checks above; no paid or phone test
was run for the rebuilt Nemotron library.

---

## 7. Implementation slices for the larger fixes
- **F2 async windowed decode:** (1) add a `DecodeWorker` in `qwen_backend.cpp`
  (a thread, a bounded deque of utterances, a condition variable, a stop flag);
  (2) `push` enqueues flushed utterances, and throws an overload error when full;
  (3) `next` pops results under a mutex; (4) `finish` waits for the queue to
  drain; `reset`/`destroy` set stop, notify and join; (5) rebuild
  `libhearth_qwen.so` via `scripts/speech/build-runtimes.sh`, update the pinned
  hash, and keep the ABI at v1; (6) tests as in F2.
- **F1 ggml ARM variants:** (1) build flags per variant in both runtime scripts;
  (2) name the files `libhearth_nemotron.so` / `libhearth_nemotron_v82.so`, and
  likewise for translation; (3) a `selectVariant(hwcap, hwcap2)` pure function
  with a host test; (4) `verify-release.py` checks the instruction presence per
  variant; (5) phone A/B.
- **F5 Whisper overload visibility:** (1) native stats struct → JSON through a
  new ADD-only JNI; (2) `WhisperEngine.streamingStats()`; (3) the controller
  shows the same "Audio queue" metric and fails or notifies above a bound;
  (4) remove the legacy DROP_OLDEST channel, or make it fail-loud like
  `LiveSpeechProcessor`.
- **F7/F8 cleanup:** (1) delete FFmpeg stubs from `gCommonJniMethods` and
  `CommonJni`; (2) delete the unused headers and classes listed in F8 (git
  history keeps them); (3) narrow `consumer-rules.pro`; (4) add the JNI xref
  check to CI.

---

## 8. Open questions for the owner
- Is Qwen3-ASR 1.7B expected to run live? (Decides F2's urgency.)
- Is whole-window Whisper normalization intentional for quiet sources? (F6.)
- Is it acceptable to ship two runtime variants per ggml plugin (APK size) or
  use ggml's backend-DL variants? (F1.)

## 9. Media-engine questions not verified (original repo unavailable)
- Packet/frame ownership (`av_packet_unref`/`av_frame_unref` on every path,
  `av_read_frame` error vs EOF handling).
- Decoder and encoder flush/drain on EOF and on cancel (`avcodec_send_packet(NULL)`
  loops) and partial-output deletion.
- Timestamp bases: `av_rescale_q` between stream, codec and filter time bases;
  trim/seek accuracy (keyframe seek + decode-to-target vs `-ss` output seek).
- Channel layout (`AVChannelLayout` API vs the deprecated masks), sample-format
  conversion and swr drift compensation for long extractions.
- MediaCodec hardware encode fallback to software, and surface/JNI lifetime
  (`av_jni_set_java_vm` is referenced here only as a weak remnant).
- Progress cadence, cancellation latency and concurrent-job isolation.
- `media_engine.h` / `MediaEditKit.kt` ADD-only compatibility contract.
- FFmpeg license obligations (LGPL vs GPL configure flags, source-offer text)
  for what is actually packaged.


## 10. Review coverage (what has and has not been read)

First-party native code is about 22,000 lines (excluding vendored whisper.cpp,
ORT headers and tests); the Kotlin library is about 8,300 lines. As of
2026-09-23, about **4,300 native lines (~20%)** and **~900 Kotlin lines
(~11%)** were read line by line. **Update 2026-09-24 (pass 2):** all of
`common/` and `jni/` (about 10,300 lines) has now been read (the unused
media/image headers by outline), plus the Kotlin `audio/`, `model/ModelIntegrity`,
`handles/NativeHandle` and `ReleasingPooledChannel`. Native total ≈ 12,800
lines (~58%); Kotlin ≈ 2,100 lines (~25%). **Update later on 2026-09-24:**
also read `marian/` (tokenizer, engine, JNI), `router/engine_router`,
`vosk_engine` push/partial/finalize, Whisper `initialize`/push, the stt_jni
direct-buffer path, `core/vad`, `ocr/` (all), `voice/supertonic`, and Kotlin
`perf/`, `json/`, `error/`, `config/AudioGateConfig`, `ModelProvider`
(digest path). Native ≈ 16,500 lines (~75%); Kotlin ≈ 2,700 (~33%).
Not read: `tts/` (dead, U27), `onnx/onnx_engine.cpp` (dead), `core/`
model_loader/session/core_jni, the rest of `common_jni_bridge.cpp` and
`stt_jni.cpp` (ONNX/batch/language paths), Whisper batch/detectLanguage, and
Kotlin `engine/`, `core/`, `CommonJni` (beyond externals), `RealtimeSttSession`,
`EngineCapabilities`, `SttTypes`, `AudioModels`, `ProcessingConfig`. The rest was covered only by whole-tree scans
(below), which find classes of bugs, not every bug.

**Read in full:** `speech/` (session, segmenter, endpoint budget, backend ABI,
Qwen/Moonshine/Omnilingual and Nemotron adapters, speech JNI), `translation/`
`text_model.h` and `translation_jni.cpp`, `common/audio_gate.h`,
`ring_buffer.h`, `sha256.h`, `lifecycle_gate.h`, `utf8_utils.h`, `CMakeLists.txt`.
Kotlin: `SpeechSession`, `LiveSpeechProcessor`, `SpeechModels`,
`SpeechModelPackage`, `CaptionTranslationBridge`.
The 2026-09-24 Phase 0 pass also read the `6ad8305` diff for
`nemotron_backend.cpp`, `source_language.h`, `speech_test.cpp`,
`CaptionTranslationBridge.kt`, `TranslationSourceEvidence.kt` and their tests;
this was a diff review, not a new whole-file audit of every caller.
**This session's additional coverage:** read the full utility implementations
and tests for `pipe_progress.h`, `scoped_timer.h`, `buffer_pool.h` and
`json_options.h`; the `jni_helper.h/.cpp` exception and attachment paths,
`jni_utils.h` attachment adapter, `ModelLoader::getEnv`, and the Whisper/Vosk
live push timer call sites. Read the current original media repository's Git
identity only; no FFmpeg source has been audited yet.
Also read `SpeechTranslation.kt`, app `PublisherSpeechPackage.kt` and
`LocalSpeechModels.kt`, `speech_smoke.cpp`, and all six listed
`scripts/speech/` build, stage, verify and package files in full. The pinned
Qwen binary/header link was checked against `scripts/verify-release.py` and
the recorded `runtime-build.json` hashes.
For Marian, re-read the tokenizer's file I/O, JSON number and surrogate paths
and the engine's ORT session-options path; added fixture-based host coverage.
The rest of Marian engine shape/deadline behavior remains at the earlier
review level recorded in U24.

**Read in part (initial pass; current coverage is above and in §0):** `whisper_engine.cpp` (~550/1330: worker, params, inference,
reset/release; not initialize, push, detectLanguage, batch), `router/stt_jni.cpp`
(~200/1103), `common_jni_bridge.cpp` (JNI_OnLoad and the registration table
only), `marian_engine.cpp` (~200/846: session options and decode step),
`common/audio_utils.h` (~250/604: conversions, resampler, alignment),
`model_integrity.h` (digest helpers only, 140/770; not the tree walk),
`verified_model_file.h` (80/465), `lease_registry.h` (80/213),
`json_utils.cpp` (string escaping only; not the parser), `jni/jni_helper.h`
(UTF-8 conversion and macros), `WhisperEngine.kt`, `CommonJni.kt` (externals).

**Not read in the initial pass (historical snapshot, since superseded):** `vosk/vosk_engine.cpp`, `onnx/onnx_engine.cpp`,
`marian/marian_tokenizer.cpp` (1195 lines; parses model vocab files),
`marian/marian_jni.cpp`, `router/engine_router.*`, `core/*` (session,
model_loader, vad, core_jni), `ocr/*`, `tts/*`, `voice/*`, `jni/jni_helper.cpp`,
`jni/native_registry.h`, `common/native_registry.h`, `buffer_pool.h`,
`buffer_view.h`, `cancel_token.h`, `pipe_progress.h`, `job_system.h`,
`job_future.h`, `json_options.h`, `engine_interface.*`, `error_codes.h`,
`logging.*`, `scoped_timer.h`, `stt_kit.*`, `jni_utils.h`, the dead headers
listed in F8, the rest of `json_utils.cpp` (parser), `model_integrity.h`
(tree walk, fd handling) and `verified_model_file.h`. Kotlin: `MicRecorder`,
`VoskEngine`, `OnnxEngine`, `ModelProvider`, `ModelIntegrity` (beyond grep),
`MarianTranslatorEngine`, `TtsKit`/`TtsEngine`, `RealtimeSttSession` and the
other classes listed in F8.

**Whole-tree scans that did run:** header → production consumer / test map
(F8); Kotlin `external` ↔ C++ `Java_*` / `RegisterNatives` cross-reference
(F7, F8); exported-JNI exception-guard scan (F12); Kotlin class usage map
(F8); instruction-set disassembly of the shipped prebuilt runtimes (F1).

**Original next-pass proposal (superseded by the owner's §0 order):** (1) parsers of untrusted files: `json_utils.cpp`
parser, `marian_tokenizer.cpp`, `model_integrity.h` tree walk and
`verified_model_file.h` (fd, TOCTOU, size limits); (2) the rest of
`whisper_engine.cpp` and `stt_jni.cpp` (push paths, detectLanguage, batch);
(3) `vosk_engine.cpp`, `onnx_engine.cpp`, `engine_router`; (4) `buffer_pool.h`,
`native_registry.h`, `jni_helper.cpp`; (5) OCR, TTS, voice, `core/`;
(6) the rest of the Kotlin library, starting with `MicRecorder` and `ModelIntegrity`.


## 11. Pass 2: reusable core (`common/`, `jni/`) — for use by other apps

Goal: judge each util as a dependency for other apps (video editors, other
tools, third parties). "Reuse verdict" = keep as is / fix then keep / merge /
delete. Probes were compiled with Homebrew clang 22 in the scratchpad; no
source was changed in this pass.

| Util | Production consumer | Tests | Reuse verdict |
|---|---|---|---|
| `json_utils` | speech config, TTS, engines, transcript JSON | via speech tests | Keep; decouple from STT types (U1) |
| `buffer_view.h` | checked math only (plane packers unused) | none | Keep; add plane-packer tests before reuse |
| `buffer_pool.h` | none (only a stats JNI) | none | Delete, or rewrite with RAII ownership (U2) |
| `cancel_token.h` | Whisper, `job_future.h` | yes | Keep; make deadline lock-free (U5) |
| `pipe_progress.h` | Whisper (inert unless an fd is attached) | yes | Fix races, then keep (U4) |
| `job_system.h` / `job_future.h` | none | yes | Keep for app/host orchestration; chunk `parallelFor` (U6) |
| `json_options.h` | none | yes | Fix defaults, then keep (U3) |
| `native_registry.h` vs `lease_registry.h` | both used | lease_registry only | Merge into one (U7) |
| `error_codes.h` + `logging` `ErrorStore` | stt_jni, engines | none | Replace with explicit error results (U8) |

### U1 · P3 · `json_utils` notes
- Sound: strict RFC 8259 parsing, 1 MiB / depth 64 / 100k-node limits,
  duplicate keys rejected, surrogate and UTF-8 validation, whole document
  consumed.
- `json_utils.h` includes `engine_interface.h` only for `buildTranscriptJson`,
  which couples a generic util to STT types. Move that helper next to the
  engines.
- `stringify` escapes every non-ASCII character as `\uXXXX`, so Arabic/CJK
  payloads grow 2–3×, and it replaces invalid UTF-8 byte by byte (inconsistent
  with `repairUtf8`'s maximal-subpart rule). Emit valid UTF-8 as-is, since the
  JNI path already converts it strictly with `utf8ToJString`.
- `JsonValue` stores every alternative in every node (bool, int64, uint64,
  double, string, object, array): ~150 B per node. `std::variant` would cut it
  roughly 3×.
- `getString`/`getInt`/`getFloat`/`getBool` re-parse the whole document for
  each field. Parse once and use `find`.
- Numbers use `istringstream`/`ostringstream` (locale machinery, allocations);
  an underflowing literal such as `1e-400` is rejected as "number out of range".

### U2 · P2 · Proven (probe) · `BufferPool` hands the same buffer to two owners
**Resolved (`6a44656`).** Slot ownership is enforced in debug and NDEBUG;
`resetAll()` leaves live borrows intact; the existing public stats API remains.
- `common/buffer_pool.h` `release()` never detects a double release, and with
  `NDEBUG` it accepts any foreign pointer. Probe: `release(a); release(a);`
  then two `acquire()` calls return the same pointer (debug and NDEBUG), and
  `active()` is wrong. `resetAll()` re-frees buffers that are still held.
- Its only use is `nativeGetBufferPoolStats`, which allocates the ~3 MB
  `AudioBufferPool` singleton just to report on it; `whisper_engine.cpp`
  includes the header without using it.
- **Applied:** retained the public stats API and pool, added slot in-use
  tracking and an RAII acquisition path, and avoided singleton construction
  for a stats-only query. Any later deletion needs owner consent.

### U3 · P2 · Proven (probe) · `json_options` loses small numeric defaults
**Resolved (`1fd333c`).** Registration keeps typed defaults and rejects
malformed or out-of-bounds specs; the no-consumer cleanup remains in U13.
- `number(name, def, …)` stores the default as `std::to_string(def)` (`%f`,
  6 decimals), then parses it with `std::stod`. Probe:
  `number("threshold", 1e-7, 0, 1)` → default **0**. Integer defaults use
  `stoll`, which throws on a malformed spec added through `add()`; defaults are
  never checked against min/max.
- (A locale round-trip was also suspected; the probe showed it is consistent
  within one process, so it's not a bug.)
- **Fix:** store the default as a typed `Value`, and validate it against the
  bounds when the spec is added.

### U4 · P2 · Proven (by reading) · `PipeProgress` data races and torn lines
**Resolved (`a45d6b7`).** State access is locked; pipe descriptors are made
nonblocking at bind, and each record is at most `PIPE_BUF` and written once.
Oversized terminal fields explicitly report truncation. ASan/UBSan and host
ThreadSanitizer passed `pipe_progress_test.cpp` on this Mac.
- It's documented as thread-safe, but `valid()`, `invalidate()` and
  `droppedLines()` read or write `fd_`, `dead_` and `droppedLines_` without the
  mutex while `writeLine()` changes them under it (a data race by the C++ memory
  model; TSan would flag it).
- A line longer than `PIPE_BUF` (4 KiB; possible for a long `FAILED` message or
  output path) can be written partially and then dropped on `EAGAIN`, leaving a
  torn JSON line the reader will concatenate with the next one.
- **Fix:** lock (or use atomics) in the accessors; cap the escaped message so
  one line is < `PIPE_BUF`, or retry until the whole line is written.
  It also relies on a transitive `<cstdio>` include for `snprintf`.

### U5 · P3 · `CancelToken`
- Correct. `shouldStop()` takes a mutex on every call (ggml calls the abort
  callback between graph nodes); store the deadline as an atomic
  `steady_clock` tick count with a sentinel instead. `setDeadline()` with a
  negative timeout silently disables the deadline; reject it or treat it as
  already expired.

### U6 · P3 · `JobSystem`
- Correct and careful: rejected work gets an exceptional future, nested
  submissions run inline (no deadlock with one worker), and `waitAll`/`shutdown`
  fail fast from a worker. Unused in production.
- `parallelFor`/`parallelForIndex` create one job, one `std::function` and one
  promise per item; for per-row frame work (1080 rows) that means 1080
  allocations. Chunk the range into about `workerCount × 4` pieces.
- `submitWithResult<T>(std::function<T()>)` forces an explicit `T` and a type
  erasure; `template<class F> auto submit(F&&) -> std::future<invoke_result_t<F>>`
  is the reusable form. `JobResult<T>` needs a default-constructible `T`.
- The singleton pool is joined during static destruction.

### U7 · P2 · Two handle registries
- `common/native_registry.h` (`NativeRegistry`: kind-tagged 48-bit handles,
  serialized leases; used by core, TTS, stt_jni streams) and
  `common/lease_registry.h` (`LeaseRegistry`: speech, OCR, Marian, llama,
  voice, engine_router). Both implement retire → exclusive-drain correctly.
- For one library, keep one. `NativeRegistry`'s kind tag is the better base,
  because a handle from one subsystem can't be replayed into another. It lives
  in namespace `jni` despite being JNI-free, and the `JNI_HANDLE_CHECK` macros
  declare reserved identifiers (`__handle_guard`, `__obj`).

### U8 · P2 · Proven (by reading) · Error messages set at the JNI layer are never shown
**Active STT validation resolved (`c3d8b64`, `e2071dc`).** Whisper, Vosk and
ONNX validation now throw the exact Java exception at the failing JNI edge;
the Kotlin engine wrappers convert it into a failed result. The existing
`jni_helper_test` exercises exception construction under a real JVM with
`-Xcheck:jni`. Batch/partial text uses `utf8ToJString` so supplementary
Unicode does not fail Modified UTF-8 conversion. **Still open:** the disabled
FFmpeg entry points at `stt_jni.cpp:981-1139` use `SET_ERROR`, and the broader
`ErrorStore` retirement is tracked under U13. Direct engine/device testing was
not run under the owner's device-testing policy.
- Before these fixes, `router/stt_jni.cpp` recorded validation failures with `SET_ERROR(...)`
  into the thread-local `ThreadLocalError` and returns `-1`. Kotlin then calls
  `nativeGetLastErrorWhisper(handle)` (`WhisperEngine.kt:179`, `:223`, `:256`,
  `:323`), which returns the engine's own `lastError` — not the thread-local
  message. "Invalid handle", "samples array is required…", "Failed to get
  array" are therefore replaced by a stale engine error or "Push failed".
- Even when the thread-local store is read (`CommonJni.getLastError()`),
  coroutine dispatchers can run consecutive JNI calls on different threads.
- Three overlapping channels exist: `ThreadLocalError`, legacy `ErrorStore`
  (which also `LOG_E`s every message), per-engine `lastError`.
- **Fix:** throw a Java exception with the actual message at the JNI
  boundary (the speech and translation JNI already do this), or return a
  status plus message in one call. Retire `ErrorStore`.


### U9 · P2 · Proven (compile probe) · `PROFILE_SCOPE` spams logcat on the live audio path
**Resolved (`b50952e`).** Two-level macro expansion, monotonic timer, and no
per-push timer on Whisper or Vosk; batch profiling remains.
- `common/scoped_timer.h:161` — `_timer_##__LINE__` pastes the literal
  `__LINE__` (`##` blocks expansion). Probe: two `PROFILE_SCOPE_SILENT`s in one
  scope → `error: redefinition of '_timer___LINE__'`.
- Non-silent `PROFILE_SCOPE` runs on every 50 ms push:
  `whisper_engine.cpp:973` (`WhisperEngine::pushAudioFloat`, which the int16
  path at `:968` calls) and `vosk_engine.cpp:293/340`. Each push takes a global
  mutex, builds a `std::string` key (29 chars, beyond libc++ SSO → heap
  allocation) and emits an **INFO logcat line** (`LOG_I` is not gated by
  `NDEBUG`). That's about 20 lines/s during Whisper or Vosk captions in release
  builds.
- `high_resolution_clock` is not monotonic on libstdc++ hosts; use
  `steady_clock`.
- **Fix:** two-level concat macro; make hot-path timers silent (or sampled)
  with a fixed-size, lock-free per-site counter instead of a global map.

### U10 · P2 · Proven (by reading) · Streaming resampler drifts and resets phase per chunk
**Resolved (`8f1cac9`) for Whisper streaming PCM16/float input.**
`AudioStreamResampler` carries the source clock and previous sample across
pushes under `streamMutex`; it resets on Clear/release or input-rate change.
`df44925` removed the stateless conversion in all three Whisper JNI push
entry points, so Kotlin array/direct-buffer callers reach that same path.
The 16-program common host suite compares full-buffer and deterministic
random-chunk output at 44.1→16, 16→24 and 16→16 kHz, including output count,
continuity and invalid input. The stateless compatibility functions remain
for one-shot callers. Linear downsampling still needs a band-limited source;
anti-alias filtering is a separate open quality task.
- `AudioUtils::resampleInto`/`resampleLinearInto` size each call's output as
  `ceil(n·dst/src)` and restart phase at 0. Streaming 44.1 kHz → 16 kHz in
  1024-sample chunks yields 372 instead of 371.52 samples per chunk: about
  +0.13% (≈4.6 s per hour) of timeline drift, plus a discontinuity at every
  chunk boundary, and no anti-alias filter.
- Not on Hearth's live path (AudioRecord delivers 16 kHz), but used by the
  Whisper/Vosk/ONNX push paths whenever a caller passes another rate, and fatal
  for A/V sync in an editor.
- **Fix:** a stateful `Resampler` object (carry phase and the last input
  sample; optionally a polyphase windowed-sinc for anti-aliasing) with a test
  that feeds random chunk sizes and checks total output length and continuity
  against one-shot resampling.

### U11 · P2 · Proven (by reading) · Shared JNI exception helper violates JNI rules on edge inputs
**Resolved (`873f33c`).** One helper constructs Java exceptions from UTF-16
messages, preserves a pending exception, and all existing catch/null-check
macros use it. A real host JVM with `-Xcheck:jni` passed Unicode, invalid
UTF-8, catch-macro and pending-exception cases.
- `jni/jni_helper.h` `throwRuntimeException`/`throwIllegalArgumentException`/
  `throwIllegalStateException` pass `e.what()` straight to `ThrowNew`, which
  requires Modified UTF-8. A message with a supplementary character (an emoji
  path, backend text such as the speech test's `原因: invalid graph 🧪`) is
  invalid MUTF-8 and aborts under CheckJNI (debuggable builds). They also call
  `FindClass` without first checking for a pending Java exception.
  `JNI_TRY_CATCH_END` uses these helpers.
- `speech_jni.cpp` (`throwSpeechError`) and `translation_jni.cpp` (`fail`)
  already work around both problems locally.
- **Fix:** one central `throwJava(env, className, utf8)` that returns if an
  exception is pending, builds the message with `utf8ToJString`, and uses
  `NewObject` + `Throw`. Route every macro through it.

### U12 · P2 · Latent · `getEnv()` attaches native threads and never detaches
**Attachment resolved (`27b5e0f`).** JNI bridge, ModelLoader and the legacy
adapter share a named daemon attachment with a pthread-key detach destructor;
16 native thread exits passed under a host JVM. **Still open (owner decision):**
`CommonJni.logs` has no producer because `dispatchLog` is unused.
- `jni_helper.cpp` `jni::getEnv()` and `core/model_loader.cpp:98`
  `ModelLoader::getEnv()` call `AttachCurrentThread` without a matching detach.
  In ART, a native thread that exits while still attached ends in
  `LOG(FATAL) "Native thread exited without calling DetachCurrentThread"`
  (a process abort).
- Today it is latent: the only `jni::getEnv()` caller, `dispatchLog`
  (`common_jni_bridge.cpp:71`), has no callers. The first future use from a
  worker thread would crash on thread exit.
- **Fix:** attach once per thread with a `pthread_key` destructor that detaches,
  and name attached threads (`JavaVMAttachArgs.name`).
- Side effect of the dead `dispatchLog`: Kotlin `CommonJni.init()` registers a
  log callback and exposes a `logs` flow that never receives anything.

### U13 · P3 · Library hygiene in `common/`
- **Four UTF-8 validators:** `utf8_utils.h` (`utf8ToUtf16`, `repairUtf8`),
  `json_utils.cpp` (`validUtf8SequenceLength`), `model_integrity.h`
  (`isValidUtf8`). Keep one decoder and build the others on it.
- **Three copies of the file-snapshot comparison** (`model_integrity.h`
  `sameFileSnapshot`, `verified_model_file.h` `sameSnapshot`, `mmap_model.h`
  `sameSnapshot`) and two copies of the 64 KiB `pread` + SHA-256 loop.
- **`cpu_affinity.h` (unused) does not compile on macOS/iOS** (`cpu_set_t`,
  `sched_setaffinity`), and its big-core rule (within 10% of the top max
  frequency) picks only the 2 prime cores on 2+6 or 1+3+4 SoCs such as the
  S25's. Delete it rather than ship it in a reusable library.
- `stt_kit.cpp`/`stt_kit.h` are compiled into `libcommon_jni` with no consumer
  (`core_jni.cpp` notes the bindings were removed). `stt_mode.h` is unused.
- **Two engine contracts:** legacy C++ `IEngine` (string/JSON results,
  `int` status + `getLastError()`) and the versioned C ABI
  `HearthSpeechBackend`. Most cross-engine inconsistencies (F3, F5, F11, U8)
  come from this split. Porting Whisper and Vosk behind `HearthSpeechBackend`
  would retire `EngineRouter`, `IEngine` and the legacy Kotlin `SttEngine`
  stack.

### U14 · P3 · `model_integrity.h` / `verified_model_file.h` / `mmap_model.h`
**Native host coverage resolved (`5094530`).** `model_integrity_test.cpp`
walks a real model directory, checks the same tree digest vector used by
Kotlin `ModelIntegrityTest`, rejects root/nested symlinks, verifies path and
size limits, and tests a changed opened file snapshot under ASan/UBSan. The
remaining cross-language implementation consolidation stays under U18/U20.
- These are the strongest code in `common/`: no-follow `openat`/`fstatat`,
  named-vs-opened inode checks, before/after snapshots, EINTR-safe `pread`,
  size/count/depth/path limits, and a domain-separated tree digest.
  `VerifiedModelFile` feeds whisper.cpp's loader from the same verified fd
  (no check-then-load race).
- The former native host-test gap for the tree walk and shared digest vector
  is closed by `5094530`. The `model-tree-sha256-v1` contract remains
  implemented twice (C++ and `ModelIntegrity.kt`), with a shared golden vector
  guarding compatibility.
- Only Whisper loads through the verified fd; Vosk, sherpa, Nemotron, llama
  and ORT re-open by path after Kotlin verification. That's safe for
  app-private copies; make it an explicit library contract, or add a
  `VerifiedMapping` (mmap + hash + pass bytes to ORT
  `CreateSessionFromArray`), which also removes the second read.
- `MmapModel` (unused) is clean; it's the natural base for that.

### U15 · P3 · Small API notes
- `SecureMemory` (unused): fine as best effort; prefer `explicit_bzero`/
  `memset_s` when available.
- `AudioUtils::processBatch` ignores conversion failure (output left
  uninitialized). `AudioUtils::normalize` has the same up-to-9000× gain as F6.
- `jni/native_registry.h` macros declare reserved identifiers
  (`__handle_guard`, `__obj`).
- `JStringGuard` et al. are sound; `JCriticalArrayGuard` always commits (`0`
  mode) even for read-only use.


### U16 · P2 · Proven (by reading) · `MicRecorder` spins on a dead recorder and drops audio silently
**Read failure and timeline resolved (`41cc027`).** Both capture modes stop on
every negative read, preserve the raw code in `readErrorCode`, enter `ERROR`,
and release the recorder. `RealtimeSttSession` forwards that code through its
error stream and failed `stop()` result. A sample-count clock prevents rounding
drift; direct capture drains into a discard buffer under backpressure, advances
the clock, and counts dropped samples. The short-array read loop is exercised
with injected error codes (including `-6`) in `CaptureReadLoopTest`. The
private pacing duplicate was replaced with `ChunkPacing`.
**Still open:** `audioFlow` uses `SharedFlow` DROP_OLDEST without an observable
drop count; source/channels/encoding remain fixed to `VOICE_RECOGNITION`, mono,
PCM16, and the recorder does not expose `AudioRecord.getTimestamp`.
- `common-jni/.../audio/MicRecorder.kt` has a `RealtimeSttSession` library
  consumer, but no current Hearth app consumer.
- Before the fix, `captureLoop` (`:198-220`) handled only `> 0`, `ERROR_INVALID_OPERATION` and
  `ERROR_BAD_VALUE`; `directCaptureLoop` (`:290-293`) treats every other
  result as "transient, keep going". `AudioRecord.read` returns
  `ERROR_DEAD_OBJECT` (-6) immediately and repeatedly after an audio-server
  restart or invalidation, so both loops **busy-spin at 100% CPU** until
  `stop()`.
- Before the fix, drops were silent: `audioFlow` is a `SharedFlow` with DROP_OLDEST; direct
  mode skips a read (`delay`) when the pool is empty and drops the newest chunk
  when the channel is full. `timestampMs` advances only for delivered chunks
  (and in truncated milliseconds), so after a drop the timestamps are early and
  consumers cannot see the gap — fatal for A/V sync in an editor.
- Hard-coded `VOICE_RECOGNITION`, mono, 16-bit; no `AudioRecord.getTimestamp`.
- The completed slice treats every negative read as an error and uses a sample
  clock. A future slice should make capture format/source configurable and
  account for ShortArray `SharedFlow` drops.

### U17 · P3 · Two resamplers with different semantics; WAV parser strictness
**WAV parser resolved (`8d4d48f`); shared native/Kotlin resampler API remains open.**
`PcmWave` now accepts bounded PCM16 WAVE_FORMAT_EXTENSIBLE and a missing pad
byte only on the final odd-sized metadata chunk. JVM tests cover valid input,
wrong subtype, short extension, invalid bit count/mask, and the unpadded tail.
- Kotlin `audio/Pcm16Resampler.kt` is stateful and chunk-invariant
  (`Pcm16ResamplerTest` proves split == continuous) — the design native
  `AudioUtils::resampleInto` lacks (U10). Both are linear with no anti-alias
  filter for downsampling, and the Kotlin one truncates rather than rounds.
  Keep one algorithm (native, exposed to Kotlin) with a stateful API.
- `audio/PcmWave.kt` is strict and bounded (good for untrusted input), but
  rejects `WAVE_FORMAT_EXTENSIBLE` PCM (`0xFFFE` with PCM subformat, written by
  many recorders/DAWs) and a final odd-sized chunk without its pad byte (common
  in real files). Accept both for general reuse.

### U18 · P3 · Kotlin and native model-integrity contracts differ
- Same `model-tree-sha256-v1` digest, different acceptance rules:
  - Path components: Kotlin `requireSafeName` rejects ISO control characters
    and components over 255 UTF-8 bytes; native `validateRelativePath` does not.
    One directory can verify natively and fail in Kotlin.
  - Expected digests: Kotlin accepts uppercase and a `sha256:` prefix;
    native requires 64 lowercase hex characters.
  - Change detection: Kotlin compares size, mtime and `fileKey`; native also
    compares ctime.
- Kotlin opens files by path (`FileInputStream`) after a NOFOLLOW attribute
  read. A swap to a symlink between the two is caught only if it persists until
  the post-read snapshot; the fd-based native walk is immune. This matters only
  for sources outside app-private storage.
- Publication moves atomically after `fd.sync()` but never fsyncs the parent
  directory, so the rename itself is not crash-durable.
- **Fix:** one written contract for path rules, digest encoding and change
  detection. The shared digest golden is now checked in both
  `ModelIntegrityTest` and the native host test (`5094530`).

### U19 · P3 · Other Kotlin/JNI helper notes
- `handles/NativeHandle.kt` (unused) releases native resources from
  `finalize()`. Native destroy can block on an in-flight inference lease, and
  Android's FinalizerWatchdog kills the process after about 10 s. `close()`
  logs and swallows release failures. Prefer explicit `close()`, plus
  `java.lang.ref.Cleaner` (API 33+) or a leak-reporting `PhantomReference`.
- `common/jni_utils.h` duplicates `jni/jni_helper.h`: its
  `CriticalArrayGuard` releases with `JNI_ABORT` while `JCriticalArrayGuard`
  commits; `GlobalRef` stores a `JNIEnv*` and uses it in its destructor
  (`JNIEnv` is thread-local, so destroying it on another thread is undefined
  behavior); `getEnvForCurrentThread` is a third attach-without-detach (U12);
  `JNI_CHECK_EXCEPTION*` describe **and clear** Java exceptions, turning a
  failure into a null return. Only the critical-array guards are used
  (`stt_jni.cpp`). A JNI-only header also lives in portable `common/`.
- `jni/transactional_cache.h` is correct and unused.
- Media-suite leftovers with no consumer, about 1,400 lines:
  `audio_decoder.h` (`IAudioDecoder`), `audio_format.h`, `audio_types.h`
  (`AudioFrame`, `VideoFrame`), `image_engine.h`, `image_types.h`,
  `image_utils.h` (`vision::`, NEON colour conversion). Move them to the media
  repository or a separate media-core module instead of shipping them unused.


### U20 · P3 · Proven (AOSP source) · Kotlin and native JSON parsers accept different languages, and tests use a third
- Native `JsonUtils::parse` is strict RFC 8259 (duplicate keys and leading
  zeros rejected). Android's `org.json` is documented lenient in AOSP
  `JSONTokener.java`: it accepts `//`, `#` and `/* */` comments, unquoted and
  single-quoted strings, `0x` hex and leading-`0` **octal** integers, and
  `=`/`=>`/`;` separators, and it **silently overwrites duplicate keys**
  (`result.put((String) name, nextValue())`).
- The JVM unit tests use `org.json:json:20231013`
  (`common-jni/build.gradle.kts`), a different implementation (it rejects
  duplicate keys and treats `01` as a string), so host tests do not exercise
  device parsing. Example: a manifest with `"schemaVersion": 01` is accepted
  on device and rejected in tests.
- Impact is limited for model packages because `SpeechModelPackage.verify`
  independently checks the on-disk file set and every SHA-256.
  `JsonInterop.parseDocument` also swallows the parse exception, so the actual
  error text is lost.
- **Fix:** parse security-relevant manifests with one strict parser on both
  sides (the native one through a small JNI call, or a strict Kotlin reader), or
  run manifest tests as Robolectric/instrumented tests against Android's
  `org.json`.

### U21 · P2 · Proven (by reading) · `core/vad.cpp` decisions depend on the caller's chunk size
- `VadDetector::pushAudio` (`core/vad.cpp`) splits each call into
  `windowSamples_` windows starting at that call's offset 0. With 800-sample
  reads and a 480-sample (30 ms) window, windows alternate 480/320 samples.
  The fixed 5-window dB history then covers a different duration depending on
  read size. `UtteranceSegmenter` explicitly avoids this by buffering fixed
  20 ms frames.
- In streaming mode `segments_` grows without bound (cleared only by
  `process()`/`reset()`), and `getSegments()` copies all of them.
- `inSpeech_` is atomic but the other state isn't; safe only because
  `core_jni` serializes access (`acquireSerialized`).
- It's the third energy VAD (with `AudioGate` and `UtteranceSegmenter`) and has
  no Hearth consumer (only Kotlin `VadDetector`).
- **Fix:** carry a partial-window buffer across pushes (as the segmenter
  does); bound or drain segments; keep one VAD component, and treat a neural
  VAD (sherpa's Silero) as the upgrade path (H3).

### U22 · P3 · Kotlin mirrors of native types have drifted
- `config/AudioGateConfig.kt` defaults (−40 dB, ZCR ceiling 0.3, 300/500 ms,
  one smoothing alpha 0.1) differ from native `AudioGateConfig` (−45 dB, 0.55,
  200/200 ms, attack 0.5 and release 0.25). A ZCR ceiling of 0.3 would reject
  the fricatives the native comment calls speech. Its only apply path,
  `nativeSetAudioGateConfig`, throws `UnsupportedOperation`, so it's a
  misleading dead API.
- `error/NativeError.kt` mirrors `ErrorCode` but lacks
  `ENGINE_CREATE_FAILED` (204), `INIT_FAILED` (205) and `MEMORY_ERROR` (250),
  and still carries the FFmpeg codes (500-505).
- `perf/ChunkPacing.kt` is documented as a copy of `MicRecorder`'s private
  pacing code; have `MicRecorder` use it. `RollingStats` keeps a
  subtract-on-evict float sum (slow drift over very long sessions).


### U23 · P2 · Proven (by reading) · Marian tokenizer: robust parsers, three reuse gaps
**Partly resolved (`dc3f32c`):** both files are size-checked before a bounded
single allocation/read; non-finite/out-of-int64/fractional added-token IDs are
rejected before casting; the surrogate pointer check is defined. A tiny
valid-model fixture checks token IDs and oversized sparse files/1e300 under
ASan/UBSan. **Still open:** malformed charsmap rejection and golden vectors
from the official tokenizer for pinned language pairs. Changing normalization
output or rejecting formerly loaded malformed models needs owner approval.
- `marian/marian_tokenizer.cpp` (hand-written SentencePiece protobuf reader,
  bounded JSON walker, Darts charsmap normalizer, unigram Viterbi). Bounds are
  careful: varint shifts capped, length-delimited fields checked against the
  remaining bytes, Darts indices checked on every step, `strlen` bounded by
  `std::string`'s terminating NUL, special-token ids range-checked, exactly
  one UNKNOWN piece required.
- **Reads before limiting:** `load()` (`:786-797`, `:896-907`) reads the whole
  spm and `tokenizer.json` through `ostringstream` and copies with `.str()`
  (about 2× file size in memory) before comparing with the 16 MB / 64 MB
  limits. A wrong multi-GB path is fully read before rejection. `stat` first
  and read at most limit + 1 bytes.
- **Silent normalization loss:** a charsmap blob shorter than 1028 bytes, or with
  an inconsistent trie length or missing NUL (`:847-863`), is dropped
  silently, and tokenization then differs from Hugging Face with no error.
  Fail loudly instead (identity-normalizer models have no blob, so they're
  unaffected).
- **UB on hostile ids:** added-token `id` is parsed as `double` and cast with
  `static_cast<int64_t>` (`:1063`) before the range check; `1e300` or `NaN`
  is undefined behavior. Range-check the double first.
- **No host test:** add a golden test with fixture sentences and the token ids
  produced by `transformers.MarianTokenizer` for each pinned pair (the phone
  runs exercise it only indirectly).
- Minor: `p_ + 1 >= end_` in the surrogate check can form a pointer two past
  the end (technically UB); use `end_ - p_ < 2`.


### U24 · P2 · Proven (by reading) · Marian engine: spin-waiting threads and base-size-only models
**Spinning resolved (`2959fef`):** ORT intra-op worker spinning is disabled
at every Marian session setup, `MARIAN_ORT_SPIN=1` explicitly opts in for
benchmarks, and an ORT rejection returns its actual error. Nine fake-ORT host
checks cover the policy. **Still open:** model-derived dimensions and one
long-lived deadline timer per engine.
- **Spinning:** `marian_engine.cpp` `applyEnvSessionConfig` sets
  `session.intra_op.allow_spinning=0` (and `arena.extend_strategy`) **only**
  when the process environment has `MARIAN_ORT_SPIN=0` / `MARIAN_ORT_ARENA=1`.
  Nothing sets them on Android, so Marian's 4 intra-op threads use ORT's
  default spin-wait while ASR runs on the same cores. The OCR sessions
  (`paddle_ocr.cpp:25`, `japanese_ocr.cpp:59-60`) disable spinning
  unconditionally. Make it a real option (default off for live captions).
- **Model shape:** `marian_engine.h:53-56` hard-codes 6 layers, 8 heads,
  head dim 64 and hidden 512 (OPUS-MT base). "tc-big" pairs (1024 hidden, 16
  heads) fail at runtime with an ORT shape error. Read the dimensions from the
  session's input metadata.
- **Per call:** `RunDeadline` starts and joins a `std::thread` for every
  translation (created even before input validation). One long-lived timer
  thread per engine would do. Decoder KV copies per step: see H6.
- Sound: cancel and deadline use `RunOptionsSetTerminate`; each translate
  clears the terminate flag under the options lock, and the previous deadline
  thread is always joined first, so a stale timer cannot abort a new sentence.
  The JNI reports errors through thread-local storage read by a separate call
  (`nativeGetLastError`). Both current callers (`TranslationLayer.kt:193-196`,
  `MarianTranslationSession.kt:31-32`) read it without suspending, so it is
  correct today but fragile for other apps (see U8).


### U25 · P2 · Proven (by reading) · Vosk partials grow with the whole session
- `vosk/vosk_engine.cpp` `getPartial()` builds each partial from **every
  final segment since the session started** plus Vosk's current partial, then
  JSON-escapes it (non-ASCII → `\uXXXX`), converts it to UTF-16 and hands it
  to Kotlin, which parses it again. `segments` is cleared only on reset,
  release or batch transcription (`:112`, `:145`, `:475`, `:531`), never
  during streaming.
- The controller polls Vosk every 500 ms, so per-poll cost and memory grow
  linearly and total cost quadratically over a long session. An hour of speech
  (~50 KB of text; 3–6× larger once Arabic/CJK is escaped) is rebuilt, escaped,
  converted and re-parsed twice a second.
- `CaptionEngineController` works around the symptom (`PARTIAL_MAX_CHARS = 200`
  head promotion, `SILENCE_PROMOTE_MS`). Its comment attributes the endless
  partial to Vosk, but the accumulation of previous finals comes from this
  wrapper; Vosk's own `partial_result` restarts after each final.
- **Fix:** return only the current Vosk partial, and expose new finals
  separately (a `takeFinals()` queue like `RemoteWhisperEngine`/the speech ABI
  path), so the controller promotes finals directly. Bound `segments` to the
  unconsumed ones.
- `OnnxEngine` (`onnx/onnx_engine.cpp`, 462 lines) has no Hearth consumer
  (no `CaptionEngineChoice` uses it); it was not reviewed line by line.


### U26 · P3 · OCR adapters: sound, with an undocumented permanent cancel
- `ocr/paddle_ocr.cpp`, `ocr/japanese_ocr.cpp`, `ocr/ocr_jni.cpp`: input
  capped at 2048 px per side, output rank/shape/element count bounded,
  non-finite scores rejected, Manga token and 45 s limits, ORT spinning disabled,
  LeaseRegistry handles. Pixel order is correct: Java `int` ARGB
  (`0xAARRGGBB`) read as little-endian `uint32` puts B in byte 0, giving the
  BGR planes the publisher preprocessing expects.
- `cancel()` sets `cancelled` and `RunOptionsSetTerminate` and never clears
  them, so the engine is dead afterwards. Hearth only cancels right before
  closing (`CameraOcrController.kt:145`), but the `OcrEngine.cancel()`
  interface doesn't say so; another app would reasonably expect per-run cancel
  (as Marian implements). Document it or reset per call.
- Minor: input/output names are looked up through the allocator on every run;
  the detector output (up to 2048×2048 floats) is copied out of the ORT value.

### U27 · P2 · The whole `tts/` stack is dead weight in the library
- `tts/tts_jni.cpp`, `tts_engine_factory.cpp`, `vits_onnx_engine.cpp`,
  `tts_router.h`, `tts_engine_interface.h` (about 1,600 lines) plus Kotlin
  `TtsKit`, `TtsEngine`, `BaseTtsEngine`, `TtsNative` (about 870 lines) have
  no app consumer (only a comment in `CaptionOverlayConfig.kt:93`). Every
  `ENABLE_*` backend is OFF (`CMakeLists.txt:252-254`), so it can only report
  "no engine", yet it's compiled into `libcommon_jni` and kept by the consumer
  ProGuard rule. Read-aloud actually runs through `voice/` Supertonic. Owner
  decision (tracked): keep it as an optional module, or remove it.

### U28 · P2 · Hypothesis · Read aloud reloads the Supertonic model for every request
- `VoicePlayer.kt:125` opens `SupertonicVoice` (four ONNX sessions:
  duration predictor, text encoder, vector estimator, vocoder) inside each
  speak request and closes it at `:131`. Every caption line spoken through
  `speakLine` pays the model load before the first sample plays.
- `voice/supertonic.cpp` itself is careful (bounded shapes and sizes, finite
  checks, host-tested `voice_bounds.h`, spinning disabled); its `cancel()` is
  permanent, which is consistent with one instance per request.
- **Measure** time to first audio with a cold vs warm instance. If load
  dominates, keep one instance warm while the player is active (with an idle
  timeout) and make cancel per-run.

---

## Log (append-only)
- 2026-09-23 — Initial review at 46d42b7. Findings F1–F10, H1–H10. Baseline
  suites not run by request. Patches A and B in progress.
- 2026-09-23 — **Environment finding (E1):** on this Mac (macOS 26.5 25F71,
  Xcode-beta Apple clang 17.0.0), any `-fsanitize=address` binary, even
  hello-world, hangs inside the ASan allocator (`__sanitizer_mz_malloc`). Every
  `run_*_tests.sh` uses ASan, so the host suites cannot finish with the default
  `c++` here. Workaround: `CXX=/opt/homebrew/opt/llvm/bin/clang++` (Homebrew
  clang 22.1.8) works. Consider making the runners prefer a working
  `$CXX`/Homebrew LLVM, or print a hint when the ASan runtime stalls.
- 2026-09-23 — Patches A (UTF-8 repair) and B (AudioGate non-finite) done with
  tests; see §6 for results. `docs/utils-bible.md` updated.
- 2026-09-23 — Reviewed `sha256.h` (matches FIPS 180-4; no known-answer test
  existed → patch C), `lifecycle_gate.h` (sound as a primitive; misuse in
  Whisper `safeReset` → F11, patch D), and a JNI exception-guard scan (F12,
  patch E for the path touched). New hypothesis H11.
- 2026-09-23 — Added §10 coverage: about 20% of native and 11% of Kotlin
  lines read in full so far; next-pass order recorded.
- 2026-09-23 — Branch state: the owner committed the patches as `1768a12`
  ("other agent"; it also committed `.vscode/settings.json` with a local path),
  then `git pull origin codex/model-phase` on this branch merged upstream
  `6ad8305` (Nemotron uncertain-tag fix, rebuilt `libhearth_nemotron.so`) as
  `91b2a3c`, now pushed. Local `codex/model-phase` is still `46d42b7`. F1
  re-checked on the new `libhearth_nemotron.so` (2,267,200 bytes): still 0
  `sdot`/`udot`/`smmla`/fp16 `fmla .8h`.
- 2026-09-23 — Pass 2 started (reusable core): json_utils, buffer_view, buffer_pool,
  cancel_token, pipe_progress, job_system, job_future, json_options, both
  registries, error_codes, logging. Findings U1–U8 (§11). Probe results: U2 and
  U3 confirmed; suspected json_options locale bug refuted.
- 2026-09-24 — Pass 2 continued: model_integrity (rest), verified_model_file,
  mmap_model, secure_memory, cpu_affinity, scoped_timer, audio_utils (rest),
  engine_interface/stt_kit/stt_mode, jni_helper, jni/native_registry. Findings
  U9–U15; U9 macro bug confirmed by compile probe.
- 2026-09-24 — Pass 2 finished for `common/` and `jni/`; started the Kotlin
  library (audio, ModelIntegrity, NativeHandle). Findings U16–U19. Coverage
  updated in §10. Still unread: vosk/onnx/marian_tokenizer/engine_router,
  core/, ocr/, tts/, voice/, rest of whisper_engine and stt_jni, and most
  remaining Kotlin (engine/, core/, tts/, perf/, error/, json/, config/).
- 2026-09-24 — Kotlin perf/, json/JsonInterop, error/, config/AudioGateConfig
  and native core/vad reviewed. Findings U20–U22 (U20 confirmed from AOSP
  JSONTokener source).
- 2026-09-24 — marian_tokenizer.cpp read in full (U23).
- 2026-09-24 — marian_jni + marian_engine (rest) reviewed (U24). **Corrected H10**:
  Marian does not disable ORT spinning by default.
- 2026-09-24 — engine_router and vosk_engine (push/partial/finalize) reviewed (U25;
  router notes folded into U13/U8).
- 2026-09-24 — Whisper initialize/push reviewed (sound); H12 added (double hashing).
- 2026-09-24 — OCR, voice (Supertonic) and tts/ usage reviewed (U26–U28). Coverage
  in §10 updated (native ≈75%, Kotlin ≈33%).
- 2026-09-24 — Owner set priorities (utils, speech, translation, ffmpeg, audio;
  Whisper deprioritized). Added §0 handoff with per-area status, remaining files
  (~1,700 lines in scope, plus upstream 6ad8305) and ordered next actions.
  FFmpeg review blocked pending a current original checkout.
- 2026-09-24 — Owner: nothing is dropped, only refocused. §0 now says "Later
  queue (tracked)" instead of "deprioritized", and lists every non-focus area
  with its open findings and unread files. The previous log line's
  "deprioritized" means "in the Later queue".
- 2026-09-24 — Phase 0 at `02aa830`: reviewed patches A–E and `6ad8305`;
  A/B/C/E approved, D change requested with the abort/finalize evidence in
  F11. The current Mac's Apple Clang ASan probe and 9 common/57 speech checks
  passed; Gradle test, both QA compiles, native debug build, both QA assembles
  and `verify-release.py` passed with the exact APK sizes recorded in §6.
  No device or paid call. The prior Whisper fix branch `7b44a00` remains
  unmerged in the tracked Later queue.
- 2026-09-24 — Follow-up on `review/common-jni-audit`: U9 `b50952e`, U3
  `1fd333c`, U2 `6a44656`, U4 `a45d6b7`, U11 `873f33c`, and U12 attachment
  `27b5e0f` landed as separate review commits with host coverage and
  `docs/utils-bible.md` entries. Before each code commit, all Gradle unit
  tests, both QA Kotlin compiles, common/speech host suites and native debug
  build passed; the final common suite has 12 programs (including a real JVM
  `-Xcheck:jni` test), speech has 57 checks. U4 also passed host TSan. The
  current Mac has a Git checkout of the original media app at
  `/Users/salehalanazi/ZCodeProject/ffmpegmakercustom`; FFmpeg is queued
  after audio, not blocked by a missing checkout. No device or paid call.
- 2026-09-24 — Speech packaging pass: read `SpeechTranslation.kt`, app
  `PublisherSpeechPackage.kt`/`LocalSpeechModels.kt`, `speech_smoke.cpp`, and
  six runtime/package scripts. H13/H14 added with file:line evidence. The
  H4 source/test slice `f863476` passed common/speech/Gradle/native gates,
  but `verify-release.py:30-32` requires its pinned Qwen runtime binary to be
  rebuilt. Reverted by `e9903e6` after rerunning the same gates;
  `verify-release.py` then passed. H4 remains open for a coupled rebuild.
- 2026-09-24 — Marian slices: `dc3f32c` bounds tokenizer file reads before
  allocation and rejects hostile numeric IDs before conversion; the new host
  fixture passed a valid token sequence, sparse over-limit files and 1e300.
  `2959fef` disables ORT worker spinning by default beside ASR; a fake-ORT
  test passed nine policy/error checks. Before both commits, common (now 13
  programs), speech (57 checks), Marian host ASan/UBSan and the required
  Gradle/native debug gates passed. Both QA APKs were assembled on the final
  code and `verify-release.py` passed: Play 44,619,594 bytes; FOSS 36,694,653
  bytes, both 16 KiB aligned with the expected permissions. U23 malformed
  charsmap/goldens and U24
  dynamic shapes/deadline timer remain open; no device or paid call.
- 2026-09-24 — Main-bound integration: `review/common-jni-audit` merged with
  `codex/model-phase` already included; the old `hearth-branding` branch is an
  ancestor of main. The separate Whisper lifecycle patch `7b44a00` was
  reviewed and integrated, combining both branches' utility test lists and
  documentation. The accidentally tracked `.vscode/settings.json` was
  excluded. After the Whisper integration, Gradle test, both QA Kotlin
  compiles, native debug build, 15-program common host suite, 57-check speech
  suite and Marian tokenizer host suite passed. Reset/release now abort the
  worker decode, while finalize drains it; a model-free native lifecycle
  regression test remains tracked under F11. No device or paid call.
- 2026-09-24 — U10 `8f1cac9`: added a chunk-invariant native streaming
  resampler and connected both Whisper PCM16/float streaming paths while
  preserving the existing one-shot APIs. Before commit, Gradle test, both QA
  Kotlin compiles, native debug build, 16-program common host suite and
  57-check speech suite passed. No device or paid call.
- 2026-09-24 — U17 WAV slice `8d4d48f`: checked the extensible header layout
  against Microsoft's WAVEFORMATEXTENSIBLE documentation and added bounded
  parsing for its PCM16 subtype plus a missing final metadata pad byte. The
  targeted `PcmWaveTest`, all Gradle unit tests and both QA Kotlin compiles
  passed. Kotlin/native resampler API consolidation remains open. No device or
  paid call.
- 2026-09-24 — F4 `5f2af68`: AGP's RelWithDebInfo compile command now includes
  `-O3` and section flags. The first IPO-enabled build failed verbatim with
  `clang++: error: invalid linker name in argument '-fuse-ld=gold'`; disabling
  Android IPO kept the supported lld path. Gradle test, both QA Kotlin
  compiles, native debug and release builds, 16-program common and 57-check
  speech suites passed. Play QA 44,684,502 bytes and FOSS QA 36,759,541 bytes
  both passed `verify-release.py` with expected permissions and 16 KiB page
  alignment. LTO and measured device speed remain open; no device or paid call.
- 2026-09-24 — U10 end-to-end follow-up `df44925`: `stt_jni.cpp` was still
  resampling non-16 kHz Whisper chunks statelessly before the engine could
  carry phase. All three JNI push variants now pass the caller's source rate
  through after copying/unpinning arrays where necessary. Gradle test, both
  QA Kotlin compiles, native debug build, 16-program common and 57-check
  speech suites passed. Play QA 44,681,482 bytes and FOSS QA 36,756,525 bytes
  passed `verify-release.py`. No device or paid call.
- 2026-09-24 — Main follow-up: `c3d8b64` and `e2071dc` replaced hidden
  Whisper/Vosk/ONNX JNI validation errors with exceptions carrying the actual
  cause; the 16-program common suite (including real-JVM CheckJNI), 57-check
  speech suite, Gradle unit tests, QA Kotlin compiles and native debug build
  passed. `5094530` added the shared Kotlin/native model-tree digest golden,
  symlink, path/size limit and changed-file host checks. Its 17-program common
  suite, 57-check speech suite and required Gradle/native gates passed.
  FFmpeg JNI error conversion and direct device coverage remain open. No
  device or paid call.
- 2026-09-24 — U16 `41cc027`: extracted an injectable short-array capture
  loop, stopped every negative microphone read, reported the raw failure code
  through `RealtimeSttSession`, and used a sample-count clock across delivered
  and dropped direct-buffer audio. The first full Kotlin gate failed verbatim
  with `Unresolved reference 'getAndSet'` because the coroutine scope's
  `isActive` shadowed the session field; explicit qualification fixed it. The
  final Gradle unit tests and both QA Kotlin compiles passed. Device capture
  and ShortArray overflow accounting remain unverified/open. No device or paid
  call.

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
- Not done in this pass: no Gradle or full host suites were run (the owner asked
  for review, not runs; the baseline runs I started were stopped). No device was
  used. Only the targeted host tests for the patches in §6 were run.
- The original `ffmpegmakercustom` repo is not on this machine at the given path.
  The only copies found are a 2025-09-06 git clone
  (`~/AndroidStudioProjects/whisperIME/ffmpegmakercustom`) and an April 2026
  non-git snapshot (`~/Downloads/ffmpegmakercustom-main`). Neither has
  `media_engine.h`, `MediaEditKit.kt`, `latest_handover.md` or the media test
  runner, so the FFmpeg media engine is **not reviewed** here (§9).

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
- **Fix:** delete, or move to an excluded `legacy/` directory. Add the JNI
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
- **Follow-up:** propagate a rejection to Kotlin (`reset()` is
  `virtual void` on the shared `ISttEngine`; add a `bool tryReset()` or check
  `getLastError()` in JNI). Add a native test seam so safeReset can run on the
  host without a model.

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
- **H10 · P2 · Hypothesis · CPU oversubscription during captions + translation.**
  Nemotron has no thread knob (default threads), Marian runs 4 ORT threads
  (spinning disabled — good), llama runs 2 and Whisper up to 8, all on big.LITTLE
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

Status: implemented on `review/common-jni-audit` (not committed). Results:
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
(~11%)** were read line by line. The rest was covered only by whole-tree scans
(below), which find classes of bugs, not every bug.

**Read in full:** `speech/` (session, segmenter, endpoint budget, backend ABI,
Qwen/Moonshine/Omnilingual and Nemotron adapters, speech JNI), `translation/`
`text_model.h` and `translation_jni.cpp`, `common/audio_gate.h`,
`ring_buffer.h`, `sha256.h`, `lifecycle_gate.h`, `utf8_utils.h`, `CMakeLists.txt`.
Kotlin: `SpeechSession`, `LiveSpeechProcessor`, `SpeechModels`,
`SpeechModelPackage`, `CaptionTranslationBridge`.

**Read in part:** `whisper_engine.cpp` (~550/1330: worker, params, inference,
reset/release; not initialize, push, detectLanguage, batch), `router/stt_jni.cpp`
(~200/1103), `common_jni_bridge.cpp` (JNI_OnLoad and the registration table
only), `marian_engine.cpp` (~200/846: session options and decode step),
`common/audio_utils.h` (~250/604: conversions, resampler, alignment),
`model_integrity.h` (digest helpers only, 140/770; not the tree walk),
`verified_model_file.h` (80/465), `lease_registry.h` (80/213),
`json_utils.cpp` (string escaping only; not the parser), `jni/jni_helper.h`
(UTF-8 conversion and macros), `WhisperEngine.kt`, `CommonJni.kt` (externals).

**Not read (scans only):** `vosk/vosk_engine.cpp`, `onnx/onnx_engine.cpp`,
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

**Next pass, by risk:** (1) parsers of untrusted files: `json_utils.cpp`
parser, `marian_tokenizer.cpp`, `model_integrity.h` tree walk and
`verified_model_file.h` (fd, TOCTOU, size limits); (2) the rest of
`whisper_engine.cpp` and `stt_jni.cpp` (push paths, detectLanguage, batch);
(3) `vosk_engine.cpp`, `onnx_engine.cpp`, `engine_router`; (4) `buffer_pool.h`,
`native_registry.h`, `jni_helper.cpp`; (5) OCR, TTS, voice, `core/`;
(6) the rest of the Kotlin library, starting with `MicRecorder` and `ModelIntegrity`.

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

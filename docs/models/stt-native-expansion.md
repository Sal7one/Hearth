# Native multilingual speech expansion — 20 October 2026

Three new speech engines are wired end to end: Cohere Transcribe Arabic 07-2026 Q4_K_M, Fun-ASR MLT Nano 2512 Q8_0, and Voxtral Mini Realtime 2602 Q4_K_M. Model downloads use exact revision URLs, SHA-256 and complete self-contained GGUF package sizes. They are available in the speech explorer and Advanced model management, with per-engine persisted CPU/Vulkan selection, per-profile window tuning, strict imports and portable setup validation. New native runtimes are currently ARM64 only; their model actions are disabled on unsupported ABIs. TV local-model restrictions remain enforced independently.

Each speech stage remains independent of translation and TTS. Cohere requires an explicit Arabic or English source. Fun-ASR accepts supported hints or Auto. Voxtral receives **no recognition language hint**. A user may declare a known supported source solely to route independent translation, as with TranscribePsy. The pinned Voxtral port does not currently supply a detected-language result, so Auto preserves unknown source rather than inventing Arabic or English. Its partials are bounded to the selected utterance window and correctly displayed as windowed partials.

The native port is pinned to [transcribe.cpp 1dc22facfc931e2f8adb43924b0e38b9c46f83c7](https://github.com/handy-computer/transcribe.cpp/tree/1dc22facfc931e2f8adb43924b0e38b9c46f83c7). The private CPU and Vulkan DSOs export only three Hearth C ABI entrypoints. CPU probing does not load graphics drivers; explicit Vulkan verifies the actual primary backend and reports errors without silently falling back. This primary-backend check does not claim every graph operator runs on GPU; feature extraction remains CPU. Exact build inputs, NDK/compiler provenance, model-free runtime licenses, symbols, sizes and hashes live in `common-jni/src/main/assets/licenses/speech-expansion/runtime-build.json` and `jniLibs/SHA256SUMS`.

Build and stage with the scripts below. Vulkan requires the existing pinned Vulkan/SPIRV headers and shader compiler prepared for translation builds. Default model-free host adapter tests work offline from checked-in pinned public headers and fake only the external engine API.

```sh
scripts/speech/build-expansion-runtimes.sh android cpu
scripts/speech/build-expansion-runtimes.sh android vulkan
python3 scripts/speech/stage-expansion-runtimes.py
scripts/speech/run-expansion-tests.sh
python3 -m unittest discover -s scripts/speech -p 'test_*.py'
```

The independent native test covers CPU and Vulkan initialization, exact model architecture/variant identity, language hints versus source declarations, allocation/engine errors, cleanup, resets, bounded utterance finals, same-text partial-to-final delivery, silence, output truncation and rejection of GPU-to-CPU fallback. On macOS 26.5, Apple's older sanitizer runtime hangs before `main`; the script prefers current installed Homebrew LLVM, while allowing `CXX` override. This is a host tooling workaround, not a disabled sanitizer.

The selected **891,271,232-byte Fun-ASR Q8** payload was downloaded and independently hash-verified, then exercised through the actual host CPU DSO and the same `HearthSpeechBackend`/`SpeechNativeSession` used by JNI. Arabic and English public WAV fixtures produced nonempty final captions, unique utterance IDs and correct source labels. The downloaded weight was removed after verification. Public fixture hashes, output and host timings are recorded in `stt-native-smoke-2026-10-20.json`.

These results are functional host evidence, not a WER study or S25 benchmark. The Android CPU/Vulkan binaries are cross-compiled and ELF/provenance checked; owner device tests remain necessary. Complete Cohere and Voxtral weight inference was not run in this session. Audar's split decoder/BF16 encoder and OWSM's different audio.cpp runtime remain separate prepared targets in `docs/handoff/models/stt.json`; they are not advertised as runnable in the app. Fun-ASR's selected export carries its model-specific v1.1 license, linked explicitly and retained in notices, independently of runtime MIT and the original model card's different current metadata label.

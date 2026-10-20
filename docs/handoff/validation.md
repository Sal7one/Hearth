# Model roadmap validation — 20 October 2026

This is the current implementation evidence for `codex/s25-models-tts-provider`,
which began from `v0.25.4` (`6c15890b284b6c74074d13d7d45d1311d0cf6035`).
It supersedes the initial preparation-only checkpoint. The current app version is
0.27.1/code91. A source build, a real host model run, owner device acceptance and
release publication remain distinct milestones.

No agent has driven a phone/TV, used adb, completed owner listening or measured
S25/S22 latency, sustained heat, battery or peak memory. Publisher quality scores
are not relabeled as Hearth quantized-device scores. No Ming checkpoint/CUDA
inference or Maven publication has run here.

## Latest checks

| Check | Evidence/result |
| --- | --- |
| Combined Play/FOSS QA compilation and focused app/common/SDK tests | Initial passed checkpoint: `build/roadmap-ui-fifth-gates.log`, 37s; superseded by the confirmed full gate below |
| common JNI plus Supertonic/Omni/MOSS/Oute/Magpie `externalNativeBuildDebug` | Passed all app ABIs at checkpoint: `build/roadmap-native-gates.log`, 13s; real optional runtime payloads remain ARM64-only |
| Model-card validator/renderer | 15 records, 68 default-profile components; exact pinned public source identities and totals checked |
| Model-card host regressions | 8/8 passed, including credential/mutable-pin rejection for HF/raw GitHub/ModelScope and Jisr exact six-file converted packages/manifest IDs/precision/totals |
| Ming hosted API tests | 6/6 passed: inventory, component integrity and actual loopback HTTP/request/error/auth contracts with injected inference |
| Voice-server API tests | 11/11 passed: official model API contracts and failure handling with injected inference |
| Oute production sampling helper | ASan+UBSan passed: stable probabilities, min-p/top-p, relative weights and invalid-logit rejection |
| Oute production JNI contract | Passed real bridge/fake C ABI: UTF-8 errors, exact Java callback exception identity, empty/non-finite/oversized PCM errors, recursive synth rejection, same-thread/worker-thread close, concurrent cancel/retire |
| Full `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin`, Chatterbox native build and all eight release AAR assemblies | Passed in 2m14s: `build/roadmap-final-gradle-gates.log`; full tests/both QA compiles confirmed after final app source changes in 1m31s: `build/roadmap-confirmed-gradle-gates.log` |
| Native speech/common/Supertonic sanitized host suites | Passed with ASan/UBSan; Magpie/Omni/MOSS/Oute/Chatterbox JNI contract fixtures also passed |
| CT2 clean-source attestation/restaging | Passed: `build/jisr/final-ct2-build.log`; regenerated source attestation and staged ARM64 runtime SHA256 `abcfa3ab4bec651a6938f0f3353767f2a6d36f88fdf87e91583b72237e109b8a` |
| Corrected SDK packaging and `python3 scripts/verify-tts-aars.py` | Passed: `build/roadmap-aar-packaging.log`, 21s; all eight archives verified, with ONNX solely in its shared runtime AAR |
| Riva prompt correction and Android runtime provenance | Production prompt matches independent publisher Jinja rendering; all six CPU/Vulkan/OpenCL binaries rebuilt/staged with source-set SHA256 `3f88097379a460fc6e0488ed9c378bf5b001ddf3a31cb7f66dbc45d2509418cc` |
| Added-model source URL audit | [22 publisher/files/license links checked](../research/model-source-link-audit-2026-10-20.json); missing MOSS license path corrected to pinned upstream LICENSE. ModelScope HEAD404/Audar HEAD403 resolved by bounded GET200; no weights downloaded |
| Final confirmation after Riva runtime and MOSS license correction | Passed: full tests, both QA compilations and assemblies in 2m55s, `build/roadmap-source-frozen-qa.log` |
| Both QA assemblies and `python3 scripts/verify-release.py` | Passed for all ten ABI/universal artifacts: signatures/current QA package/version, native hashes/ELF/16 KiB alignment and permissions; FOSS remains offline. `build/roadmap-apk-verification.log` |
| Final eight-AAR distribution check | Passed again after final builds: `build/roadmap-aar-final-verification.log` |
| Remote v0.27.0 draft audit | All 63 uploaded asset sizes and SHA-256 digests match; signed tag, source archive and embedded APK revision all bind `ecd1dc02029f66570863633ad890d411f0b103f3`. The tag/artifacts remain unchanged |
| Final card/CI metadata audit | v0.27.1 corrects stale documentary readiness after real model runs and installs NumPy2.3.5 in isolated CI Python environments. A clean Python3.12 venv passed all66 voice/Ming/catalog/ABI/delivery/speech/benchmark Python tests, all five JNI contract fixtures and Oute sanitized sampling. No Torch/ORT/model download is required for those contract checks |
| v0.27.1 source-frozen confirmation | Full Gradle tests, both QA compiles/assemblies and all eight SDK release assemblies passed in2m29s (`build/roadmap-0271-final-gates.log`); ten APKs and eight AARs independently verified. Version91/0.27.1; runtime code/pins are unchanged from the v0.27.0 integration snapshot |
| Tagged GitHub draft inputs | Final source, verified artifacts, model ZIPs and SDKs are prepared for v0.27.1. The draft's BUILD-PROVENANCE.json and SHA256SUMS bind its tested commit and delivered artifacts |
| Owner device acceptance | Remains with the owner; no phone/TV/adb or listening/performance claim |

Tests using loopback HTTP servers needed local socket access beyond this
workspace sandbox; they contact no model/provider service and use injected
inference. Those passing contracts do not claim hosted checkpoint inference.

## XML test totals at the confirmed gate

All listed configurations have zero failures and errors. Counts are per
configuration; repeated flavor/build runs are not additional distinct test cases.

| Module/configuration | Tests | Skipped |
| --- | ---: | ---: |
| App Play Debug, QA and Release, each | 674 | 2 |
| App FOSS Debug, QA and Release, each | 674 | 11 |
| common-jni Debug and Release, each | 136 | 0 |
| TTS core Debug and Release, each | 9 | 0 |
| Supertonic Debug and Release, each | 7 | 0 |
| Chatterbox Debug and Release, each | 10 | 0 |
| Magpie, OmniVoice, MOSS and Oute Debug/Release, each provider/configuration | 3 | 0 |

These numbers were read from each module’s `build/test-results/*/TEST-*.xml`
after the confirmed run. Expected skips retain flavor/pinned-fixture conditions.
No source test is replaced with a fake successful inference result.

## Real-model evidence

| Area | What actually ran | What it establishes |
| --- | --- | --- |
| Cohere/Voxtral | [Pinned Q4 CPU speech receipts](../research/stt-cohere-voxtral-smoke-2026-10-20.json) | Arabic/English/silence, bounded production-session ownership, Voxtral monotonic windowed partials/exactly one final; no phone/GPU quality result |
| Fun-ASR MLT | [Pinned Q8 CPU production-owner receipt](../models/stt-native-smoke-2026-10-20.json) | Actual Arabic/English final captions; no corpus WER or device benchmark |
| Audar/OWSM | [Pinned decoder/projector and Q8 CPU receipts](../research/stt-modern-cpu-smoke-2026-10-20.json) | Arabic/English/Auto/silence and real no-speech protocol; OWSM Arabic errors remain explicit |
| QuickMT/SMaLL | [Initial production CT2 samples](../models/ct2-host-smoke-2026-10-20.json), plus [QuickMT AR→EN six-case receipt](../research/mt-quickmt-cpu-smoke-2026-10-20.json) | Both QuickMT and SMaLL directions ran; EOS correction, fixture subject/name loss and imperfect wording remain explicit. Official Python output parity/phone acceptance remain pending |
| Jisr | [Real source/native parity](../research/jisr-native-parity-2026-10-20.json) | 20 tokenizer vectors, FP32 21/21 output matches, INT8 15/21; no phone or corpus quality claim |
| Magpie | [C ABI/JNI receipt](../research/magpie-native-smoke-2026-10-20.json) | Corrected F16 generator/tokenizer, actual five-speaker inventory, Arabic/English PCM and lifecycle; no verified gender or listening score |
| OmniVoice | [C ABI/JNI receipt](../research/omnivoice-native-smoke-2026-10-20.json) | Actual pinned Q8 generator/codec Arabic/English PCM; design attributes are not fixed/verified-gender voices |
| MOSS Nano | [C ABI/JNI receipt](../research/moss-native-smoke-2026-10-20.json) | Actual text frontend/LM/codec output, Arabic/English finite bounded PCM, cancellation and callback errors |
| Oute 1.0 | [Real C ABI/JNI receipt](../research/oute-native-smoke-2026-10-20.json) | Correct combined generator/DAC1.0 and reference, same-handle Arabic/English reuse; actual prefill cancel returned in 109 ms with cancellation scheduled at 100 ms; host synthesis slower than real time |
| Chatterbox V3 | [Real C ABI/JNI and graph/Perth receipt](../research/chatterbox-native-smoke-2026-10-20.json), plus [native watermark parity](../research/perth-watermark-parity-2026-10-20.json) | Actual Arabic/English PCM; same-runtime graph transformation produced identical speech tokens/watermarked PCM. ORT versions differ on English tokens; no cross-runtime or device parity claim |
| EuroLLM 1.7B | [Pinned Q4 EN→AR/AR→EN CPU fixtures](../research/mt-eurollm-cpu-smoke-2026-10-20.json) | Six FLEURS sentences each direction, one pass; raw fixture chrF/WER diagnostics are not a corpus quality score or phone benchmark |
| Riva-Translate | [Corrected Q4 twelve-case/five-safety receipt](../research/mt-riva-translate-cpu-smoke-2026-10-20.json), plus [independently rendered publisher template](../research/mt-riva-publisher-prompt-2026-10-20.json) | Actual U+000A wrapper corrected source echo; both directions, bounded input/context errors, cancellation/no partial text and cancel reset ran. Meaning/terminology errors remain; no corpus/phone/GPU quality or speed claim |
| Ming five-checkpoint hosted routes | [Pinned inventories and API test scope](../ming-3.0.md#validation-and-remaining-evidence) | Two actual TTS API contracts plus ASR/audio hosted request routing; injected inference only, no local Android/CUDA run or Arabic claim |

Smoke model weights were independently size/SHA-256 verified and excluded from
Git. Receipts retain exact inputs/outputs/runtime pins and clarify host scope.
Large temporary payloads were removed after their required checks to make space
for subsequent models. The source-card component totals describe original
inputs; Jisr conversion profiles separately describe its executable packages.

## SDK and product boundaries

The model-free `:hearth-tts` API has no app, model weights, network client or
Android player dependency. Supertonic's native adapter is now extracted from
broad `common-jni`; it depends on the core and `:hearth-onnx-runtime`, which owns
one ONNX library per ABI. MOSS and Chatterbox share that ONNX package. Magpie,
OmniVoice and Oute have isolated optional native runtime packages. This is a
standalone dependency structure, not a claim that ONNX/model runtime binaries
are tiny. All eight release AARs passed the actual archive inventory check after removing
CMake-imported ONNX copies from Supertonic/common JNI packaging. ONNX is present
only in its shared runtime AAR. The following measured ZIP sizes exclude model
weights and are not runtime-memory figures:

| Release AAR | Bytes |
| --- | ---: |
| hearth-tts | 39,703 |
| hearth-onnx-runtime | 26,499,331 |
| hearth-tts-supertonic | 1,742,221 |
| hearth-tts-magpie | 2,534,513 |
| hearth-tts-omnivoice | 2,308,481 |
| hearth-tts-moss | 1,962,062 |
| hearth-tts-oute | 2,664,617 |
| hearth-tts-chatterbox | 3,354,243 |

Consumers combining native adapters can select their matching NDK C++ runtime
copy with `packaging.jniLibs.pickFirsts`; see [the SDK guide](../tts-sdk.md).
APK packaging remains a separate final gate.

The app uses one family table for runtime availability, inventory, readiness,
verification and engine creation. Legacy transport names and JNI package names
stay compatible. STT, MT and TTS remain independent, and installation does not
activate or reroute stages. New experimental voices stay behind More engines in
Basic unless already configured; Advanced exposes the complete engine list.
Strict gender requests fail for unverified families.

The final UI pass fixes two concrete persistence/provenance defects: browse
filters now queue DataStore writes in the input gesture instead of a cancelled
250ms composition debounce, and voice SAF imports record verified final private
files plus the separate original document URI. Valid files imported before a
later invalid ZIP member retain provenance; the actual import/cancel error is
preserved. Magpie binds its final tokenizer members, not its deleted range
payload. Derived model/Perth files bind their installed paths while retaining the
raw original source. Focused tests cover these behaviors and current-failure
versus old installed-record download presentation.

Original-source grants and private-file availability remain separate facts.
Startup audit checks bounded metadata/access on IO; native startup still verifies
full hashes. FOSS has no network permission or delegated downloads. TV policy
blocks local models at transfer, import, selection and execution, while retaining
explicit cloud setup and Android system TTS choices.

## Reproduction

Run from the repository root with the pinned dependencies/toolchains described
in the specialist runtime docs. No model download is part of Gradle or the SDK.

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
./gradlew :common-jni:externalNativeBuildDebug
CXX=clang++ bash common-jni/src/main/cpp/speech/tests/run_speech_tests.sh
CXX=clang++ bash common-jni/src/main/cpp/common/tests/run_common_utils_tests.sh
CXX=clang++ bash hearth-tts-supertonic/src/main/cpp/voice/tests/run_voice_tests.sh
CXX=clang++ bash scripts/voice/run-oute-host-tests.sh
python3 -m unittest discover -s scripts -p test_model_handoff.py
python3 -m unittest discover -s scripts/ming -p 'test_*.py'
python3 -m unittest discover -s scripts/voice -p 'test_*.py'
python3 scripts/validate-model-handoff.py --render
./gradlew :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
git diff --check
```

Use the current Homebrew LLVM on this macOS host for sanitized tests: the older
Apple sanitizer runtime previously hung before main. Production Android uses
NDK27 Clang. Neither compiler choice removes sanitizer/assertion coverage.
The final release goes to the owner-requested tagged GitHub draft; APKs are not
sent in chat. The draft validation assets record the executed gates and remaining owner-only checks.

# Omnilingual CTC native adapter: 2026-09-23

The experimental [Meta Omnilingual ASR CTC 300M](https://huggingface.co/facebook/omniASR-CTC-300M)
adapter uses the existing versioned `HearthSpeechBackend` C ABI. The C++
implementation lives in the shared speech backend, not in the Android UI. The
same interface loads in the macOS host harness and the Android NDK build. This
does **not** claim an iOS build or SDK yet.

The pinned [sherpa-onnx INT8 archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2)
is 292,313,120 bytes with SHA-256
`951b32409aade32bd525310bb39e9666773ba3fc611a39e817f620936d76c631`.
The verified package requires `model.int8.onnx` and `tokens.txt`. It is
downloaded, extracted and checked on the phone using the existing bounded
publisher installer. The Play download remains in `Downloads/Hearth/models`;
FOSS supports importing a local copy and makes no network request.

The CTC export has no source-language forcing parameter. Choosing English,
Arabic, Russian or Chinese **declares** the known source to the translation
router; it does not accelerate or condition recognition. Auto gives CC, but
translation still needs an identifiable source. The backend is utterance
windowed, not a persistent streaming decoder. The four visible languages are
an initial adapter/test subset, not the model's full published coverage.

## Evidence

- Mac host C++ build and `HearthSpeechBackend` smoke: publisher English WAV
  (3.85 s audio) loaded in 1.28 s, decoded in 0.22 s, and returned the
  correct “ask not what your country can do for you” sentence. A second load
  used cache; numbers are smoke observations, not a speed benchmark.
- The bundled FLEURS Arabic, Russian and Chinese clips decoded in 0.66, 0.45
  and 0.47 s respectively after warm load. They returned script-appropriate
  text but contained transcription errors, so this is not a quality win.
- Android arm64 runtime compiled from the pinned sherpa source, exports the
  new C ABI entry, and has 16 KiB ELF alignment. Exact staged library hash
  and source revision are in `common-jni/src/main/assets/licenses/speech`.
- Package, source-selection and native host tests pass. On the Samsung S22
  Ultra (SM-S908E, Android 16), the Play QA app installed the exact archive
  and ran all four built-in language sets through the production speech
  session. The exported private-device result is
  `/tmp/hearth-benchmarks-20260923-223428.json`; the data is not bundled.

| Language | Audio duration | Warm six-clip pass | Load + verify | Automatic error | Silence |
| --- | ---: | ---: | ---: | ---: | ---: |
| English | 43.82 s | 6.73 s | 1.21 s | WER 15.74%, CER 5.17% | 0/2 false positives |
| Russian | 47.18 s | 6.61 s | 1.13 s | WER 35.00%, CER 7.25% | 0/2 false positives |
| Arabic | 47.26 s | 6.70 s | 1.14 s | WER 31.51%, CER 9.12% | 0/2 false positives |
| Chinese | 49.14 s | 6.01 s | 1.16 s | CER 26.92% | 0/2 false positives |

The S22 Chinese reference separates characters with spaces, so a whitespace
word-level comparison yielded an uninformative WER of 100%. The app now hides
Chinese WER and displays CER. These are six short publisher clips per language
with peak normalization and no live capture, bilingual review or sustained
thermal test. Some recognized words are wrong; no quality superiority is
claimed. The warm pass time sums all six clips and does not measure delay from
spoken phrase to visible captions.

Future desktop/iOS reuse should package this same C ABI and isolate file,
thread and lifecycle adapters per platform. It should not clone recognition
logic into another platform UI.

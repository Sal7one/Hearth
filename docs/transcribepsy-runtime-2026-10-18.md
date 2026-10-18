# TranscribePsy: October 2026 speech support

Hearth now has a separate local speech backend for **QVAC TranscribePsy 250M EU-sub Q8_0**, officially released on **14 October 2026**. This adds a new model architecture and native runtime; it is not an older Whisper model with a recent conversion date.

The [publisher's release](https://qvac.tether.io/blog/on-device-speech-recognition-transcribepsy) identifies it as a 254M-parameter FastConformer TDT checkpoint distilled from Parakeet. The CPU checkpoint supports **Dutch, English, French, German, Italian, Polish, Portuguese and Spanish**. The publisher's 25-language model is a separate NeMo artifact, which Hearth does not present as an installable CPU model. Arabic is not included in this checkpoint.

## Reproducible artifacts

| Item | Exact identity |
| --- | --- |
| Native source | [qvac-transcribepsy](https://github.com/tether-ai-research/qvac-transcribepsy/tree/364ec36130dc87d2cd46e596598268a6e3313751), `364ec36130dc87d2cd46e596598268a6e3313751` |
| Model repository | [qvac/TranscribePsy-250M-EU-sub-gguf](https://huggingface.co/qvac/TranscribePsy-250M-EU-sub-gguf/tree/92460e0a8e7dc20f2fb126198bdd9eebb9ba2dc7) |
| Model revision | `92460e0a8e7dc20f2fb126198bdd9eebb9ba2dc7` |
| Filename | `transcribepsy-250m-eu-sub.gguf` |
| Download size | 304,879,584 bytes, approximately 291 MiB |
| SHA-256 | `a93ef324d38e410b5e49389db95d06364eddbb890b74a69f311e482d2fda6f4b` |
| First artifact commit | `16ac82cf9fc2fd4d69e0caf9ade1fff418461d68`, 13 October 2026 |
| Official model release | 14 October 2026 |
| Model / runtime license | Apache-2.0; bundled GGML notices retained separately |
| Hearth profile | `transcribepsy-250m-eu-sub-q8` |
| Hearth backend / library | `transcribepsy` / `libhearth_transcribepsy.so` |

The Hugging Face repository creation date, 30 September, predates its first published model commit. The catalog records the actual artifact date separately from the official model release date.

## Runtime behavior

The QVAC runtime and its bundled GGML are statically linked into a private plugin. The exported surface is only Hearth's existing speech C ABI. The app continues to call JNI through `com.sal7one.common_jni`; no third-party C++ objects or GGML symbols cross that boundary. The plugin uses CPU inference with the selected 1–8 thread budget, compatible baseline instruction sets and 16 KiB ELF page alignment on all four supported Android ABIs. GPU, OpenCL, Vulkan, Core ML and automatic backend loading are disabled in this build.

The model stays loaded between utterances. Hearth supplies normalized mono 16 kHz audio through its existing bounded utterance segmenter. The maximum utterance window and silence endpoint settings still apply. Completed utterances produce exactly one final event each. This is **utterance-windowed recognition**, with no partial captions; it does not claim cache-aware streaming.

This checkpoint has no exposed language detection result or decoder language hint. With Auto selected, the returned source language remains unknown. An explicit language choice declares the source for downstream routing; it does not force the recognizer. STT, text translation and shared TTS remain independently selectable.

Actual load and inference errors pass through the existing failure path. An incompatible CTC/diarization GGUF is rejected by the adapter instead of being accepted under this TDT profile. The verified importer enforces GGUF magic, expected bytes and SHA-256 without adding a ZIP conversion or weakening archive checks. FOSS retains its existing network policy.

## Verification

- The pinned QVAC source passed all four of its CPU-only host tests.
- All three speech plugins were rebuilt and staged for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` (12 artifacts). ELF checks confirmed private exports, permitted dependencies and 16 KiB alignment. Every artifact matches the current compilation source snapshot and its recorded size/SHA-256; the complete vendor checksum manifest also passed.
- Sanitized host suites passed: speech foundation 98 checks, Whisper 144 checks, TranscribePsy adapter 26 checks. The new adapter tests exercise the production ABI and native session, faking only the external model API to cover ownership, failed loading/inference, sticky failures, reset, independent repeated finals, thread settings and language declarations.
- Python package/source-snapshot tests passed ten cases, including the new single-GGUF profile and source-change invalidation.
- The exact downloaded model, verified against the pinned digest, ran through Hearth's production plugin, `SpeechNativeSession` and utterance segmentation on an Apple M4 Pro CPU with four threads. On the 11-second upstream JFK fixture it produced three final captions: “And so my fellow Americans ask.” / “not what your country can do for you.” / “Ask what you can do for your country.” Loading took 0.564 seconds and inference took 0.161 seconds in that single smoke run. This confirms integration, not phone latency or a representative accuracy benchmark.

`build-runtimes.sh all android ABI` builds all three speech plugins against one unchanged source snapshot. Publication now checks the snapshot captured during compilation; it cannot attach current source hashes to an older sibling library simply by running the staging script. Source pins, file hashes and notices are recorded in `common-jni/src/main/assets/licenses/speech/runtime-build.json`.

Phone speed, sustained thermal behavior, accents and noisy speech still require owner device testing. Publisher phone figures are useful research context; they are not measurements of Hearth's caption pipeline.

A second integration check synthesized one short train-arrival sentence with the
installed macOS voice for each supported language, converted it to mono 16 kHz
PCM and sent it through the same production plugin with **Auto** selected. Every
case produced one final and an unknown returned source, confirming that the
adapter does not invent a detected language. These clean, synthetic fixtures
are particularly small and are not representative accuracy data:

| Language | Observed result |
| --- | --- |
| English | “The next train arrives in five minutes.” — matched input |
| Dutch | “De volgende trein komt over vijf minuten.” — matched input |
| French | “Le prochain train arrive dans 5 minutes.” — number normalization |
| German | “Der nächste zukommt in fünf Minuten.” — incorrect fusion of “Zug kommt” |
| Italian | “Il prossimo treno arriva tra 5 minuti.” — number normalization |
| Polish | “Następny pociąg przyjedzie za 5 minut.” — number normalization |
| Portuguese | “O próximo combalho chega dentro de cinco minutos.” — incorrect “comboio” |
| Spanish | “El próximo trenco minutos.” — dropped words |

The recorded receipts live in ignored `build/modern-stt-audio/results.json`.
Errors in even these basic fixtures mean the integration must not be advertised
as a universal quality improvement over other recognizers. Users can retain
Whisper, Moonshine, Qwen, Phonon or Nemotron independently of this addition.

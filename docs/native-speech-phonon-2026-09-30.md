# Phonon 2 native adapter: 2026-09-30

[Fermion Phonon 2](https://huggingface.co/FermionResearch/Phonon-2) is an
English-only derivative of NVIDIA's parakeet-tdt-0.6b-v3 (TDT transducer,
CC-BY-4.0 weights). This adapter adds it as the `phonon` speech backend behind
the existing versioned `HearthSpeechBackend` C ABI. Like Moonshine, Qwen3-ASR
and Omnilingual CTC, the implementation lives in the shared sherpa-onnx speech
runtime (`libhearth_qwen.so`), exported as `hearth_phonon_backend_v1`; the
adapter selects sherpa's `nemo_transducer` model type, whose greedy decoder
auto-detects the TDT head from export metadata and decodes (token, duration)
pairs. The same interface loads in the macOS host harness and the Android NDK
build. This does **not** claim an iOS build or SDK.

## Weights provenance and conversion

Fermion publishes only a packed artifact: `phonon-2.bps.tar.zst`
(163,515,201 bytes, SHA-256
`98125795b6dda72f5c6eee9ba33d19815df65dcb18b50a357bf9f73c9935309e`) containing
the `fermion-five-value-parakeet-v1` container
(177,438,361 bytes, SHA-256
`4b6bfa3a12cc3c4e0a54f2ab3ec4ca7a842b09e5c7ecfc8e7ca0ac6cc8c11468`).
That format is not an ASR runtime input, and a model package is data — it can
never supply code. Therefore:

- `scripts/speech/phonon-2-export.py` (host-only, no phone dependency) verifies
  both pinned hashes, decodes the five-value/int6 records to a dense fp32 HF
  `ParakeetForTDT` state dict using Fermion's published integer codec, and
  re-exports the three ONNX graphs exactly as k2-fsa's pinned
  `scripts/nemo/parakeet-tdt-0.6b-v3` recipe does (encoder `x[B,128,T]`,
  decoder with two LSTM state tensors, joiner; int8 dynamic quantization;
  embedded `vocab_size`/`subsampling_factor`/`pred_rnn_layers`/`pred_hidden`/
  `feat_dim=128` metadata whose `url` marks the model as TDT).
- The exported directory packages with `make-package.py --profile phonon-2`
  into roles `model` (tokens.txt), `frontend` (joiner), `encoder`, `decoder`;
  Hearth verifies every file hash before the runtime ever loads.
- Fermion's own gates (bit-exact five-value expansion; 40/40 identical
  LibriSpeech transcripts packed vs dense) plus the converter's optional
  `--verify` transcript parity step are the accuracy chain. No quality
  superiority over existing engines is claimed here.

The conversion has been run on Fermion's real artifact. The pinned Hearth
archive (`phonon-2-hearth-int8.tar.bz2`, 339,918,748 bytes, SHA-256
`6b6cd464a3271f25f0bcbde0c12839516cd1e9b49f8292368da6a1931a2fa805`) carries
`tokens.txt` plus int8 encoder/decoder/joiner graphs, and the model transcribed
Fermion's bundled LibriSpeech sample exactly ("He could wait no longer.")
through `hearth_phonon_backend_v1` on the host: 0.90 s cold load, 0.068 s
inference for 2.09 s of audio. The archive must be attached to the `v0.23.0`
release for the catalog download button to resolve.

## Evidence

- The Android arm64 runtime was rebuilt from the same pinned sherpa revision
  (`210f340bcfdfd5b9ad6b24245e77a934d6c28f1b`) with the new export and
  re-verified by `scripts/speech/verify-android-runtimes.py`: exactly the four
  expected backend exports, 16 KiB ELF alignment, expected dependency set.
  Fresh binary hashes and adapter source hashes are recorded in
  `common-jni/src/main/assets/licenses/speech/runtime-build.json` and
  `common-jni/src/main/jniLibs/SHA256SUMS`. The rebuilt `libhearth_qwen.so` is
  20,560,352 bytes.
- Config validation, package role verification and the Python packager cover
  the phonon profile (Kotlin, C++ `speech_test`, `test_package.py`); all unit
  suites pass on this change.
- TDT decode behavior was confirmed against the pinned sherpa source
  (`DecodeOneTDT`: token/duration logit split, per-step duration advance,
  five-tokens-per-frame guard) rather than assumed.
- Host smoke through the production session: k2-fsa's pinned
  `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` package (Phonon 2's exact base
  architecture) packaged with `make-package.py --profile phonon-2` and decoded
  through `hearth_phonon_backend_v1` returned the correct sentence "Ask not
  what your country can do for you, ask what you can do for your country."
  from the publisher's 3.85 s English clip — 1.88 s cold load, 0.17 s
  inference, one final utterance. These are smoke observations, not a speed
  benchmark, and the fixture is NVIDIA's base model, not Fermion's five-value
  derivative.

## Not yet done (tracked in BACKLOG 0.23.0)

- Run `phonon-2-export.py` on Fermion's real five-value artifact end to end
  (the host smoke above used NVIDIA's dense base export) and record
  transcripts on the bundled LibriSpeech sample.
- Publish the converted package, pin it in `SpeechDownloads`, and only then
  surface a download button.
- Phone load/pass measurements on the Samsung S22, alongside the existing
  utterance-window engines.

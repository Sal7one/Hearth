# Compare local speech and translation models

The in-app benchmark runs the installed production adapters on the phone. Its
built-in quick set contains six aligned, publisher-provided sentences for each
supported language or direction. Speech checks add two silence inputs. The
current pack covers Arabic, English, Russian and Chinese, with translation
pairs Arabic↔English, Russian→Arabic/English, and Chinese→Arabic/English.
Audio and references come from the pinned FLEURS test split; see
[`app/src/main/assets/benchmark/LICENSE.txt`](../app/src/main/assets/benchmark/LICENSE.txt)
for attribution and CC BY 4.0 terms. These general test sentences do not model
Gulf dialect accuracy, every accent, or specialized captions.

Open **Compare local models**, choose Speech or Translation, select the source
language and (for translation) destination, then select the built-in set. It is
selected by default. The app applies the same six samples to each compatible
installed candidate that you check, in order. **Deselect all** keeps the list
empty until you choose a model. For speech it also runs 1-second and 2.5-second
silence checks; these are shown separately and are not included in reference
error rates. Choose **My recording or text** to run a single private sample with
optional corrected reference text. The language pickers offer the languages
advertised by the models installed on that phone; for a pair outside the
bundled quick set, the screen offers a direct switch to your own WAV/text.
This lets the S25 owner compare any **supported** language pair with their own
material, without claiming a public reference score for a language we did not
bundle. Saved results can be filtered to the current language pair or viewed
together, and the JSON export records exact model identity, phone, timing,
outputs and any reference scores. The suggested export name includes the
current date/time so repeated exports do not silently replace earlier runs.
Run the same sample on both phones and inspect the output as well as elapsed
time.

No network request, model download, microphone, or recording permission is used
by the benchmark. ML Kit runs only with packs already installed by the user in
the Play build. The speech corpus adds about 7.6 MB to the APK. Phone benchmark
results and user-selected custom recordings stay on that phone unless the user
exports the results JSON. Export includes recognized/translated text and the
public references, but never the custom audio bytes.

## What the measurements mean

- **Load + verify** includes integrity checking and native model creation. It
  uses a fresh model session but does not promise cold disk or OS caches.
- **First pass** is the first complete pass over all selected samples;
  **warm pass median** is the median of two more complete passes with weights retained.
- **Compute time** is accelerated replay, not microphone-to-caption latency.
  For speech the real-time factor divides the warm corpus compute time by total
  audio duration. Below 1 means the measured adapter processes that test set
  faster than its audio duration. It does not measure phrase-finality or live
  queue behavior.
- **WER and CER** compare normalized reference/output speech text. Chinese
  publisher references insert spaces between characters, while model output
  typically does not; the app therefore reports CER only for Chinese. Translation does not
  receive WER/CER because different valid wording would be counted as errors. For translation,
  **chrF++** is the mean sentence score over the six aligned samples. These are
  automatic text-similarity scores, not bilingual judgments of meaning,
  fluency, dialect, or safety.
- Expand a result to inspect each sample, its publisher reference, model output,
  timing, exact model/runtime identity and device. A fast empty or wrong answer
  is not a quality win. Errors remain failed results; they do not become zero
  scores or successful output.

The phone selects up to 12 compatible models, including installed Marian ONNX
language pairs, and runs them sequentially under
`LocalWorkGate`; Overlay and Traveler cannot load another local engine during
the run. Speech models receive the same decoded 16 kHz mono PCM. Streaming
adapters use 20 ms frames and their production options; batch adapters remain
identified as batch. Each model's weights stay loaded for its three measured
passes and are released before the next candidate.

## Run speech and translation samples on macOS

The host translation runner uses the same six sentence IDs, adapter prompt,
llama.cpp revision, context size, output cap and 20-second per-sample deadline
as the Android GGUF adapter. It writes the app's result-JSON shape so timings,
references, outputs, model hash and device metadata can be compared together.
It does not download weights or data. Point it at an already downloaded model:

```sh
bash scripts/translation/build-runtime.sh host
python3 scripts/benchmark/run_macos_translation.py \
  --model /path/to/Hy-MT2-1.8B.Q2_K.gguf --family hy-mt2 \
  --source ru --target ar --threads 2 --passes 3
```

The report is written to `build/benchmark-results/macos-translation.json` by
default. On macOS, the build script enables Metal when Xcode's `metal` shader
compiler is available; otherwise it clearly builds CPU mode. Pass
`--gpu-layers 0` to force CPU, or use the runner's default to select the
compiled backend.

Whisper can also run the same six FLEURS speech clips and silence checks using
the vendored whisper.cpp source and a compatible, already-downloaded
whisper.cpp `.bin` model:

```sh
bash scripts/benchmark/build_macos_whisper.sh
python3 scripts/benchmark/run_macos_whisper.py \
  --model /path/to/ggml-tiny-q5_1.bin --source ru --threads 8 --passes 3
```

Its report is written to `build/benchmark-results/macos-whisper.json`. The
runner uses forced language, batch greedy decoding, 16 kHz mono input, gain
normalization, and the app's batch Whisper thresholds. Silence clips stay in
the per-sample output and count as false positives if any text is emitted. It
is an equivalent host setup for comparison, not the Android NDK build: host
CPU, compiler and thermal behavior differ. The host benchmark tools currently cover
Whisper speech and GGUF translation; a separate sherpa C++ smoke tool exercises
the Omnilingual CTC backend on real clips. They do not run Nemotron, Qwen, Moonshine,
or ML Kit as benchmark candidates on macOS. Host results must name the Mac/backend and cannot predict
phone speed, battery or sustained thermal behavior. The phone remains the
authority for Android speed and memory.

Speech comparison audio uses deterministic per-clip peak normalization matching
the host harness: when a clip peaks below 0.50 full scale, it is boosted to a
0.90 peak; digital silence is unchanged. This avoids treating unusually quiet
publisher recordings as silence while keeping processed input equal across
installed speech models. The policy is recorded in each result's runtime details.

The built-in benchmark suite also has a deterministic pack generator at
[`scripts/benchmark/make_fleurs_pack.py`](../scripts/benchmark/make_fleurs_pack.py).
It downloads only when a developer explicitly runs it; weights and personal
recordings are never included.

The checked-in [Mac host smoke report](benchmark-host-smoke-2026-09-23.md)
records a Russian Whisper / Hy-MT2 baseline, including a translation timeout
and silence hallucinations. It is a diagnostic sample, not a general model
ranking.

The [phone smoke report](benchmark-phone-smoke-2026-09-23.md) records one
Samsung run of Moonshine Tiny, ML Kit and Hy-MT2 Q4. The substantial Q4
translation delay on that device is a result, not a universal model ranking.

## Limits

Six short public sentences are a quick comparison set, not a release-quality
language evaluation. The scores cannot reveal every dialect, name, number,
negation, code-switch, noise condition, long-session thermal effect, or
ASR-plus-translation interaction. Review the exact outputs and follow a
promising result with representative recordings and bilingual review. No
unmeasured model is called faster or more accurate based on model-card claims.

`BenchmarkAudio` bounds WAV parsing and decodes selected samples once for equal
inputs. Benchmark history is capped at 200 results. **Stop comparison**, leaving
the screen, or backgrounding the activity cancels cooperatively; a current
native operation may need to finish before model resources close. Native crashes
and process-level OOM cannot be converted into managed benchmark results.

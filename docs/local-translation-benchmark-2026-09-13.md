# Local translation measurements — 13 September 2026

Measured through Hearth 0.7.0 (17) on the connected Samsung SM-S908E (S22 Ultra), Android 16 / API 36, arm64. This is not the SM-S938B mentioned in older crash reports.

## What to choose from these results

All six ordinary HY quantizations completed every direction. ML Kit also completed
all four. TranslateGemma completed Chinese → English and hit the production
20-second deadline on the other three directions: 29 successful model/direction
runs, 87 completed inference passes, three failed runs. These were handled
timeouts, not observed native crashes or out-of-memory failures.

- **Fastest: ML Kit**, with 0.04–0.07-second repeated-text warm medians and
  0.26–0.91-second first calls. However, it changed Chinese future departure into
  “The train started for ten minutes,” used an incorrect Russian → Arabic time
  preposition, and omitted “in” in Arabic → English. English → Arabic also used
  an unnatural verb for departure. It is a speed option with visible quality
  limitations, not a safe quality winner for travel instructions.
- **Smallest working GGUF specialist: either HY Q4 at 1.13 GB.** Hy-MT2 Q4
  produced the correct Arabic dual “تذكرتين” where all HY-MT1.5 variants emitted
  “تذكرةين” in both Arabic-target fixtures. Hy-MT2 Q4 is the better starting
  candidate for Arabic wording in this small sample, but takes 5.62–8.73 seconds
  warmed and is not uniformly idiomatic. It has not earned a broad quality claim.
- **Fastest GGUF: HY-MT1.5 Q8 in three of four directions**, at the cost of a
  1.91 GB file and the Arabic spelling defect above. Its Q4 beat Q8 slightly in
  Arabic → English. Hy-MT2 Q8 was faster than its Q4 in all four directions here;
  that does not establish a universal hardware ranking.
- **Q6 offers no measured speed advantage:** it was slower than both Q4 and Q8
  within each HY family on all four fixtures. File size/quantization alone does
  not predict CPU speed. This run did not isolate kernel cost from output-token
  count, ordering or thermal effects.
- **TranslateGemma is unsuitable for live captions under these settings:** even
  its successful direction took a 19.55-second warm median. Keep it an optional
  experiment, not the live default, until a different deployment is measured.

The GGUF times establish that translation compute alone can be a major bottleneck
even when forced-language Nemotron is fast. A serial translator taking eight
seconds cannot keep up indefinitely if similarly sized finalized captions arrive
every four seconds. This is a scheduling implication, not a reproduction of every
reported missing-caption bug. Keep source captions visible and retain explicit
queue/error handling. The next useful native experiment is the smaller STQ model
below, followed by the same fixtures and then concurrent ASR+MT validation; do not
claim a speedup until it runs on Android.

## Method

Eight installed options, four translation directions, three passes per successful model/direction. Each direction uses the same short travel sentence for every model. These are deliberately synthetic public fixtures, not captured private conversations. The exact [inputs](benchmarks/2026-09-13-translation-cases.json) and [raw results including all outputs, errors, hashes and timings](benchmarks/2026-09-13-translation-results.json) are checked in.

GGUF runs use the production CPU backend, two threads, 2,048-token context, 384-token output cap and 20-second decode deadline. The model's context is cleared per translation. Each model opens once per pair, then translates the identical text three times. The warm value below is the median of the second and third passes; repeated-input/SDK caching can help and this is not varied-text sustained throughput. Models ran sequentially in catalogue order (TranslateGemma, HY-MT1.5 Q4/Q6/Q8, Hy-MT2 Q4/Q6/Q8, ML Kit), without concurrent ASR or active downloads. Ordering was not randomized and OS file cache was not controlled.

This isolates text translation from recognition and queueing. It does not measure microphone/playback-to-caption delay, concurrent ASR+MT performance, RAM peaks, battery, thermal sustainability or a multilingual accuracy score. Initial battery temperature was 28.3°C; the phone was USB connected. A short successful load/run is not a long-session guarantee.

## Warm translation time

Seconds per complete two-sentence fixture, excluding model loading. Lower is faster. A timeout is a failed run, not an empty translation or a synthetic 20-second timing. File sizes use decimal GB and describe GGUF downloads, not RAM.

| Option | Download | Russian → Arabic | Chinese → English | English → Arabic | Arabic → English |
|---|---:|---:|---:|---:|---:|
| ML Kit · installed packs | Language packs | 0.07 s | 0.07 s | 0.04 s | 0.06 s |
| HY-MT1.5 1.8B · Q4_K_M | 1.13 GB | 7.43 s | 5.12 s | 6.28 s | 5.88 s |
| HY-MT1.5 1.8B · Q6_K | 1.47 GB | 9.57 s | 6.44 s | 8.05 s | 8.53 s |
| HY-MT1.5 1.8B · Q8_0 | 1.91 GB | 7.10 s | 4.79 s | 6.03 s | 6.07 s |
| Hy-MT2 1.8B · Q4_K_M | 1.13 GB | 8.73 s | 5.62 s | 7.89 s | 8.21 s |
| Hy-MT2 1.8B · Q6_K | 1.47 GB | 10.89 s | 8.37 s | 11.13 s | 10.50 s |
| Hy-MT2 1.8B · Q8_0 | 1.91 GB | 8.14 s | 5.55 s | 7.51 s | 6.96 s |
| TranslateGemma 4B · Q4_K_M | 2.49 GB | Timeout | 19.55 s | Timeout | Timeout |

## First inference and loading

Fresh-session preparation includes integrity verification and model creation, but may benefit from the OS file cache. ML Kit creates its pair client lazily; its first inference includes that initialization. Ranges below span the four directions, not confidence intervals. Failed partial runs remain visible in the raw JSON.

| Option | Load + verify range | First successful inference range |
|---|---:|---:|
| ML Kit · installed packs | 0.07–0.08 s | 0.26–0.91 s |
| HY-MT1.5 1.8B · Q4_K_M | 2.07–2.55 s | 5.15–7.21 s |
| HY-MT1.5 1.8B · Q6_K | 2.49–9.48 s | 6.37–9.11 s |
| HY-MT1.5 1.8B · Q8_0 | 3.37–4.86 s | 4.75–7.06 s |
| Hy-MT2 1.8B · Q4_K_M | 1.97–2.83 s | 5.32–8.68 s |
| Hy-MT2 1.8B · Q6_K | 2.60–3.42 s | 8.37–11.13 s |
| Hy-MT2 1.8B · Q8_0 | 3.64–4.67 s | 5.57–8.39 s |
| TranslateGemma 4B · Q4_K_M | 4.05–5.58 s | 18.77–18.77 s |

## Exact outputs to review

The intended meaning in every case is: “The train leaves in ten minutes. I need two tickets to the airport.” Below is the first successful pass for each option; the JSON retains every pass and the verbatim native errors.

### ru-ar

Source: Поезд отправляется через десять минут. Мне нужны два билета до аэропорта.

- **ML Kit · installed packs**: يغادر القطار إلى عشر دقائق. أحتاج إلى تذكرتين للمطار.
- **HY-MT1.5 1.8B · Q4_K_M**: القطار سيغادر خلال عشر دقائق. أحتاج إلى تذكرةين للسفر إلى المطار.
- **HY-MT1.5 1.8B · Q6_K**: القطار سيغادر خلال عشر دقائق. أحتاج إلى تذكرةين للوصول إلى المطار.
- **HY-MT1.5 1.8B · Q8_0**: القطار سيغادر خلال عشر دقائق. أحتاج إلى تذكرةين للسفر إلى المطار.
- **Hy-MT2 1.8B · Q4_K_M**: القطار يغادر بعد عشر دقائق. أحتاج إلى تذكرتين للسفر إلى المطار.
- **Hy-MT2 1.8B · Q6_K**: القطار سيغادر خلال عشر دقائق. أحتاج إلى تذكرتين للوصول إلى المطار.
- **Hy-MT2 1.8B · Q8_0**: القطار سيغادر خلال عشر دقائق. أحتاج إلى تذكرتين للوصول إلى المطار.
- **TranslateGemma 4B · Q4_K_M**: No completed translation. **Run failed:** Local translation exceeded 20 seconds

### zh-en

Source: 火车十分钟后出发。我需要两张去机场的车票。

- **ML Kit · installed packs**: The train started for ten minutes. I need two tickets to the airport.
- **HY-MT1.5 1.8B · Q4_K_M**: The train will depart in ten minutes. I need two tickets to get to the airport.
- **HY-MT1.5 1.8B · Q6_K**: The train will leave in ten minutes. I need two tickets to get to the airport.
- **HY-MT1.5 1.8B · Q8_0**: The train will leave in ten minutes. I need two tickets to get to the airport.
- **Hy-MT2 1.8B · Q4_K_M**: The train leaves in ten minutes. I need two tickets to the airport.
- **Hy-MT2 1.8B · Q6_K**: The train will leave in ten minutes. I need two tickets to get to the airport.
- **Hy-MT2 1.8B · Q8_0**: The train will leave in ten minutes. I need two tickets to get to the airport.
- **TranslateGemma 4B · Q4_K_M**: The train departs in ten minutes. I need two tickets to the airport.

### en-ar

Source: The train leaves in ten minutes. I need two tickets to the airport.

- **ML Kit · installed packs**: يترك القطار في عشر دقائق. أحتاج إلى تذكرتين للمطار.
- **HY-MT1.5 1.8B · Q4_K_M**: القطار سيغادر خلال عشر دقائق. أحتاج إلى تذكرةين للوصول إلى المطار.
- **HY-MT1.5 1.8B · Q6_K**: القطار سيغادر بعد عشر دقائق. أحتاج إلى تذكرةين للوصول إلى المطار.
- **HY-MT1.5 1.8B · Q8_0**: القطار سيغادر بعد عشر دقائق. أحتاج إلى تذكرةين للوصول إلى المطار.
- **Hy-MT2 1.8B · Q4_K_M**: القطار سيغادر في عشر دقائق. أحتاج إلى تذكرتين للسفر إلى المطار.
- **Hy-MT2 1.8B · Q6_K**: القطار سيغادر بعد عشر دقائق. أحتاج إلى تذكرتين للوصول إلى المطار.
- **Hy-MT2 1.8B · Q8_0**: القطار سيغادر خلال عشر دقائق. أحتاج إلى تذكرتين للوصول إلى المطار.
- **TranslateGemma 4B · Q4_K_M**: No completed translation. **Run failed:** Local translation exceeded 20 seconds

### ar-en

Source: يغادر القطار بعد عشر دقائق. أحتاج إلى تذكرتين إلى المطار.

- **ML Kit · installed packs**: The train leaves ten minutes. I need two tickets to the airport.
- **HY-MT1.5 1.8B · Q4_K_M**: The train leaves after ten minutes. I need two tickets to get to the airport.
- **HY-MT1.5 1.8B · Q6_K**: The train leaves after ten minutes. I need two tickets to get to the airport.
- **HY-MT1.5 1.8B · Q8_0**: The train leaves in ten minutes. I need two tickets to get to the airport.
- **Hy-MT2 1.8B · Q4_K_M**: The train leaves after ten minutes. I need two tickets to get to the airport.
- **Hy-MT2 1.8B · Q6_K**: The train leaves in ten minutes. I need two tickets to get to the airport.
- **Hy-MT2 1.8B · Q8_0**: The train leaves in ten minutes. I need two tickets to get to the airport.
- **TranslateGemma 4B · Q4_K_M**: No completed translation. **Run failed:** Local translation exceeded 20 seconds

## Runtime and artifacts

Native llama.cpp revision: `64e9bceb2c3a856efed96feda784a50947049feb`. Bundled `libtransiber_translation.so` SHA-256: `4f953bd3f573c627ff0e003bcc9aa44c5160ee223035218ae2b8b4d413bd01d1`. NDK 27.0.12077973, CPU/two threads; no GPU/QNN/NPU benchmark. Full build provenance is in `common-jni/src/main/assets/licenses/translation/runtime-build.json`.

- HY-MT1.5 Q4/Q6/Q8: publisher revision `265b2e615a7dc9b06c435dc878829ad99a512ba2`; Tencent Hunyuan community terms. [Publisher models](https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF).
- Hy-MT2 Q4/Q6/Q8: publisher revision `a0c709d9fac510f2c807aa3af52872340dc37a4a`; Apache-2.0. [Publisher models](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF).
- TranslateGemma Q4: conversion revision `35a7486e128b19642cdc72d7b91b21ba388aaf42`; Gemma terms. [Official model and template](https://huggingface.co/google/translategemma-4b-it), [tested conversion](https://huggingface.co/mradermacher/translategemma-4b-it-GGUF). Our adapter exposes 35 normalized codes, fewer than the publisher's 55-language family coverage.
- ML Kit SDK 17.0.3 with English, Arabic, Russian and Chinese available. Google manages pack versions/storage; this export cannot pin individual weight revisions. Play build only; inference runs locally after explicit pack installation. [Google's documentation](https://developers.google.com/ml-kit/language/translation) describes casual/simple translation and the English pivot for non-English pairs.

Every GGUF import was checked against its catalogue's exact byte size and SHA-256; hashes are also recorded in the raw results. Download originals remain on the phone in `Downloads/Hearth/models`. No weights are committed. Legacy Marian is not included: this benchmark screen does not expose it and no matching prepared package was evaluated.

## Smaller candidate worth integrating next

[Hy-MT2 1.25-bit](https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF) advertises 440 MB and requires the specialized STQ kernel in [llama.cpp PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836). The PR was open and unmerged when checked on 2026-09-13. Hearth's pinned runtime does not include it. This is an integration candidate, not a measured winner or an already working import. Upstream Apple M4 Pro timings do not establish Android performance.

Do not confuse it with the older HY-MT1.5 2-bit SEQ artifact previously rejected for incompatible tensor offsets. The ordinary Q4/Q6/Q8 artifacts measured here are working independently of those special formats.

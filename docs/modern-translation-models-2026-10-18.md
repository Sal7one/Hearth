# Modern local translation: September–October 2026

Checked 2026-10-18. This adds a new translation architecture and a real
September/October model, rather than counting more quantizations of an older
checkpoint. STT, translation and TTS remain independent selections.

## Integrated: Index-Translate 2B Q4_K_M

The [publisher repository](https://github.com/bilibili/Index-Translate/blob/9aff74ca50c60873916f99269b10cd0d79237b4a/README.md)
dates the model release to **2026-09-30**, official GGUF quantizations to
**2026-10-03**, and the image-translation update to **2026-10-10**.
Hearth integrates **text translation only**. The model is based on dense
Qwen3.5 and uses Apache-2.0 terms. The image projector and the separate
speech/dubbing/document packages are not part of this integration.

- Catalog ID: `index-translate-2b-q4`.
- Official repository: `IndexTeam/Index-Translate-2B-GGUF`.
- Revision: `3a0eca6ce931d88d00614d427ab3ed8ff0743902`.
- File: `Index-Translate-2B.Q4_K_M.gguf`.
- Bytes: `1,312,164,352` (1,251.4 MiB).
- SHA-256: `044b313d29342bd3b2c77cbb64023ca0d209bd9b3247763f9d162767ef2d746a`.
- Availability: experimental; host inference checked, phone behavior unverified.

The [official GGUF card](https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF/blob/3a0eca6ce931d88d00614d427ab3ed8ff0743902/README.md)
recommends Q4_K_M and explicitly describes significant quality loss for Q2_K.
Hearth adds Q4 only. A 1.31 GB download is **not an ultra-light replacement**
for Marian or ML Kit, and download size is not running memory use.

### Native adapter

The pinned llama.cpp revision
`64e9bceb2c3a856efed96feda784a50947049feb` already implements `qwen35` CPU
graphs. The previous Hearth wrapper accepted Gemma 3 and Hunyuan only.
`TextModel` now accepts the dense `qwen35` architecture, keeping the persistent
two-thread CPU pool and the existing context/output/time limits.

Qwen3.5 mixes attention and recurrent state. Prefix reuse is **disabled for
this architecture even when `n_swa` is zero**; every request clears model
memory before evaluating its complete prompt. Removing a token suffix cannot
rewind a recurrent state safely. Hunyuan's validated prefix reuse and Gemma's
full-prefill behavior are preserved.

The wrapper uses the publisher's ChatML template with thinking disabled:
`<|im_start|>user\n`, literal prompt tokens, then
`<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n`.
The text uses the [official plain translation instruction](https://github.com/bilibili/Index-Translate/blob/9aff74ca50c60873916f99269b10cd0d79237b4a/inference/llm/translate.py).
Only fixed envelope markers are parsed as special tokens. Caption text
containing ChatML markers remains literal, and the validated assistant end
control token completes output without appearing in the translation.
Cancellation, native handle ownership, output limits and visible errors keep
their existing contracts. No JNI signature or prompt-protocol change is needed.

### Coverage and routing

`IndexTranslationLanguages` transcribes the complete inventory in Appendix C
of the [publisher report](https://github.com/bilibili/Index-Translate/blob/9aff74ca50c60873916f99269b10cd0d79237b4a/docs/Index_Translate_Series_Technical_Report.pdf):
**150 languages/varieties, 154 publisher identifiers**. This is publisher
coverage rather than a claim that Hearth has quality-tested every direction.
The official client's 32 Chinese language names are retained; other targets
use the report's English language/variety names in the same instruction.

Script and dialect choices remain distinct, including Serbian Latin/Cyrillic,
Uzbek Latin/Cyrillic, Urdu Arabic/Latin, Hindi/Latin Hindi, and Moroccan,
Egyptian, Najdi and Levantine Arabic. Caption target persistence, direction
validation, display names and RTL layout now support those selections.
Existing legacy preference values remain readable.

Unknown ASR language is not identified by script alone when multiple supported
languages share that script. Arabic/Persian/Urdu/dialects, Cyrillic languages,
and Han Chinese/Cantonese/Japanese remain ambiguous on the broad model and
retain original captions until the source is known. A language-specific route
can still use the existing bounded source-evidence recovery. Explicit ASR
metadata is preserved; Latin text is never assigned a language by this helper.

### Actual-weight host smoke

The full file was downloaded into ignored build storage and checked against
both the pinned size and SHA-256 before loading. A CPU-only executable used
the production wrapper and the exact pinned llama.cpp library, two threads,
zero GPU layers, on an **Apple M4 Pro / macOS 26.5**. These are small
compatibility checks, not a controlled speed or quality comparison. Some
Gradle work overlapped the run. They are not speech-to-caption latency or
Android measurements.

All 12 requests completed. Observed median translation time was **482 ms**
(444–746 ms); model preparation took **1.74 s**. English→Arabic kept negation,
two tickets, dollar amounts and a train time. Russian→Arabic,
Arabic→English, English→Russian, English→Chinese and English→Serbian Latin
also completed. Repeating the same English→Arabic caption after unrelated
requests produced exactly the same output.

Quality is not guaranteed: Chinese→Arabic kept the negative meaning but used
an incorrect first-person verb (`لا ألغي`) instead of the requested imperative.
Arabic→English changed singular reservation to plural. The model therefore
remains experimental and does not replace an existing default.
The process reported 2.46 GiB maximum resident size and 1.31 GiB peak memory
footprint on this host, before any concurrent STT/TTS Android measurement.
These two platform memory counters measure different things; neither supports
a claim that the complete phone pipeline fits under 1 GB.

[Raw cases, outputs, timings and environment](benchmarks/2026-10-18-index-translate-host.json).

Validation: production-wrapper ASAN/UBSAN host suite **2,025 checks passed**;
all common-jni JVM tests passed; Play QA script-target picker/persistence
regression passed. The root integration run records final native packaging,
full flavor tests and APK verification separately.

## Smaller candidates examined, with actual runtime boundaries

| Candidate | Primary evidence and outcome |
| --- | --- |
| [QVAC TranslatePsy-EuroNano](https://huggingface.co/qvac/TranslatePsy-EuroNano) | September announcement; 17M–43M Marian/Bergamot SSRU models. The Tiny INTGEMM model is 17,141,227 bytes per direction, plus vocabulary/shortlist, and covers English plus nine European languages. This is the strong ultra-light candidate, but it has no Arabic/Russian/Chinese coverage. It is not an Optimum Marian Transformer ONNX model. |
| [Publisher EuroNano toolkit](https://github.com/tether-ai-research/qvac-translatepsy-euro-nano/tree/2a51da3e05653c412199b6365b2b1dcf7c05b71f) | Public PyTorch SSRU architecture and direct NPZ loader make an ONNX export possible in principle. It still needs an SSRU state/zero-embedding bootstrap adapter, conversion parity, pinned redistribution artifacts and Android validation. Loading these files with Hearth's existing KV-cache Marian decoder would be wrong. The research clone is ignored. |
| [Hy-MT2 official 1.25-bit](https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF) | Real 461,860,800-byte file, but requires the STQ1_0 kernel. [Upstream PR 22836](https://github.com/ggml-org/llama.cpp/pull/22836) remained **open/unmerged** in the GitHub API check on 2026-10-18, head `1e411d8f5a1e23525fa3265dfb4bd76265465397`. It is not supported by Hearth's current pinned runtime. A custom kernel backport needs numerical/kernel tests across all four ABIs; a download button alone would not make it usable. |
| [QVAC TranslatePsy-AfriSLM 0.8B Q4](https://huggingface.co/qvac/TranslatePsy-AfriSLM-0.8B-Q4-GGUF) | Real 672,329,792-byte Qwen3.5 GGUF for English plus 19 Sub-Saharan languages. The new architecture adapter makes a bounded compatibility trial practical, but publisher-specific prompting, licensing notices and actual-weight quality checks remain required before catalog exposure. |
| [TranslateGemma](https://huggingface.co/google/translategemma-4b-it), MiLMMT | Existing supported options. TranslateGemma's smallest published family member remains 4B; no official 270M TranslateGemma was found. MiLMMT-46 1B v1.0 is August 2026, rather than a new September/October release. |

EuroNano artifact inventory was checked at revision
`3a5e1e4e2f7001ff5cfd79785bef03cf19281b73`. The ignored research directory
also contains the pinned Index source and report. No research model weights,
credentials or generated builds are tracked.

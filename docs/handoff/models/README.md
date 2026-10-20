# Hearth model implementation cards

Generated from the three pinned JSON inventories by `python3 scripts/validate-model-handoff.py --render`.

These are implementation records. Two GGUF translators have branch integrations pending real inference checks; the other new adapters remain planned. No S25 results are claimed. Component totals describe required source downloads; they can include conversion-only archives, rather than the final installed footprint.

## STT

### Cohere Transcribe Arabic 07-2026

ID: `planned-cohere-transcribe-arabic-07-2026`. Family: Cohere Transcribe.

Parameters: {"count": 2065804048, "basis": "HF official safetensors total", "exact": true, "scope": "encoder and autoregressive decoder"}.

Languages: ar, en.

Required source components: **1558.163 MB** (1,558,162,848 bytes).

Readiness: **upstream_runtime_available_hearth_adapter_missing**. Target: cohere_asr.

Streaming: utterance_finals.

License: [Apache-2.0](https://huggingface.co/CohereLabs/cohere-transcribe-arabic-07-2026/blob/c3e911b42149bf7a1e53d5cef9878aee87515a23/README.md).

Required work:

- Implement pinned transcribe.cpp HearthSpeechBackend adapter and architecture/variant checks.
- Build and attest CPU runtime; build separate optional arm64 Vulkan runtime with explicit backend validation.
- Cohere requires a spoken-language selection; do not accept Auto and silently select Arabic.
- Real Arabic/English parity and S25 latency/RAM tests pending.

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `cohere-transcribe-arabic-07-2026-Q4_K_M.gguf` | model | 1,558,162,848 | True | [Pinned file](https://huggingface.co/handy-computer/cohere-transcribe-arabic-07-2026-gguf/blob/11a54d6895f94cce78d0ece142b8e552e7822539/cohere-transcribe-arabic-07-2026-Q4_K_M.gguf) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
[
  {
    "metric": "WER",
    "value": 11.18,
    "unit": "percent",
    "language": "ar",
    "dataset": "FLEURS ar_eg full test, 428 utterances",
    "weights": "selected Q4_K_M GGUF",
    "publisher": "transcribe.cpp conversion maintainer",
    "normalizer": "Whisper BasicTextNormalizer",
    "source": "https://github.com/handy-computer/transcribe.cpp/blob/1dc22facfc931e2f8adb43924b0e38b9c46f83c7/docs/models/cohere-transcribe-arabic-07-2026.md",
    "caveat": "Egyptian-dialect FLEURS score; not a Gulf/Saudi score and not the same protocol as the six-set average."
  },
  {
    "metric": "WER",
    "value": 1.34,
    "unit": "percent",
    "language": "en",
    "dataset": "LibriSpeech test-clean",
    "weights": "selected Q4_K_M GGUF",
    "publisher": "transcribe.cpp conversion maintainer",
    "source": "https://github.com/handy-computer/transcribe.cpp/blob/1dc22facfc931e2f8adb43924b0e38b9c46f83c7/docs/models/cohere-transcribe-arabic-07-2026.md"
  }
]
```

Sources:

- [Source 1](https://huggingface.co/CohereLabs/cohere-transcribe-arabic-07-2026/blob/c3e911b42149bf7a1e53d5cef9878aee87515a23/README.md)
- [Source 2](https://github.com/handy-computer/transcribe.cpp/blob/1dc22facfc931e2f8adb43924b0e38b9c46f83c7/docs/models/cohere-transcribe-arabic-07-2026.md)
- [Source 3](https://huggingface.co/api/models/handy-computer/cohere-transcribe-arabic-07-2026-gguf/revision/11a54d6895f94cce78d0ece142b8e552e7822539?blobs=true)

### Audar-ASR V1 Flash

ID: `planned-audar-asr-v1-flash`. Family: Audar ASR / Qwen3-ASR adaptation.

Parameters: {"count": 782426112, "exact": true, "basis": "official card and safetensors metadata", "parts": {"decoder": 596049920, "audioEncoder": 186376192}}.

Languages: ar, en.

Required source components: **775.276 MB** (775,275,936 bytes).

Readiness: **upstream_multimodal_path_available_hearth_adapter_missing**. Target: audar_mtmd.

Streaming: utterance_finals_first; local_agreement_windowed_partials_later.

License: [AudarAI Open License v1.0](https://www.audarai.com/license/audarai-open-license-v1.0/).

Required work:

- Audar GGUF is a decoder plus separate BF16 mmproj; current sherpa QWEN3_ASR ONNX adapter cannot load either file.
- Implement dedicated mtmd ASR adapter and two-file transactional installer.
- Do not quantize the required BF16 audio encoder.
- Implement Qwen ASR scaffold parsing and genuine language None no-speech semantics.
- Validate Q4/Q8 Arabic and English against publisher BF16 protocol; no selected-quant WER is published.
- LocalAgreement redecoding is not cache-aware acoustic streaming.

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `Audar-ASR-V1-Flash-Q4_K_M.gguf` | model | 396,700,512 | True | [Pinned file](https://huggingface.co/audarai/Audar-ASR-V1-Flash/blob/54274d39245beba38f30048128e231d4176bd91d/Audar-ASR-V1-Flash-Q4_K_M.gguf) |
| `mmproj-Audar-ASR-V1-Flash.gguf` | encoder | 378,575,424 | True | [Pinned file](https://huggingface.co/audarai/Audar-ASR-V1-Flash/blob/54274d39245beba38f30048128e231d4176bd91d/mmproj-Audar-ASR-V1-Flash.gguf) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
[
  {
    "metric": "WER",
    "value": 32.04,
    "unit": "percent",
    "language": "ar",
    "dataset": "Open Universal Arabic ASR six full test sets: SADA, CV-18, MASC-clean, MASC-noisy, MGB-2, Casablanca",
    "weights": "BF16 official qwen-asr protocol, not selected GGUF quant",
    "publisher": "Audar model card reports independently reproduced leaderboard result",
    "referenceBaseline": {
      "model": "Qwen3-ASR-0.6B",
      "value": 42.19,
      "unit": "percent WER, same table"
    },
    "source": "https://huggingface.co/audarai/Audar-ASR-V1-Flash/blob/54274d39245beba38f30048128e231d4176bd91d/README.md",
    "caveat": "Use forced Arabic language scaffold, max_new_tokens=256, genuine no-speech handling. Do not attribute 32.04% to Q4 without testing."
  }
]
```

Sources:

- [Source 1](https://huggingface.co/audarai/Audar-ASR-V1-Flash/blob/54274d39245beba38f30048128e231d4176bd91d/README.md)
- [Source 2](https://github.com/AudarAI/Audar-ASR-V1)
- [Source 3](https://github.com/ggml-org/llama.cpp/blob/64e9bceb2c3a856efed96feda784a50947049feb/tools/mtmd/models/qwen3a.cpp)
- [Source 4](https://github.com/ggml-org/llama.cpp/blob/64e9bceb2c3a856efed96feda784a50947049feb/tools/mtmd/mtmd.h)
- [Source 5](https://huggingface.co/api/models/audarai/Audar-ASR-V1-Flash/revision/54274d39245beba38f30048128e231d4176bd91d?blobs=true)

### Voxtral Mini Realtime 2602

ID: `planned-voxtral-mini-4b-realtime-2602`. Family: Voxtral Realtime.

Parameters: {"count": 4429679360, "exact": true, "basis": "official HF safetensors stored total", "marketingLabel": "4B"}.

Languages: ar, en, fr, es, de, ru, zh, ja, it, pt, nl, hi, ko.

Required source components: **2830.494 MB** (2,830,493,984 bytes).

Readiness: **upstream_stream_api_available_hearth_adapter_missing**. Target: voxtral_realtime.

Streaming: windowed_partials_in_current_upstream_adapter; model_architecture_is_streaming.

License: [Apache-2.0](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602/blob/2769294da9567371363522aac9bbcfdd19447add/README.md).

Required work:

- Implement transcribe.cpp stream adapter and hard bounded utterance resets.
- Current pinned public extension says tentative partials reprocess accumulated audio; do not label cache-aware constant-memory streaming.
- Stream is Auto-language only; do not advertise forced Arabic/English support.
- Honor transcribe_was_truncated even if stream_finalize status is OK; never turn truncated output into successful complete final.
- Arabic Q4 parity, S25 memory and thermal performance pending.

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `Voxtral-Mini-4B-Realtime-2602-Q4_K_M.gguf` | model | 2,830,493,984 | True | [Pinned file](https://huggingface.co/handy-computer/Voxtral-Mini-4B-Realtime-2602-gguf/blob/7fd3d424fc558edca2c53808578b0541ef52a81a/Voxtral-Mini-4B-Realtime-2602-Q4_K_M.gguf) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
[
  {
    "metric": "WER",
    "value": 22.53,
    "unit": "percent",
    "language": "ar",
    "dataset": "FLEURS test",
    "weights": "official original weights at 480ms model delay, not selected GGUF Q4",
    "publisher": "Mistral official card",
    "source": "https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602/blob/2769294da9567371363522aac9bbcfdd19447add/README.md"
  },
  {
    "metric": "WER",
    "value": 2.08,
    "unit": "percent",
    "language": "en",
    "dataset": "LibriSpeech test-clean, 2620 utterances",
    "weights": "selected Q4_K_M GGUF, offline path at delay 6",
    "publisher": "transcribe.cpp conversion maintainer",
    "source": "https://github.com/handy-computer/transcribe.cpp/blob/1dc22facfc931e2f8adb43924b0e38b9c46f83c7/docs/models/voxtral-realtime.md",
    "caveat": "Pinned runtime offline default delay is 30; published quant table was measured at delay 6. 480ms is algorithmic delay, not measured phone end-to-end latency."
  }
]
```

Sources:

- [Source 1](https://huggingface.co/mistralai/Voxtral-Mini-4B-Realtime-2602/blob/2769294da9567371363522aac9bbcfdd19447add/README.md)
- [Source 2](https://github.com/handy-computer/transcribe.cpp/blob/1dc22facfc931e2f8adb43924b0e38b9c46f83c7/docs/models/voxtral-realtime.md)
- [Source 3](https://github.com/handy-computer/transcribe.cpp/blob/1dc22facfc931e2f8adb43924b0e38b9c46f83c7/include/transcribe/voxtral_realtime.h)
- [Source 4](https://huggingface.co/api/models/handy-computer/Voxtral-Mini-4B-Realtime-2602-gguf/revision/7fd3d424fc558edca2c53808578b0541ef52a81a?blobs=true)

### Fun-ASR MLT Nano 2512

ID: `planned-fun-asr-mlt-nano-2512`. Family: Fun-ASR multilingual Nano.

Parameters: {"count": 800000000, "exact": false, "basis": "official checkpoint card approximately 800M; do not report marketing estimate as an exact full-stack count"}.

Languages: ar, en, zh, yue, ja, ko, vi, id, th, ms, fil, hi, bg, hr, cs, da, nl, et, fi, el, hu, ga, lv, lt, mt, pl, pt, ro, sk, sl, sv.

Required source components: **891.271 MB** (891,271,232 bytes).

Readiness: **upstream_runtime_available_hearth_adapter_missing**. Target: funasr_mlt_nano.

Streaming: utterance_finals.

License: [FunASR Model Open Source License Agreement v1.1 (selected GGUF); upstream HF metadata currently says Apache-2.0](https://huggingface.co/handy-computer/Fun-ASR-MLT-Nano-2512-gguf/blob/5db7c5bcebbc70d6c372e825399c3019f6089af7/LICENSE).

Required work:

- Implement pinned transcribe.cpp adapter; require MLT variant, not Chinese/English/Japanese-only Nano sibling.
- Build/stage CPU and optional Vulkan runtimes with matching ABI and source notices.
- Keep ITN on for parity with the pinned quant benchmark.
- Resolve license mismatch between current upstream HF metadata and the GGUF conversion LICENSE before enabling redistribution/download.
- S25 speed, memory, Gulf quality and Q4 Arabic parity pending.

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `Fun-ASR-MLT-Nano-2512-Q8_0.gguf` | model | 891,271,232 | True | [Pinned file](https://huggingface.co/handy-computer/Fun-ASR-MLT-Nano-2512-gguf/blob/5db7c5bcebbc70d6c372e825399c3019f6089af7/Fun-ASR-MLT-Nano-2512-Q8_0.gguf) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
[
  {
    "metric": "WER",
    "value": 25.88,
    "unit": "percent",
    "language": "ar",
    "dataset": "FLEURS test",
    "weights": "selected Q8_0 GGUF, ITN on",
    "publisher": "transcribe.cpp conversion maintainer, pinned source snapshot",
    "source": "https://github.com/handy-computer/transcribe.cpp/blob/1dc22facfc931e2f8adb43924b0e38b9c46f83c7/docs/models/fun-asr-mlt-nano-2512.md"
  },
  {
    "metric": "WER",
    "value": 5.02,
    "unit": "percent",
    "language": "en",
    "dataset": "FLEURS test",
    "weights": "selected Q8_0 GGUF, ITN on",
    "publisher": "transcribe.cpp conversion maintainer, pinned source snapshot",
    "source": "https://github.com/handy-computer/transcribe.cpp/blob/1dc22facfc931e2f8adb43924b0e38b9c46f83c7/docs/models/fun-asr-mlt-nano-2512.md"
  }
]
```

Sources:

- [Source 1](https://huggingface.co/FunAudioLLM/Fun-ASR-MLT-Nano-2512/blob/2c088f535108689beff7e11cdc3d792af7d56552/README.md)
- [Source 2](https://github.com/handy-computer/transcribe.cpp/blob/1dc22facfc931e2f8adb43924b0e38b9c46f83c7/docs/models/fun-asr-mlt-nano-2512.md)
- [Source 3](https://huggingface.co/api/models/handy-computer/Fun-ASR-MLT-Nano-2512-gguf/revision/5db7c5bcebbc70d6c372e825399c3019f6089af7?blobs=true)

### OWSM v4 Base 102M

ID: `planned-owsm-v4-base-102m`. Family: OWSM v4.

Parameters: {"count": 102000000, "exact": false, "basis": "official ESPnet checkpoint name/paper; native package omits unused CTC head"}.

Languages: ar, en.

Required source components: **118.205 MB** (118,204,672 bytes).

Readiness: **upstream_runtime_available_android_adapter_missing**. Target: owsm.

Streaming: utterance_finals.

License: [CC-BY-4.0 (model weights); Apache-2.0 audio.cpp runtime is a separate license](https://huggingface.co/espnet/owsm_v4_base_102M/blob/1529407e6b49ffa6c335cbcff0926c72e16b2b63/README.md).

Required work:

- Implement narrow audio.cpp OWSM-only build and HearthSpeechBackend adapter; upstream framework default includes unrelated models and services.
- Disable native model manager/network clients, server, CLI/media helpers, examples, tests from shipping DSO.
- Map ar/en to ara/eng only at adapter boundary and retain ISO-639-1 output for MT routing.
- Use complete bounded utterance input: audio.cpp streaming mode emits tokens from a complete file, not incremental microphone audio.
- Validate Arabic Q8 quality, Android toolchain and GPU operation coverage before enabling GPU.

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `owsm-v4-base-102m-q8_0.gguf` | model | 118,204,672 | True | [Pinned file](https://huggingface.co/audio-cpp/audio.cpp-gguf/blob/661a224e9d9adbd49c01626f85e426f5f425d771/OWSM-GGUF/owsm-v4-base-102m-q8_0.gguf) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
[
  {
    "metric": "word_disagreement",
    "value": 1.21,
    "unit": "percent",
    "language": "en",
    "dataset": "conversion parity sample against Python, not human-reference WER",
    "weights": "Q8_0 native conversion",
    "publisher": "audio.cpp conversion maintainer",
    "source": "https://huggingface.co/audio-cpp/audio.cpp-gguf/blob/661a224e9d9adbd49c01626f85e426f5f425d771/OWSM-GGUF/README.md",
    "caveat": "No exact Arabic Q8 WER verified. Do not use 1.21% as speech recognition WER."
  }
]
```

Sources:

- [Source 1](https://huggingface.co/espnet/owsm_v4_base_102M/blob/1529407e6b49ffa6c335cbcff0926c72e16b2b63/exp/s2t_train_conv2d8_size384_e6_d6_mel128_raw_bpe50000/config.yaml)
- [Source 2](https://huggingface.co/audio-cpp/audio.cpp-gguf/blob/661a224e9d9adbd49c01626f85e426f5f425d771/OWSM-GGUF/README.md)
- [Source 3](https://github.com/0xShug0/audio.cpp/blob/673ade451c9cba9c893fb0e606872d9dc44f21e3/docs/models/owsm.md)
- [Source 4](https://github.com/0xShug0/audio.cpp/blob/673ade451c9cba9c893fb0e606872d9dc44f21e3/include/audiocpp.h)
- [Source 5](https://huggingface.co/api/models/audio-cpp/audio.cpp-gguf/revision/661a224e9d9adbd49c01626f85e426f5f425d771?blobs=true)

## TRANSLATION

### QuickMT English ↔ Arabic

ID: `planned-quickmt-en-ar-pair`. Family: quickmt.

Parameters: 185M EN→AR; approximately 200M AR→EN.

Languages: en, ar.

Required source components: **773.708 MB** (773,707,570 bytes).

Readiness: **not-implemented**. Target: CTranslate2 CPU + SentencePiece.

Streaming: Committed text segments; no token streaming currently exposed.

Directions: [{"source": "en", "target": "ar"}, {"source": "ar", "target": "en"}].

License: [CC-BY-4.0](https://huggingface.co/quickmt/quickmt-en-ar).

Required work:

- CTranslate2 Android runtime, JNI session and native provenance not implemented
- Dedicated multi-file import/download/install integration not implemented
- Arabic/English tokenizer and real-weight inference parity not tested

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `en-ar/model.bin` | weights | 360,843,109 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-en-ar/blob/37f2b37486c29b457971295d7711b5ac1869775d/model.bin) |
| `en-ar/config.json` | configuration | 223 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-en-ar/blob/37f2b37486c29b457971295d7711b5ac1869775d/config.json) |
| `en-ar/source_vocabulary.json` | tokenizer | 339,573 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-en-ar/blob/37f2b37486c29b457971295d7711b5ac1869775d/source_vocabulary.json) |
| `en-ar/target_vocabulary.json` | tokenizer | 731,023 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-en-ar/blob/37f2b37486c29b457971295d7711b5ac1869775d/target_vocabulary.json) |
| `en-ar/src.spm.model` | tokenizer | 589,684 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-en-ar/blob/37f2b37486c29b457971295d7711b5ac1869775d/src.spm.model) |
| `en-ar/tgt.spm.model` | tokenizer | 642,429 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-en-ar/blob/37f2b37486c29b457971295d7711b5ac1869775d/tgt.spm.model) |
| `ar-en/model.bin` | weights | 407,101,843 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-ar-en/blob/a1542c88d817d641a2acf2b8952bc892144882c0/model.bin) |
| `ar-en/config.json` | configuration | 223 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-ar-en/blob/a1542c88d817d641a2acf2b8952bc892144882c0/config.json) |
| `ar-en/source_vocabulary.json` | tokenizer | 1,205,230 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-ar-en/blob/a1542c88d817d641a2acf2b8952bc892144882c0/source_vocabulary.json) |
| `ar-en/target_vocabulary.json` | tokenizer | 546,670 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-ar-en/blob/a1542c88d817d641a2acf2b8952bc892144882c0/target_vocabulary.json) |
| `ar-en/src.spm.model` | tokenizer | 901,383 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-ar-en/blob/a1542c88d817d641a2acf2b8952bc892144882c0/src.spm.model) |
| `ar-en/tgt.spm.model` | tokenizer | 806,180 | True | [Pinned file](https://huggingface.co/quickmt/quickmt-ar-en/blob/a1542c88d817d641a2acf2b8952bc892144882c0/tgt.spm.model) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
{
  "benchmark": "FLORES-200 devtest (publisher prose; card header says flores101-devtest)",
  "en-ar": {
    "BLEU": 29.61,
    "chrF2": 61.59,
    "COMET22": 86.28
  },
  "ar-en": {
    "BLEU": 44.11,
    "chrF2": 67.96,
    "COMET22": 87.64
  },
  "scope": "Publisher scores; no S25 measurement. Do not relabel chrF as chrF++."
}
```

Sources:

- [Source 1](https://huggingface.co/quickmt/quickmt-en-ar)
- [Source 2](https://huggingface.co/quickmt/quickmt-ar-en)
- [Source 3](https://github.com/OpenNMT/CTranslate2)
- [Source 4](https://github.com/quickmt/quickmt)

### EuroLLM 1.7B Instruct

ID: `eurollm-17b-q4`. Family: eurollm.

Parameters: 1,656,850,432.

Languages: bg, hr, cs, da, nl, en, et, fi, fr, de, el, hu, ga, it, lv, lt, mt, pl, pt, ro, sk, sl, es, sv, ar, ca, zh, gl, hi, ja, ko, no, ru, tr, uk.

Required source components: **1045.157 MB** (1,045,156,992 bytes).

Readiness: **integrated-unverified**. Target: Existing llama.cpp CPU; explicit ARM64 Vulkan or Adreno OpenCL.

Streaming: Committed text segment in; complete translation out.

Directions: {"mode": "all-distinct-pairs", "languageCodesRef": "languages", "pairCount": 1190, "requiredHandoffPairs": [{"source": "en", "target": "ar"}, {"source": "ar", "target": "en"}], "qualityNote": "Coverage is not quality verification for every pair. See per-benchmark quality evidence."}.

License: [Apache-2.0](https://huggingface.co/utter-project/EuroLLM-1.7B-Instruct).

Required work:

- Real-weight English/Arabic parity and owner S25 benchmarks pending
- Per-model GPU placement is checked at load; speed is not established

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `EuroLLM-1.7B-Instruct.Q4_K_M.gguf` | weights | 1,045,156,992 | True | [Pinned file](https://huggingface.co/QuantFactory/EuroLLM-1.7B-Instruct-GGUF/blob/4126f034ddfe7fd39ca3378ae58d3b1d74065315/EuroLLM-1.7B-Instruct.Q4_K_M.gguf) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
{
  "benchmark": "Original-weight FLORES-200 COMET-22",
  "en-ar": 85.17,
  "ar-en": 86.34,
  "scope": "Not a Q4 parity result; not all multilingual directions evaluated here."
}
```

Sources:

- [Source 1](https://huggingface.co/utter-project/EuroLLM-1.7B-Instruct/blob/a25c7fa65fc2a644e6270b8940dbe295b51da681/README.md)
- [Source 2](https://huggingface.co/QuantFactory/EuroLLM-1.7B-Instruct-GGUF/tree/4126f034ddfe7fd39ca3378ae58d3b1d74065315)

### Jisr-MT 50M dialect pair

ID: `planned-jisr-50m-dialect-pair`. Family: jisr-mt.

Parameters: Approximately 48.2M per direction; publisher rounds to 49M.

Languages: en, ar, arz, ary, arq, aeb, ayl, apc, ajp, ajp-ps, ars, afb, apd, acq, acm.

Required source components: **390.802 MB** (390,802,192 bytes).

Readiness: **not-implemented**. Target: Exported ONNX/Marian adaptation or tested CTranslate2 converter + ByteLevel BPE tokenizer.

Streaming: Committed text segments only.

Directions: [{"source": "en", "target": "ar"}, {"source": "en", "target": "arz"}, {"source": "en", "target": "ary"}, {"source": "en", "target": "arq"}, {"source": "en", "target": "aeb"}, {"source": "en", "target": "ayl"}, {"source": "en", "target": "apc"}, {"source": "en", "target": "ajp"}, {"source": "en", "target": "ajp-ps"}, {"source": "en", "target": "ars"}, {"source": "en", "target": "afb"}, {"source": "en", "target": "apd"}, {"source": "en", "target": "acq"}, {"source": "en", "target": "acm"}, {"source": "ar", "target": "en"}, {"source": "arz", "target": "en"}, {"source": "ary", "target": "en"}, {"source": "arq", "target": "en"}, {"source": "aeb", "target": "en"}, {"source": "ayl", "target": "en"}, {"source": "apc", "target": "en"}, {"source": "ajp", "target": "en"}, {"source": "ajp-ps", "target": "en"}, {"source": "ars", "target": "en"}, {"source": "afb", "target": "en"}, {"source": "apd", "target": "en"}, {"source": "acq", "target": "en"}, {"source": "acm", "target": "en"}].

License: [Apache-2.0](https://huggingface.co/oddadmix/Jisr-MT-50M-AllDialects).

Required work:

- Original safetensors cannot be run by the existing OPUS ONNX adapter
- No verified exported inference package or its pins yet
- Hearth current Marian tokenizer does not implement this ByteLevel BPE pipeline
- Dialect labels must retain publisher mappings, not collapse Arabic codes
- MSA reverse-direction quality is degraded; broad Arabic quality claim prohibited

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `en-ar/model.safetensors` | weights | 192,741,468 | True | [Pinned file](https://huggingface.co/oddadmix/Jisr-MT-50M-AllDialects/blob/8803d9d4e3b53e96783968237aa32425eb83922e/model.safetensors) |
| `en-ar/config.json` | configuration | 890 | True | [Pinned file](https://huggingface.co/oddadmix/Jisr-MT-50M-AllDialects/blob/8803d9d4e3b53e96783968237aa32425eb83922e/config.json) |
| `en-ar/generation_config.json` | configuration | 989 | True | [Pinned file](https://huggingface.co/oddadmix/Jisr-MT-50M-AllDialects/blob/8803d9d4e3b53e96783968237aa32425eb83922e/generation_config.json) |
| `en-ar/tokenizer.json` | tokenizer | 2,670,172 | True | [Pinned file](https://huggingface.co/oddadmix/Jisr-MT-50M-AllDialects/blob/8803d9d4e3b53e96783968237aa32425eb83922e/tokenizer.json) |
| `en-ar/tokenizer_config.json` | tokenizer | 423 | True | [Pinned file](https://huggingface.co/oddadmix/Jisr-MT-50M-AllDialects/blob/8803d9d4e3b53e96783968237aa32425eb83922e/tokenizer_config.json) |
| `ar-en/model.safetensors` | weights | 192,718,120 | True | [Pinned file](https://huggingface.co/oddadmix/Jisr-MT-50M-Dialects2En/blob/2cfe89dcfaba280c81bc0b9da34e9dc096795405/model.safetensors) |
| `ar-en/config.json` | configuration | 928 | True | [Pinned file](https://huggingface.co/oddadmix/Jisr-MT-50M-Dialects2En/blob/2cfe89dcfaba280c81bc0b9da34e9dc096795405/config.json) |
| `ar-en/generation_config.json` | configuration | 989 | True | [Pinned file](https://huggingface.co/oddadmix/Jisr-MT-50M-Dialects2En/blob/2cfe89dcfaba280c81bc0b9da34e9dc096795405/generation_config.json) |
| `ar-en/tokenizer.json` | tokenizer | 2,667,790 | True | [Pinned file](https://huggingface.co/oddadmix/Jisr-MT-50M-Dialects2En/blob/2cfe89dcfaba280c81bc0b9da34e9dc096795405/tokenizer.json) |
| `ar-en/tokenizer_config.json` | tokenizer | 423 | True | [Pinned file](https://huggingface.co/oddadmix/Jisr-MT-50M-Dialects2En/blob/2cfe89dcfaba280c81bc0b9da34e9dc096795405/tokenizer_config.json) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
{
  "benchmark": "Alexandria publisher evaluation, beam4",
  "en-Saudi-chrF++": 35.44,
  "Saudi-en-chrF++": 47.53,
  "MSA-en-FLORES+": {
    "BLEU": 23.18,
    "chrF++": 50.65
  },
  "cautions": [
    "Reverse MSA loses 6.17 BLEU versus base",
    "Algerian, Bahraini, Iraqi lack external validation"
  ]
}
```

Sources:

- [Source 1](https://huggingface.co/oddadmix/Jisr-MT-50M-AllDialects/tree/8803d9d4e3b53e96783968237aa32425eb83922e)
- [Source 2](https://huggingface.co/oddadmix/Jisr-MT-50M-Dialects2En/tree/2cfe89dcfaba280c81bc0b9da34e9dc096795405)

### SMaLL-100 INT8

ID: `planned-small100-ct2-int8`. Family: small100.

Parameters: 332,735,488.

Languages: af, am, ar, ast, az, ba, be, bg, bn, br, bs, ca, ceb, cs, cy, da, de, el, en, es, et, fa, ff, fi, fr, fy, ga, gd, gl, gu, ha, he, hi, hr, ht, hu, hy, id, ig, ilo, is, it, ja, jv, ka, kk, km, kn, ko, lb, lg, ln, lo, lt, lv, mg, mk, ml, mn, mr, ms, my, ne, nl, no, ns, oc, or, pa, pl, ps, pt, ro, ru, sd, si, sk, sl, so, sq, sr, ss, su, sv, sw, ta, th, tl, tn, tr, uk, ur, uz, vi, wo, xh, yi, yo, zh, zu.

Required source components: **344.075 MB** (344,075,494 bytes).

Readiness: **not-implemented**. Target: CTranslate2 CPU int8_float32 + SentencePiece.

Streaming: Committed text segments only.

Directions: {"mode": "all-distinct-pairs", "languageCodesRef": "languages", "pairCount": 9900, "requiredHandoffPairs": [{"source": "en", "target": "ar"}, {"source": "ar", "target": "en"}], "qualityNote": "Coverage is not quality verification for every pair. See per-benchmark quality evidence."}.

License: [MIT](https://huggingface.co/solavr/small100-ctranslate2-int8/blob/3c5af8e17d5c4f35bda849b7ffceaa1dfc62eb84/LICENSE).

Required work:

- Shared CT2 Android/JNI runtime and package installation not implemented
- Target-language source prefix differs from standard M2M100; parity tests required
- Exact INT8 Arabic/English parity unverified

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `model.bin` | weights | 338,727,246 | True | [Pinned file](https://huggingface.co/solavr/small100-ctranslate2-int8/blob/3c5af8e17d5c4f35bda849b7ffceaa1dfc62eb84/model.bin) |
| `config.json` | configuration | 233 | True | [Pinned file](https://huggingface.co/solavr/small100-ctranslate2-int8/blob/3c5af8e17d5c4f35bda849b7ffceaa1dfc62eb84/config.json) |
| `sentencepiece.bpe.model` | tokenizer | 2,423,393 | True | [Pinned file](https://huggingface.co/solavr/small100-ctranslate2-int8/blob/3c5af8e17d5c4f35bda849b7ffceaa1dfc62eb84/sentencepiece.bpe.model) |
| `shared_vocabulary.json` | tokenizer | 2,924,622 | True | [Pinned file](https://huggingface.co/solavr/small100-ctranslate2-int8/blob/3c5af8e17d5c4f35bda849b7ffceaa1dfc62eb84/shared_vocabulary.json) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
{
  "benchmark": "Original floating-point model FLORES-101 spBLEU",
  "en-ar": 26.1,
  "ar-en": 30.8,
  "scope": "Not a score for this INT8 export; do not compare directly to COMET/chrF/other corpora."
}
```

Sources:

- [Source 1](https://huggingface.co/alirezamsh/small100)
- [Source 2](https://huggingface.co/solavr/small100-ctranslate2-int8/tree/3c5af8e17d5c4f35bda849b7ffceaa1dfc62eb84)
- [Source 3](https://aclanthology.org/2022.emnlp-main.571/)

### Riva-Translate 4B Instruct v2

ID: `riva-translate-4b-v2-q4`. Family: riva-translate.

Parameters: 4,179,704,832.

Languages: en, cs, da, de, el, es, fi, fr, hu, it, lt, lv, nl, no, pl, pt, ro, ru, sk, sv, zh, ja, hi, ko, et, sl, bg, uk, hr, ar, vi, tr, id, th.

Required source components: **2759.303 MB** (2,759,302,720 bytes).

Readiness: **integrated-unverified**. Target: Existing llama.cpp CPU; explicit ARM64 Vulkan or Adreno OpenCL.

Streaming: Committed text segment in; complete translation out.

Directions: [{"source": "en", "target": "cs"}, {"source": "en", "target": "da"}, {"source": "en", "target": "de"}, {"source": "en", "target": "el"}, {"source": "en", "target": "es"}, {"source": "en", "target": "fi"}, {"source": "en", "target": "fr"}, {"source": "en", "target": "hu"}, {"source": "en", "target": "it"}, {"source": "en", "target": "lt"}, {"source": "en", "target": "lv"}, {"source": "en", "target": "nl"}, {"source": "en", "target": "no"}, {"source": "en", "target": "pl"}, {"source": "en", "target": "pt"}, {"source": "en", "target": "ro"}, {"source": "en", "target": "ru"}, {"source": "en", "target": "sk"}, {"source": "en", "target": "sv"}, {"source": "en", "target": "zh"}, {"source": "en", "target": "ja"}, {"source": "en", "target": "hi"}, {"source": "en", "target": "ko"}, {"source": "en", "target": "et"}, {"source": "en", "target": "sl"}, {"source": "en", "target": "bg"}, {"source": "en", "target": "uk"}, {"source": "en", "target": "hr"}, {"source": "en", "target": "ar"}, {"source": "en", "target": "vi"}, {"source": "en", "target": "tr"}, {"source": "en", "target": "id"}, {"source": "en", "target": "th"}, {"source": "cs", "target": "en"}, {"source": "da", "target": "en"}, {"source": "de", "target": "en"}, {"source": "el", "target": "en"}, {"source": "es", "target": "en"}, {"source": "fi", "target": "en"}, {"source": "fr", "target": "en"}, {"source": "hu", "target": "en"}, {"source": "it", "target": "en"}, {"source": "lt", "target": "en"}, {"source": "lv", "target": "en"}, {"source": "nl", "target": "en"}, {"source": "no", "target": "en"}, {"source": "pl", "target": "en"}, {"source": "pt", "target": "en"}, {"source": "ro", "target": "en"}, {"source": "ru", "target": "en"}, {"source": "sk", "target": "en"}, {"source": "sv", "target": "en"}, {"source": "zh", "target": "en"}, {"source": "ja", "target": "en"}, {"source": "hi", "target": "en"}, {"source": "ko", "target": "en"}, {"source": "et", "target": "en"}, {"source": "sl", "target": "en"}, {"source": "bg", "target": "en"}, {"source": "uk", "target": "en"}, {"source": "hr", "target": "en"}, {"source": "ar", "target": "en"}, {"source": "vi", "target": "en"}, {"source": "tr", "target": "en"}, {"source": "id", "target": "en"}, {"source": "th", "target": "en"}].

License: [NVIDIA Open Model License](https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/).

Required work:

- Real-weight prompt/Arabic Q4 parity and owner S25 benchmarks pending
- No non-English↔non-English direct direction claimed
- Larger concurrent STT/MT/TTS memory and sustained thermal use require owner test

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `Riva-Translate-4B-Instruct-v2-Q4_K_M.gguf` | weights | 2,759,302,720 | True | [Pinned file](https://huggingface.co/liodon-ai/Riva-Translate-4B-Instruct-v2-imatrix-GGUF/blob/aa8cc2e72e2f97e07b021e4afbc612cff3bf9ae9/Riva-Translate-4B-Instruct-v2-Q4_K_M.gguf) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
{
  "benchmark": "Original-weight FLORES-101 sacreBLEU",
  "en-ar": 25.1,
  "ar-en": 40.7,
  "scope": "Not a Q4 parity or S25 speed result. WMT24++ Arabic is weaker."
}
```

Sources:

- [Source 1](https://huggingface.co/nvidia/Riva-Translate-4B-Instruct-v2/blob/040d958b128018ff0bed2542a7b51005e9ea563c/tokenizer_config.json)
- [Source 2](https://huggingface.co/nvidia/Riva-Translate-4B-Instruct-v2)
- [Source 3](https://huggingface.co/liodon-ai/Riva-Translate-4B-Instruct-v2-imatrix-GGUF/tree/aa8cc2e72e2f97e07b021e4afbc612cff3bf9ae9)

## TTS

### MagpieTTS v2607

ID: `magpie-v2607`. Family: MagpieTTS.

Parameters: {"generator": 364000000, "codec": "Additional frozen NanoCodec; not included in 364M headline"}.

Languages: ar, zh, en, fr, de, hi, it, ja, ko, pt, es, vi.

Required source components: **1880.843 MB** (1,880,843,296 bytes).

Readiness: **planned-not-executable**. Target: NeMo-Speech.cpp separate C ABI library, CPU first then Vulkan.

Streaming: {"upstream": "Incremental codec PCM callback; longform available", "hearth": false}.

Selected model and codec weights: **410.635 MB**. Required frontend/source inputs and installed footprint are separate.

requiredSourceBytes includes full .nemo archive for reproducible tokenizer extraction, not installed runtime footprint; previous 411MB headline excludes mandatory tokenizer assets.

Voice evidence: {"kind": "baked-speakers", "items": [{"id": "0", "label": "Aria", "gender": "unknown", "genderEvidence": null}, {"id": "1", "label": "Jason", "gender": "unknown", "genderEvidence": null}, {"id": "2", "label": "John", "gender": "unknown", "genderEvidence": null}, {"id": "3", "label": "Leo", "gender": "unknown", "genderEvidence": null}, {"id": "4", "label": "Sofia", "gender": "unknown", "genderEvidence": null}], "mappingEvidence": "Official v2607 speaker_map; inspect selected GGUF metadata because older converted files used v2602 order. No gender inferred from personal names.", "source": "https://huggingface.co/nvidia/magpie_tts_multilingual_357m/blob/446bff55e1fe3d3aa29c771574e7d2b0fa322990/README.md"}.

License: [NVIDIA Open Model License; runtime Apache-2.0; companion codec license must accompany distribution](https://huggingface.co/nvidia/magpie_tts_multilingual_357m#license).

Required work:

- No Hearth Magpie adapter or Android NeMo runtime is packaged.
- Tokenizer is outside the GGUF and must match the .nemo revision/profile.
- Selected published Q8 needs speaker_names verification: upstream documents an old v2602/v2607 mapping bug.
- No S25 speed, thermals, Arabic Q8 quality or male/female listening validation.

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `magpie_tts_multilingual_357m.v2607.q8_0.gguf` | generator | 331,812,192 | True | [Pinned file](https://huggingface.co/nvidia/magpie_tts_multilingual_357m/blob/446bff55e1fe3d3aa29c771574e7d2b0fa322990/magpie_tts_multilingual_357m.v2607.q8_0.gguf) |
| `nemo_nano_codec_22khz_1.89kbps_21.5fps.decoder.f16.gguf` | waveform-decoder | 78,823,104 | True | [Pinned file](https://huggingface.co/nvidia/nemo-nano-codec-22khz-1.89kbps-21.5fps/blob/fc00890b604aa2de298d2641ffc6c5f6caf8c4d7/nemo_nano_codec_22khz_1.89kbps_21.5fps.decoder.f16.gguf) |
| `magpie_tts_multilingual_357m.nemo` | tokenizer-source-archive | 1,470,208,000 | True | [Pinned file](https://huggingface.co/nvidia/magpie_tts_multilingual_357m/blob/446bff55e1fe3d3aa29c771574e7d2b0fa322990/magpie_tts_multilingual_357m.nemo) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
{
  "scope": "Publisher original v2607 release; not the selected Q8 generator/codec conversion and not Hearth/S25 measurements.",
  "Arabic": {
    "CERpercent": 1.62,
    "SSIM": 0.806,
    "dataset": "Internal per-language held-out Arabic test set"
  },
  "English": {
    "CERpercent": 0.37,
    "SSIM": 0.822,
    "dataset": "LibriTTS test-clean"
  },
  "metricNotes": "CER lower is better; SSIM speaker similarity higher is better. Arabic and English use different datasets; these are not cross-model rankings.",
  "source": "https://huggingface.co/nvidia/magpie_tts_multilingual_357m/blob/446bff55e1fe3d3aa29c771574e7d2b0fa322990/README.md#evaluation"
}
```

Sources:

- [Source 1](https://huggingface.co/nvidia/magpie_tts_multilingual_357m)
- [Source 2](https://huggingface.co/nvidia/nemo-nano-codec-22khz-1.89kbps-21.5fps)
- [Source 3](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/caa3f47c7c75fa2e9e6340272ebad57c1647dd44/docs/tts/models.md)

### OmniVoice

ID: `omnivoice`. Family: OmniVoice.

Parameters: {"generator": 612600000, "codec": "Additional HuBERT/DAC/RVQ stack"}.

Languages: ar, en.

Required source components: **945.285 MB** (945,284,608 bytes).

Readiness: **planned-not-executable**. Target: omnivoice.cpp isolated GGML backend; CPU then optional Vulkan.

Streaming: {"upstream": "Diffusion-style generation; verify chunk semantics, do not assume autoregressive token streaming", "hearth": false}.

Selected model and codec weights: **945.285 MB**. Required frontend/source inputs and installed footprint are separate.

Voice evidence: {"kind": "voice-design-or-user-reference", "items": [], "genderEvidence": null, "notes": "No verified stable named male/female preset inventory. Gender prompting does not establish speaker metadata. Arabic/English reference profiles require separate provenance and installation."}.

License: [CC-BY-NC-4.0 weights; codec upstream terms require independent audit; runtime MIT](https://huggingface.co/k2-fsa/OmniVoice#license).

Required work:

- No Hearth adapter.
- Noncommercial model must not enter commercial Arabic SDK default bundles.
- Codec terms need resolution before redistribution.
- Q8 main and codec Arabic parity and voice stability unverified.
- No pinned male/female Arabic reference profile supplied.

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `omnivoice-base-Q8_0.gguf` | generator | 656,395,008 | True | [Pinned file](https://huggingface.co/Serveurperso/OmniVoice-GGUF/blob/017094167b5c9ed565a5076ac9b3b93c5ecf5c73/omnivoice-base-Q8_0.gguf) |
| `omnivoice-tokenizer-Q8_0.gguf` | audio-codec | 288,889,600 | True | [Pinned file](https://huggingface.co/Serveurperso/OmniVoice-GGUF/blob/017094167b5c9ed565a5076ac9b3b93c5ecf5c73/omnivoice-tokenizer-Q8_0.gguf) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
{
  "ArabicWER": 1.392,
  "EnglishWER": 1.56,
  "dataset": "MiniMax-Multilingual-24",
  "scope": "Original model, not this Q8 conversion",
  "source": "https://arxiv.org/html/2604.00688v1#S4.T3"
}
```

Sources:

- [Source 1](https://huggingface.co/k2-fsa/OmniVoice)
- [Source 2](https://huggingface.co/Serveurperso/OmniVoice-GGUF)
- [Source 3](https://github.com/ServeurpersoCom/omnivoice.cpp)

### MOSS-TTS Nano 100M

ID: `moss-tts-nano`. Family: MOSS-TTS Nano.

Parameters: {"generator": 100000000, "codec": "Approximately22M additional parameters"}.

Languages: ar, en.

Required source components: **717.869 MB** (717,868,602 bytes).

Readiness: **planned-not-executable**. Target: ONNX Runtime CPU; native SentencePiece/text frontend and cached multi-graph decoder.

Streaming: {"upstream": "KV-cached global/local decoding plus stateful codec", "hearth": false, "nativeOutput": "48000Hz stereo; Hearth SDK currently accepts mono, requiring explicit tested downmix or an API extension"}.

Voice evidence: {"kind": "user-reference-or-unconditioned", "items": [], "genderEvidence": null, "notes": "No packaged verified male/female Arabic/English styles. Need authored reference recordings/profiles and QA."}.

License: [Apache-2.0; verify all codec and reference-profile terms](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX).

Required work:

- No Hearth adapter; upstream Android smoke example consumes pretokenized prompts.
- Implement SentencePiece and exact special tokens/prompt grammar.
- Implement KV ownership, local generation sampler, decode-step termination and stereo-to-mono conversion.
- No verified Nano-specific Arabic quality score.
- Official ONNX assets are much larger than the 100M parameter headline; do not claim PyTorch size as Android download.

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `moss_tts_prefill.onnx` | generator | 283,305 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/blob/f52645cb467506d8e18e746ddd59482685b74e58/moss_tts_prefill.onnx) |
| `moss_tts_decode_step.onnx` | generator | 291,483 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/blob/f52645cb467506d8e18e746ddd59482685b74e58/moss_tts_decode_step.onnx) |
| `moss_tts_local_decoder.onnx` | generator | 49,231 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/blob/f52645cb467506d8e18e746ddd59482685b74e58/moss_tts_local_decoder.onnx) |
| `moss_tts_local_cached_step.onnx` | generator | 53,685 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/blob/f52645cb467506d8e18e746ddd59482685b74e58/moss_tts_local_cached_step.onnx) |
| `moss_tts_local_fixed_sampled_frame.onnx` | generator | 471,262 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/blob/f52645cb467506d8e18e746ddd59482685b74e58/moss_tts_local_fixed_sampled_frame.onnx) |
| `moss_tts_global_shared.data` | generator | 440,813,568 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/blob/f52645cb467506d8e18e746ddd59482685b74e58/moss_tts_global_shared.data) |
| `moss_tts_local_shared.data` | generator | 229,678,080 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/blob/f52645cb467506d8e18e746ddd59482685b74e58/moss_tts_local_shared.data) |
| `tokenizer.model` | text-tokenizer | 470,897 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/blob/f52645cb467506d8e18e746ddd59482685b74e58/tokenizer.model) |
| `tts_browser_onnx_meta.json` | model-metadata | 4,487 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/blob/f52645cb467506d8e18e746ddd59482685b74e58/tts_browser_onnx_meta.json) |
| `browser_poc_manifest.json` | model-metadata | 503,354 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX/blob/f52645cb467506d8e18e746ddd59482685b74e58/browser_poc_manifest.json) |
| `moss_audio_tokenizer_decode_full.onnx` | waveform-decoder | 681,902 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/blob/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae/moss_audio_tokenizer_decode_full.onnx) |
| `moss_audio_tokenizer_decode_step.onnx` | waveform-decoder | 351,400 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/blob/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae/moss_audio_tokenizer_decode_step.onnx) |
| `moss_audio_tokenizer_decode_shared.data` | waveform-decoder | 44,198,912 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/blob/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae/moss_audio_tokenizer_decode_shared.data) |
| `codec_browser_onnx_meta.json` | codec-metadata | 17,036 | True | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/blob/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae/codec_browser_onnx_meta.json) |
| `moss_audio_tokenizer_encode.onnx` | reference-audio-encoder | 815,775 | False | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/blob/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae/moss_audio_tokenizer_encode.onnx) |
| `moss_audio_tokenizer_encode.data` | reference-audio-encoder | 44,507,136 | False | [Pinned file](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX/blob/ceff0d0749bfb3fa2d61149794ec6feef0d1e1ae/moss_audio_tokenizer_encode.data) |

Checksums and complete variant information are in the corresponding JSON inventory.

Exact selected-package Arabic quality is not established in this record. Verify it before making quality claims.

Sources:

- [Source 1](https://github.com/OpenMOSS/MOSS-TTS-Nano)
- [Source 2](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX)
- [Source 3](https://huggingface.co/OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX)

### Chatterbox Multilingual V3

ID: `chatterbox-multilingual-v3`. Family: Chatterbox.

Parameters: {"backbone": 500000000, "total": "Additional speech encoder and conditional decoder; 500M is not full-stack count"}.

Languages: en, ar, da, de, el, es, fi, fr, he, hi, it, ko, ms, nl, no, pl, pt, ru, sv, sw, tr, ja, zh.

Required source components: **1556.285 MB** (1,556,285,252 bytes).

Readiness: **planned-not-executable**. Target: ONNX Runtime CPU with com.microsoft contrib ops and MatMulNBits support.

Streaming: {"upstream": "KV-cached LM then conditional waveform decoder; exact streaming behavior to validate", "hearth": false, "nativeOutput": "24000Hz mono"}.

Voice evidence: {"kind": "default-reference-or-user-reference", "items": [{"id": "default", "label": "Bundled default reference", "gender": "unknown", "genderEvidence": null}], "notes": "Default reference gender is unverified. Pin user/imported male/female reference metadata separately."}.

License: [MIT model/export; upstream watermarking policy and reference rights must be preserved](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/blob/19b94afe41de738624b2f3afc75b4cd56221fbeb/LICENSE).

Required work:

- No Hearth V3 adapter.
- Use NFKD full-case tokenizer; do not lowercase.
- Trim reference to6s; maintain16-head/64dim caches; drop final960audio samples per upstream V3 contract.
- Export omits Perth watermarking used upstream; integration needs explicit output provenance/watermark decision and upstream policy check.
- Chinese/Japanese frontends missing in this export; metadata language coverage is upstream and does not mean all native text frontends are implemented.
- Q4 Arabic parity, live latency and gender inventory unverified.

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `onnx/speech_encoder.onnx` | onnx-model | 1,204,600 | True | [Pinned file](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/blob/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/speech_encoder.onnx) |
| `onnx/speech_encoder.onnx_data` | onnx-model | 591,274,880 | True | [Pinned file](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/blob/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/speech_encoder.onnx_data) |
| `onnx/embed_tokens.onnx` | onnx-model | 17,616 | True | [Pinned file](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/blob/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/embed_tokens.onnx) |
| `onnx/embed_tokens.onnx_data` | onnx-model | 68,808,704 | True | [Pinned file](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/blob/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/embed_tokens.onnx_data) |
| `onnx/language_model_q4.onnx` | onnx-model | 228,633 | True | [Pinned file](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/blob/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/language_model_q4.onnx) |
| `onnx/language_model_q4.onnx_data` | onnx-model | 353,621,248 | True | [Pinned file](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/blob/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/language_model_q4.onnx_data) |
| `onnx/conditional_decoder.onnx` | onnx-model | 6,371,670 | True | [Pinned file](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/blob/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/conditional_decoder.onnx) |
| `onnx/conditional_decoder.onnx_data` | onnx-model | 533,970,816 | True | [Pinned file](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/blob/19b94afe41de738624b2f3afc75b4cd56221fbeb/onnx/conditional_decoder.onnx_data) |
| `tokenizer.json` | text-tokenizer | 72,765 | True | [Pinned file](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/blob/19b94afe41de738624b2f3afc75b4cd56221fbeb/tokenizer.json) |
| `default_voice.wav` | reference-audio | 714,320 | True | [Pinned file](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX/blob/19b94afe41de738624b2f3afc75b4cd56221fbeb/default_voice.wav) |

Checksums and complete variant information are in the corresponding JSON inventory.

Evaluation evidence:

```json
{
  "scope": "Export parity tested on limited non-Arabic examples; do not label as Arabic quality verified."
}
```

Sources:

- [Source 1](https://github.com/resemble-ai/chatterbox)
- [Source 2](https://huggingface.co/Masterx/chatterbox-multilingual-v3-ONNX)

### OuteTTS1.0 1B

ID: `outetts-1.0-1b`. Family: OuteTTS.

Parameters: {"headline": 1000000000, "storedGeneratorApprox": 1248000000, "codec": "Additional DAC waveform codec"}.

Languages: ar, en.

Required source components: **1113.901 MB** (1,113,900,979 bytes).

Readiness: **planned-not-executable**. Target: Isolated llama.cpp generator plus separately converted IBM DAC2-codebook waveform decoder.

Streaming: {"upstream": "Chunked speech generation; reference profile occupies context", "hearth": false, "nativeOutput": "24000Hz mono"}.

Selected model and codec weights: **1113.900 MB**. Required frontend/source inputs and installed footprint are separate.

Voice evidence: {"kind": "default-or-user-speaker-profiles", "items": [{"id": "EN-FEMALE-1-NEUTRAL", "label": "English female neutral (upstream example)", "gender": "female", "genderEvidence": "Official Oute model usage names this profile explicitly; profile bytes and cross-lingual Arabic QA not pinned here."}], "notes": "No verified male or native Arabic reference profile currently bundled. Do not expose a missing profile as installed."}.

License: [CC-BY-NC-SA-4.0 continued training/components; Llama3.2 Community License; DAC CDLA-Permissive2.0](https://huggingface.co/OuteAI/Llama-OuteTTS-1.0-1B#license-information).

Required work:

- No Hearth adapter.
- Source codec weights need executable Android conversion with pinned outputs and parity tests.
- Native port must implement1.0 prompt timing/energy/pitch metadata; do not use0.3 WavTokenizer.
- Pin and verify speaker-profile bytes and provenance; reference uses context, reducing available generation duration.
- Noncommercial/share-alike/Llama terms exclude this from an unrestricted commercial default SDK bundle.
- No verified Arabic Q4 quality or S25 benchmark.

| Component | Role | Bytes | Required | Source |
| --- | --- | ---: | --- | --- |
| `Llama-OuteTTS-1.0-1B-Q4_K_M.gguf` | generator | 818,168,800 | True | [Pinned file](https://huggingface.co/OuteAI/Llama-OuteTTS-1.0-1B-GGUF/blob/f0a131bce9ca77922d38da5af18db11d29b6b768/Llama-OuteTTS-1.0-1B-Q4_K_M.gguf) |
| `weights_24khz_1.5kbps_v1.0.pth` | codec-source-weights | 295,731,578 | True | [Pinned file](https://huggingface.co/ibm-research/DAC.speech.v1.0/blob/1ea7f64cd0678415e2d8c32d67b190722cb9b149/weights_24khz_1.5kbps_v1.0.pth) |
| `config.json` | codec-metadata | 601 | True | [Pinned file](https://huggingface.co/ibm-research/DAC.speech.v1.0/blob/1ea7f64cd0678415e2d8c32d67b190722cb9b149/config.json) |

Checksums and complete variant information are in the corresponding JSON inventory.

Exact selected-package Arabic quality is not established in this record. Verify it before making quality claims.

Sources:

- [Source 1](https://huggingface.co/OuteAI/Llama-OuteTTS-1.0-1B)
- [Source 2](https://huggingface.co/OuteAI/Llama-OuteTTS-1.0-1B-GGUF)
- [Source 3](https://huggingface.co/ibm-research/DAC.speech.v1.0)
- [Source 4](https://github.com/edwko/OuteTTS)

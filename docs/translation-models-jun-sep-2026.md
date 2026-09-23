# Speech and translation model check, June–September 2026

Checked 2026-09-23. The priority is a smaller Arabic/Russian/Chinese-capable
translator for live captions. A model card or Mac benchmark is not an Android
quality claim.

| Candidate | Evidence | Decision |
| --- | --- | --- |
| [Xiaomi MiLMMT-46-1B-v1.0](https://huggingface.co/xiaomi-research/MiLMMT-46-1B-v1.0) | August 2026; 1B parameters, 46 published languages including Arabic, Russian and Chinese. Gemma terms. [Q4/Q5 GGUFs](https://huggingface.co/mradermacher/MiLMMT-46-1B-v1.0-GGUF) are 806/851 MB. | **Integrated experimentally.** Pinned download, quant choice, model card, language coverage, publisher-format raw prompt and native route. Q4 ran on Mac and Samsung S22; Q5 and sustained live quality remain unverified. The picker currently groups Simplified/Traditional Chinese into one `zh` code (45 selectable codes). |
| [QVAC TranslatePsy-EuroNano](https://huggingface.co/qvac/TranslatePsy-EuroNano) | September 2026; English ↔ nine European languages, 17–43M parameters, Apache-2.0. Publisher reports 161–819 ms/sentence for the shown Android routes and variants; European→European needs an English pivot. | Best next ultra-light experiment for European languages. Its Marian/Bergamot INTGEMM packs need a **new runtime adapter**; current ONNX/GGUF loaders cannot use them. No Arabic, Russian or Chinese. Vendor timing is not a Hearth measurement. |
| [QVAC TranslatePsy-AfriNano](https://huggingface.co/qvac/TranslatePsy-AfriNano) | September 2026; English ↔ eight African languages, 17–43M, Bergamot INTGEMM. | Research only for now; no Arabic/Russian/Chinese and same runtime work. |
| [QVAC TranslatePsy-AfriSLM 0.8B Q4](https://huggingface.co/qvac/TranslatePsy-AfriSLM-0.8B-Q4-GGUF) | September 2026; 19 Sub-Saharan African languages plus English, 672 MB Q4 GGUF, Apache-2.0, Qwen3.5 architecture. | Valuable regional candidate, but **not** a substitute for Arabic or the EuroNano Bergamot route. Our pinned llama.cpp and its chat template need a host/phone compatibility check before a one-tap card. |
| [LocalSubs EN→Traditional Chinese 0.6B](https://huggingface.co/Aiden1020/LocalSubs-EN-ZH-TW-0.6B) | July 2026; subtitle-focused Qwen3/LMT derivative. | A useful narrow caption candidate, not a general translator or Arabic solution. Its own prompt and Android behavior require testing. |
| [LMT-60 0.6B](https://huggingface.co/NiuTrans/LMT-60-0.6B) | Weights date to 2025; [ACL paper](https://aclanthology.org/2026.acl-long.1153/) appeared in 2026. English/Chinese-hub directions. | Tested Q4 on Mac: fast but serious meaning errors. **Not** added to the public catalog. |
| [North Small Translate 1.0](https://huggingface.co/CohereLabs/North-Small-Translate-1.0) | September 2026; 218B total parameters. | Server reference, not an on-phone model. |

## MiLMMT implementation and host check

The [publisher example](https://huggingface.co/xiaomi-research/MiLMMT-46-1B-v1.0)
uses a raw prompt, `Translate this from <source> to <target>:\n<source>:
<text>\n<target>:`, tokenized with `add_special_tokens=False`. The adapter
selects raw mode explicitly and rejects it for non-Gemma-3 architectures. It
does **not** reuse the TranslateGemma chat envelope. [Publisher paper](https://arxiv.org/abs/2608.10812).

The tested GGUF is pinned to revision
`34df5efbe6592773ec168cc7b307728c08623472`, SHA-256
`74d38ba75108d455326e9deeaf9ab01bb266dfa665eae9c4aa84e84485d4fdf9`.
The Mac smoke used the pinned Android C++ translation runtime, CPU, two threads,
Apple M2 Pro, six FLEURS source/reference pairs per direction and one pass.
These are text-only compute times, **not** spoken-phrase-to-caption delay.

| Direction | Median sentence | Six-sentence compute | Host peak RSS | chrF++ vs one reference |
| --- | ---: | ---: | ---: | ---: |
| English→Arabic | 1.12 s | 6.22 s | 1,340 MiB | 0.398 |
| Russian→English | 1.04 s | 5.78 s | 1,341 MiB | 0.678 |
| Chinese→English | 0.97 s | 5.49 s | 1,338 MiB | 0.627 |

Meaning is mixed: English→Arabic rendered “Atlantic Ocean” as “Atlantic Gulf”
and mistranslated dragonflies/mayflies; Russian→English rendered those insects
as cockroaches/bedbugs. A separate three-line smoke preserved negation and
“two tickets” in English/Chinese→Arabic and completed in ~0.5–0.8 s per line
on the Mac. This supports experimental availability, **not** an automatic
default. Bilingual review and sustained live Samsung timing remain open.

Hy-MT2 Q3 failed the Samsung quick set with the native 20-second timeout.
LMT-60 Q4 was ~0.4 s/sentence on the Mac but translated “tickets to the
airport” as plane tickets and mistranslated insects. Smaller weights alone do
not solve the quality problem.

## Samsung S22 Ultra Q4 phone check

Hearth 0.22.3 Play QA, Samsung SM-S908E, Android 16. The first test APK had an
old staged native translation library; it silently accepted a changed JNI
signature and produced invalid prompt behavior. We rebuilt the library and added
a protocol-version guard. **Only the corrected-APK runs below are model
evidence.** Three passes of the six-sentence FLEURS quick set were run in the
app, with one publisher reference per sentence:

| Direction | Load + verify | First full pass | Warm full-pass median | chrF++ |
| --- | ---: | ---: | ---: | ---: |
| English→Arabic | 1.07 s | 20.64 s | 22.72 s | 40.45% |
| Russian→Arabic | 1.08 s | 23.26 s | 25.75 s | 31.80% |

On this same phone's English→Arabic quick set, the existing Marian pair took
1.86 s warm, ML Kit 0.39 s, and Hy-MT2 Q4 53.01 s. MiLMMT is faster than HY
but roughly 12× slower than Marian for this tested pair. Its English→Arabic
chrF++ 40.45% also did not improve on Marian's 42.20% in this small sample.
One custom line, “Hello, I need two train tickets,” became Arabic for
“tickets for two trains,” a material meaning error. The six references and
automated scores cannot establish broad quality or dialect performance.
MiLMMT remains an optional comparison model, not the Easy Setup default.

## Corrections to the circulated shortlist

| Claim | Verified position for Hearth |
| --- | --- |
| “TranslatePsy is ONNX; OPUS-MT drops into the same adapter.” | TranslatePsy publishes Bergamot/Marian **INTGEMM plus SentencePiece** packs. Hearth's three working OPUS-MT routes are separately converted and pinned **ONNX** packages. Sharing the Marian model family does not make the files interchangeable. The EuroNano publisher's 98.4% comparison is for its evaluation aggregate, not a guarantee for any Arabic or unrelated direction. |
| “Qwen3-ASR is 52 languages, ~350 MB Q4, and uses our llama.cpp adapter.” | The publisher specifies **30 languages plus 22 Chinese dialects**. Hearth currently downloads the verified sherpa-onnx **INT8** archive (878,702,423 bytes), using utterance-windowed offline decoding. A hypothetical Q4 size or a different GGUF runtime is not this shipped route. |
| “Parakeet-TDT-v3 is a July–September 2026 multilingual replacement.” | The [NVIDIA checkpoint](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) predates this window and covers 25 mostly European languages. It has no Arabic in its published coverage. We will not label a vendor/server throughput number as phone latency. |
| “Cohere Transcribe Arabic fits below 1 GB at Q4.” | [The July checkpoint](https://huggingface.co/CohereLabs/cohere-transcribe-arabic-07-2026) is a real 2B Arabic/English ASR family. A [sherpa-compatible third-party Q4 ONNX export](https://huggingface.co/abdelmoez98/cohere-transcribe-arabic-07-2026-ONNX) and a [C++ GGUF export](https://huggingface.co/cstr/cohere-transcribe-arabic-07-2026-GGUF) both report about **1.5 GB**, before runtime memory. No on-phone Hearth adapter has been validated. |
| “Qwen-MT offers a downloadable 0.6B/1.7B specialist.” | [Qwen's Qwen-MT announcement](https://qwenlm.github.io/blog/qwen-mt/) is from **July 2025** and describes a hosted API/MoE. It does not identify official 0.6B or 1.7B MT-tuned downloadable checkpoints. The general Qwen3 weights are different models. |
| “Tower+ 2B is Apache and Jais-590M is a ready translator.” | [Tower+ 2B](https://huggingface.co/Unbabel/Tower-Plus-2B) is **CC-BY-NC-SA-4.0** in its metadata (the body says NC), excludes Arabic and is a 2025 Gemma-2-based model. [Jais-590M](https://huggingface.co/inceptionai/jais-family-590m) is Arabic/English **general text generation** with custom JAIS architecture, not a validated translation specialist or guaranteed llama.cpp drop-in. |
| “NLLB and IndicTrans2 solve the new model request.” | [NLLB distilled 600M](https://huggingface.co/facebook/nllb-200-distilled-600M) is a 2022 research model with **CC-BY-NC-4.0** terms. [IndicTrans2 distilled 200M](https://huggingface.co/ai4bharat/indictrans2-en-indic-dist-200M) is older, direction-specific, uses custom preprocessing and gated file access. Both can inform future language coverage but are not new drop-in public-app defaults. |
| “EuroLLM, MADLAD, TranslateGemma and SEA-LION meet a 1 GB phone ceiling.” | [EuroLLM-1.7B-Instruct](https://huggingface.co/utter-project/EuroLLM-1.7B-Instruct) is a useful older multilingual comparison, but Q4 file size around the ceiling does not prove working-set memory below it. [TranslateGemma 4B](https://huggingface.co/google/translategemma-4b-it) is already in the catalog as a larger option. MADLAD-400-3B-MT and SEA-LION 3B are multi-billion-parameter models, not compact candidates for this cap. |
| “All files under 1 GB keep the three-stage pipeline under 1 GB RAM.” | Weight download size and live memory are different. MiLMMT's 806 MB Q4 file used about **1.34 GiB host peak RSS** in our six-sentence run, before concurrent ASR, app UI and audio buffers. Combined-RAM claims require a joint phone trace. |

Speech options worth a *bounded* future trial: [Meta Omnilingual ASR CTC
300M](https://huggingface.co/facebook/omniASR-CTC-300M), released in 2025,
has a sherpa-onnx offline CTC route and small third-party INT8 exports, but
the CTC route lacks the explicit language-conditioning path that the larger LLM
variant offers. It would be a broad-coverage **windowed** comparison, not a
forced-language replacement for Nemotron. [NVIDIA Canary 180M
Flash](https://huggingface.co/nvidia/canary-180m-flash) has a supported sherpa
offline route and even direct speech translation, but only English, French,
German and Spanish. Neither is a new June–September checkpoint or an Arabic
solution. The app already ships the June Nemotron 3.5 and January Qwen3-ASR
families. A new speech download button needs a pinned package and actual
Samsung result first.

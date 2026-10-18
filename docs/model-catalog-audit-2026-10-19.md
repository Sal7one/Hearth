# Model catalog audit · October 19, 2026

All **135 downloadable artifact files** and **119 distinct source links** were checked live. There were no missing files, incorrect sizes, changed published hashes or unreachable source links in the completed audit. These are file counts, not a claim of 135 separate models.

| Category | Download files | Coverage |
| --- | ---: | --- |
| Speech | 49 | 15 publisher packages, 27 Whisper precisions/checkpoints, 7 Vosk archives |
| Translation | 60 | 12 GGUF files and four files for each of 12 Marian directions |
| Camera OCR | 10 | Shared Paddle detector, language readers, manga and Meiki exports |
| Local voice | 16 | Six Supertonic runtime/configuration files and ten voice styles |

**128 files matched both their exact pinned byte size and SHA-256** against Hugging Face immutable-revision metadata, GitHub release asset digests, or bounded downloads of small configuration/tokenizer files. All download routes were also probed without fetching model weights.

The remaining **seven Vosk ZIPs were reachable and matched their pinned sizes**. The publisher does not expose a SHA-256 metadata endpoint, so this audit does not claim a fresh content-hash comparison for those archives. Hearth retains its existing archive and expanded-tree checksums and verifies actual imported/downloaded content before installation and use.

The [machine-readable evidence](model-catalog-audit-2026-10-19.json) records each file's URL, expected and observed size/hash, verification method, source status and check time. The first pass encountered five Hugging Face page rate limits. After the publisher's rate-limit window reset, all five passed. Recent successful checks were reused with their original timestamps rather than repeatedly requesting large catalogs.

## Catalog and source changes

- Speech source cards now derive download URLs, sizes and names from the installer catalog, replacing duplicated data. This also fills the previously missing Nemotron source-card download size.
- Every catalog family has model-card, browsable pinned-file and license references. GGUF conversions and Marian exports also identify the original checkpoint author separately from the artifact distributor.
- Whisper and legacy Marian file browsers now point at the same exact revisions used for downloads. Generic custom Qwen 1.7B and additional Vosk imports remain clearly separate from verified automatic downloads.
- HY-MT1.5 and official Hy-MT2 license buttons point to the actual license documents at the pinned artifact revisions. OCR and Supertonic source/license metadata is shared by their cards and the audit exporter.
- Source buttons open readable file/revision pages rather than initiating a weight download. External link failures are shown in the app.

## License and attribution checks

The audit preserves these distinctions in the cards:

| Family | Model license/source |
| --- | --- |
| Whisper | MIT, [publisher model card](https://huggingface.co/ggerganov/whisper.cpp) |
| Moonshine English / other languages | English MIT; other languages Moonshine Community License, as specified in the [publisher license section](https://github.com/moonshine-ai/moonshine-v2#license) |
| Qwen3-ASR / Omnilingual / TranscribePsy | Apache-2.0, respective publisher cards linked in the evidence |
| Phonon 2 | CC-BY-4.0 weights, distinct from Apache-2.0 runtime code; [publisher card](https://huggingface.co/FermionResearch/Phonon-2) |
| Nemotron 3.5 ASR | OpenMDW-1.1, [publisher card](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) |
| Vosk | All seven selected packages are marked Apache 2.0 in the [official model table](https://alphacephei.com/vosk/models) |
| HY-MT1.5 / Hy-MT2 | Tencent HY community terms for 1.5; Apache-2.0 for Hy-MT2. These are separate licenses, linked to their pinned files |
| TranslateGemma / MiLMMT-46 | Gemma terms; original Google and Xiaomi model cards are linked in addition to GGUF distributor cards |
| Index-Translate | Apache-2.0, [original publisher card](https://huggingface.co/IndexTeam/Index-Translate-2B) |
| Marian | CC-BY-4.0 ONNX conversions; original Helsinki-NLP model licenses remain separately identified per direction |
| Paddle / manga OCR / Meiki | Apache-2.0 Paddle and manga exports; LGPL-3.0 Meiki weights. Their exact pinned export cards are linked |
| Supertonic 3 | OpenRAIL-M weights, distinct from MIT runtime code; [pinned model license](https://huggingface.co/Supertone/supertonic-3/blob/3cadd1ee6394adea1bd021217a0e650ede09a323/LICENSE) |

Self-hosted Chatterbox, Qwen3-TTS and Fish Speech source cards and Google ML Kit terms were also included in the source-link checks. They are not counted as downloadable local model files.

## Repeating the audit

The exporter is a host test over the actual Kotlin catalogs, so a new model cannot be omitted because a separate hand-maintained URL list was forgotten:

```sh
./gradlew :app:testPlayQaUnitTest --tests '*ModelCatalogExportTest'
python3 scripts/audit-model-catalog.py --output build/reports/model-catalog/live-audit.json
python3 -m unittest scripts/test_audit_model_catalog.py
```

If a publisher rate-limits source pages, wait for its reset and retry only unfinished/new links:

```sh
python3 scripts/audit-model-catalog.py \
  --resume-report build/reports/model-catalog/live-audit.json \
  --output build/reports/model-catalog/live-audit-retry.json
```

Resume accepts reports less than one hour old and only reuses successful checks whose identity, size and hash still match the current export. Reports distinguish missing files, HTTP access failures, transient rate/server failures, size mismatches, hash mismatches and checks without a published digest. The auditor never reads app provider keys, sends authentication headers, writes model weights, follows non-HTTPS redirects, or treats a network failure as a successful empty result.

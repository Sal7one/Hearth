# QuickMT and SMaLL-100 in Hearth

Hearth now includes an optional **ARM64 CPU CTranslate2 4.8.3 runtime** with Ruy and SentencePiece. Existing STT, translation and TTS choices remain independent. This path does not use the GGUF Vulkan/OpenCL setting.

| Model ID | Direction | Selected required files | Download |
| --- | --- | ---: | ---: |
| `ct2-quickmt-en-ar` | English → Arabic | 6 | 363,146,041 bytes |
| `ct2-quickmt-ar-en` | Arabic → English | 6 | 410,561,529 bytes |
| `ct2-small100-int8` | 100 languages including Arabic/English | 4 | 344,075,494 bytes |

These remain experimental integrations awaiting official reference-output parity and owner S25 measurements. The exact pinned QuickMT EN→AR and SMaLL INT8 production packages now passed [native host sample inference](ct2-host-smoke-2026-10-20.md), including both SMaLL Arabic/English directions and empty-input rejection. The samples exposed a QuickMT EOS preprocessing mismatch, now corrected, and a SMaLL subject-loss error with Arabic diacritics. QuickMT AR→EN weights have not run. The separate upstream Arabic fixture passed FLOAT32 and INT8_FLOAT32. These checks are not translation scores or phone benchmarks.

QuickMT computes stored FP16 weights as FLOAT32, so runtime memory exceeds the download size. SMaLL computes INT8_FLOAT32. Both use greedy decoding, two CPU threads, at most one queued native batch, a 20-second generation deadline, 1022 source tokens, 384 output tokens and one complete nonempty result per call. Cancellation/deadline/length limits never publish a partial translation as success. CT2 exposes no Adreno GPU backend here.

Use Models → Translation → Advanced → Compact models. Each QuickMT direction is installed independently. Downloaded files are pinned to immutable publisher revisions and SHA-256. All required files must verify before the model folder is published. Installation does not change active settings; Use translator is explicit. TVs and unsupported app architectures cannot transfer/import/select/run these local models. FOSS can import an offline ZIP and has no download delegation.

For offline imports, collect the exact required components from [the pinned inventory](../handoff/models/translation.json) in one folder. Run:

```sh
python3 scripts/translation/ct2/package.py ct2-quickmt-en-ar /path/to/already-downloaded-assets /path/to/quickmt-en-ar.zip
```

This command verifies local sizes/hashes and creates a ZIP; it never fetches weights. The archive root contains `hearth-translation.json` and the model's exact files. Manifest example:

```json
{"schemaVersion":1,"modelId":"ct2-quickmt-en-ar"}
```

Nested files, extra files, duplicate paths, wrong checksums, unsupported schemas, unknown model IDs and oversized archives are rejected. Replacing an installed folder preserves the old install if publication fails. Original import URIs and every private component are recorded for launch validation.

Build/runtime sources and licenses are in `common-jni/src/main/assets/licenses/ct2/runtime-build.json`. Build with `bash scripts/translation/ct2/build-runtime.sh arm64-v8a`; stage with `python3 scripts/translation/ct2/stage-runtime.py arm64-v8a`. Host fixture command after a host build:

```sh
build/ct2-runtime/host/ct2_smoke research/CTranslate2/tests/data/models/v2/aren-transliteration --fixture
```

QuickMT publisher weights use CC-BY-4.0; SMaLL uses MIT. Publisher beam-search scores are not measured greedy-device scores. Keep existing translators available until owner comparisons establish the preferred bundle.

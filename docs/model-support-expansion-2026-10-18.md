# Model support expansion — 18 October 2026

This expansion adds 16 downloadable speech models, nine lightweight direct translation models and the new Index-Translate 2B multilingual translator. Recognition, optional text translation and optional TTS remain independently selectable.

The subsequent [optional GPU translation follow-up](android-gpu-acceleration-2026-10-18.md)
adds ARM64 Vulkan without changing this model inventory or the default CPU path.
Its final APKs are 60.9 MiB (Play ARM64) and 53.2 MiB (FOSS ARM64); the smaller
artifact sizes below describe the earlier model-support-only build.

| Catalog | Before | After |
| --- | ---: | ---: |
| Downloadable speech artifacts | 33 | 49 |
| Moonshine packages | 2 | 10 |
| Pinned Vosk packages | 0 | 7 |
| Marian direct translation pairs | 3 | 12 |
| Explicit two-model Marian routes | 2 | 30 |
| Multilingual GGUF translation variants | 11 | 12 |

The 49 speech artifacts comprise 15 publisher speech packages, 27 Whisper variants and seven Vosk packages. Qwen3-ASR 1.7B remains a prepared-package import option rather than an unverified download button. A Marian route reuses two direct models; it is not another weight download or an additional multilingual model.

## September–October releases and model browser follow-up

The newest additions are [Index-Translate 2B](modern-translation-models-2026-10-18.md), released September 30 with official GGUF weights October 3, and [TranscribePsy 250M](transcribepsy-runtime-2026-10-18.md), released October 14. Both have actual-weight host inference checks and pinned download artifacts. Index adds a hybrid Qwen3.5 translation architecture, script-aware language routing and the publisher’s 150 language/variety inventory. TranscribePsy adds a private CPU speech plugin for eight European languages. Neither changes the existing default or ties STT, translation and TTS to a single provider.

The model library uses category tabs and a single download-queue entry. Speech and translation have Browse models and Installed and imports views. Searchable language/engine/type pickers replace growing chip walls; alternate precisions share a checkpoint card and live in Details with source, license and integrity information. Translation filters by source and target, separates compact pairs from multilingual models and makes two-model English pivots optional. Manual imports stay available in FOSS, with existing network gates retained.

Queue cards distinguish waiting, network transfer, verification, installation, paused/failed and installed states. Percentages describe transfer bytes only. Each state has one primary action; file inspection, repair and removal live in collapsed options. Speech selection validates the private installation instead of trusting a saved Installed record, so missing files stay repairable. Caption read-aloud settings now live with Voices.

Pure host tests cover stage presentation, checkpoint/precision grouping, actual direction filtering, stale saved selection and atomic-pair disk budgeting. Kotlin compiles validate both distributions. These checks do not substitute for owner device review of layout or responsiveness.

## Speech: language-specific Moonshine v2 models

Hearth's existing Moonshine adapter already accepted the two-graph v2 format. The restriction was in Hearth's profile catalog and its English-only source tagging. This update adds eight installable models, covering seven additional languages, through the same pinned Sherpa CPU runtime. It does not introduce a new native dependency or combine recognition with translation.

| New checkpoint | Download bytes | Expanded package bytes |
| --- | ---: | ---: |
| Arabic Base | 119,143,068 | 141,523,592 |
| Spanish Base | 50,846,902 | 65,357,006 |
| Japanese Tiny | 47,889,565 | 72,848,066 |
| Korean Tiny | 49,153,415 | 72,433,302 |
| Japanese Base | 104,162,109 | 142,033,850 |
| Ukrainian Base | 111,037,143 | 141,505,978 |
| Vietnamese Base | 106,719,129 | 141,442,654 |
| Chinese Base | 99,905,420 | 141,542,510 |

Storage budgeting uses the verified expanded tree sizes for all ten Moonshine packages, including the existing English Tiny (44,441,158 bytes) and Base (141,498,518 bytes). The TAR installer writes that tree directly; it does not need a second private compressed copy. The existing reserve covers installation metadata. Other packages keep the existing conservative estimate unless their expanded size was verified.

These remain **utterance-windowed recognizers**: audio is collected into bounded speech segments, then a final transcript is decoded. They do not provide cache-aware streaming partials. The existing configurable window and endpoint settings still apply. Separate STT, translation and TTS selections remain available.

Each checkpoint has one fixed source language. Hearth resolves Auto to that identity from the verified package before JNI configuration, and rejects an incompatible explicit source. The decoder receives no language-forcing hint; selecting another language means selecting another model. This also prevents Arabic and other model outputs from being incorrectly tagged as English when routed to translation.

### Provenance, format and licenses

- Sherpa source remains pinned to `210f340bcfdfd5b9ad6b24245e77a934d6c28f1b`.
- The official `asr-models` release publishes the `2026-02-27` quantized archives. Every archive above was downloaded, checked against its publisher SHA-256 and byte length, and inspected. Exact archive pins are in `SpeechDownloads.kt`; the release itself can replace assets, so the byte and digest checks remain necessary.
- All eight packages contain the existing supported roles: `tokens.txt`, `encoder_model.ort` and `decoder_model_merged.ort`. The installer still writes the verified `hearth-speech.json` manifest at its package root and retains the archive and path limits.
- The actual non-English archives contain the **Moonshine Community License**, including its research, personal and conditional commercial terms. They are labeled with that license. The separately downloaded and verified English Tiny and Base v2 archives explicitly retain MIT terms for English weights. Code and model licenses are distinct.

Primary sources: [Sherpa Moonshine v2 model instructions](https://k2-fsa.github.io/sherpa/onnx/moonshine/models-v2.html), [publisher assets and SHA-256 digests](https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models), [Moonshine v2 repository](https://github.com/moonshine-ai/moonshine-v2), [publisher license](https://github.com/moonshine-ai/moonshine-v2/blob/main/LICENSE).

### Validation and limits

All eight actual model packages ran through the production C++ speech adapter and `SpeechNativeSession` on a CPU-only macOS ARM64 host. Each produced nonempty final text tagged with its expected source language, with distinct utterance IDs. The probes used each package's own sample WAV and the unchanged four-second utterance window. This checks inference compatibility and delivery, rather than recognition accuracy.

For example, Arabic Base processed its 6.546-second publisher sample in 0.120 seconds after loading on this host in the all-model run. That is neither an Android timing claim nor an end-to-end caption latency measurement: endpoint waiting and real audio capture are absent from the probe. Its sample transcript was imperfect. Accent, dialect, noise, quiet speech, recognition quality and phone responsiveness still need owner testing against the existing models.

The updated sanitizer speech suite passed 87 checks; the existing Whisper failure suite passed 144. Kotlin coverage opens every Moonshine profile through a verified package and checks the language passed to the native driver, rejects the wrong explicit source, checks fixed identity versus language forcing, and confirms safe handle destruction. Eight Python packaging tests passed with the same three-role contract and path/symlink checks.

Raw host measurements, transcripts, archive inspection receipts and model weights remain under ignored `build/model-expansion/`; weights and build outputs are not committed.

## Speech: pinned Vosk packages

The existing Vosk recognizer now has seven automatic download/install choices from the [official Vosk model catalog](https://alphacephei.com/vosk/models): US English, Spanish, French, German, Russian, Chinese and Arabic MGB-2. The six small ZIPs range from 39,817,833 to 46,499,967 bytes. Arabic is a larger broadcast-speech model: 333,241,610 compressed bytes and 698,195,675 expanded bytes. It is explicitly labeled larger and does not imply broad dialect coverage. Every selected model is listed as Apache-2.0, and every downloaded feature configuration specifies 16 kHz audio.

Vosk provides streaming recognition through the existing native adapter. Each package fixes its spoken language; changing that language means changing the model. Hearth recognizes a catalog package by its complete verified tree digest, so renaming its installed folder does not change its language identity. An unknown imported Vosk folder does not acquire a language from its filename. Original captions remain usable; translation and read-aloud need a known source language.

The installer verifies the ZIP's exact byte length and SHA-256 before opening it, inspects central-directory entry types, rejects links/special entries and unsafe or duplicate paths, enforces entry and expansion bounds, and checks entry sizes and CRCs. It accepts only STORE/DEFLATE: Commons ZIP metadata inspection retains Unix file modes, while the platform ZIP reader decompresses data after both readers agree on names, methods, types, sizes, CRCs and entry counts. This requires no additional compression libraries. Extraction uses the existing bounded directory sink and publishes only after the independent model-tree digest matches. Its temporary private ZIP is included in installation space budgeting and removed after success or failure. Reinstall can repair a damaged private copy. Download transport retains the existing FOSS network gating; FOSS can still import an extracted local model folder.

Twelve focused Vosk tests passed, including all seven real official archives through the production extraction path with both pins checked. Synthetic archives cover traversal, foreign roots, invalid separators, duplicate entries, symlinks/special files, wrong archive/tree/expanded-size pins, CRC damage, missing assets and cancellation cleanup. They also exercise STORE success and reject an unsupported compression method even when archive and tree pins match. A transport regression checks each model's ID, URL, byte count, SHA and temporary-ZIP-plus-tree space reserve. Four source-catalog tests also passed, including the distinction between seven pinned automatic installs and a custom folder import.

These checks verify packaging and installation compatibility. The seven Vosk models have not been loaded or benchmarked on an Android device in this expansion. Exact sizes, source details and the opt-in archive test command are in [Vosk model downloads](vosk-models.md). Archives and inspection receipts stay under ignored `build/model-support/vosk/`.

## Lightweight translation: 12 direct pairs and 30 explicit pivots

Marian / OPUS-MT now offers English ↔ Arabic, Russian, Chinese, French, German and Spanish. Nine added directions extend the original English→Arabic, Russian→English and Chinese→English choices. Each direction has its own four-file pinned ONNX package, roughly 220–242 MiB, instead of requiring a multilingual GGUF of 741 MiB or more.

Thirty explicitly selected routes connect different non-English languages through English. Arabic→French, for example, owns Arabic→English and English→French sessions and runs them in order. Both installed model directions are required; either stage's real error remains visible. The original `marian-ru-ar-via-en` and `marian-zh-ar-via-en` IDs, original three tree pins and existing download filenames remain intact. Pivots use two loaded models and two inference passes, with the associated memory, latency and quality tradeoffs.

The model browser groups direct targets and English pivots under their actual source language. Per-pair license labels distinguish the English→German original's CC-BY-4.0 terms from the other originals' Apache-2.0 terms; the ONNX conversions use CC-BY-4.0. Existing GGUF pins and prompt protocols are retained; the new Index family is described above.

The nine added source tokenizers passed 29 reference cases. This exposed a narrow unknown-surface bug: a character missing from the source SentencePiece vocabulary, such as Spanish `€`, may still have a token in Marian's joint vocabulary. The tokenizer now preserves the original surface, including consecutive unknown characters, before looking it up. Dedicated sanitizer fixtures cover the supported joint token, contiguous runs and a truly unknown run. This does not close the separate malformed-charsmap audit item or establish tokenizer parity for every possible input.

The production C++ Marian engine with ONNX Runtime 1.20.1, two CPU threads and AddressSanitizer/UndefinedBehaviorSanitizer loaded all nine added packages after their four files passed exact size and SHA-256 verification. Every model completed three requests, for 27 full-model requests covering tickets, negation and price/number contrasts, with no native runtime or sanitizer failures. These are compatibility smokes, not phone timing or translation-quality benchmarks. One Arabic sentence changed “today” into a daily rate in English, while French→English preserved a price contrast with awkward phrasing. Host checks do not replace Android model and bilingual quality testing. See [local translation](local-translation.md) for coverage, quality examples and the original pairs' earlier phone results.

## Integration verification status

Focused catalog, installer and fixed-language tests passed, including Play/FOSS Kotlin compilation. Source identity is resolved from the selected model before native recognition, translation routing and original-language TTS; an old saved language hint cannot override a fixed-language package. Caption-language tests cover every Moonshine profile, each known Vosk identity and unknown custom imports. A stale-English-hint Arabic-model regression also checks compatible direct/pivot translation targets and incompatible source directions with both explicit and remembered local translator routes. The chooser and target picker use the same actual model identity. Existing route IDs and direct-pair direction boundaries have dedicated coverage.

The full required integration gate passed:

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :common-jni:externalNativeBuildDebug
./gradlew :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

The final combined Gradle test, both QA Kotlin compiles and both QA assembly run completed successfully in 2 minutes 43 seconds, with 302 tasks. The debug external native build also passed. The common native host suites and Marian tokenizer/decoder suites passed under AddressSanitizer and UndefinedBehaviorSanitizer. Speech foundation passed 98 checks, Whisper 144, the new TranscribePsy adapter 26 and the production translation wrapper 2,025. Package/provenance Python tests passed ten cases. All three speech plugins and the translation runtime were rebuilt and staged for all four Android CPU architectures; binary and build-time source-snapshot hashes passed verification.

Both optimized QA variants built successfully, and release verification passed for all ten APKs: Play and FOSS for ARM64, ARMv7, x86, x86_64 and universal. Verification covered native binary identities, ABI and page alignment, packaged provenance, all speech/translation notices byte-for-byte and FOSS network permissions. Final ARM64 artifacts are 47.3 MiB (Play) and 39.6 MiB (FOSS); model weights are separate downloads. These remain debug-signed test artifacts. No device was controlled, and no new Android performance or quality claim is made.

### User settings and local model bindings (October 19)

The Basic/Advanced switch is a shared DataStore preference. Existing caption,
speech tuning and speech acceleration DataStores remain in place. All remaining
user preference namespaces now migrate atomically into `hearth_user_preferences`:
`hearth-appearance`, `hearth-home`, `conversation-options`, `typed-translation`,
`reading-overlay`, `voice-output`, `camera-translate`, `conversation-translators`,
`translation-browser`, `byok_keys`, `byok_cloud_config`, and `cloud_voice_v1`.
Only `folder_uri` and `folder_label` leave the mixed `file_downloads` operational
store, moving to `download-location`; download jobs and IDs stay untouched.
Model registration indexes, Vosk asset anchors and startup diagnostics remain
operational state. Android's app-locale preference remains managed by AppCompat.

Migration preserves every primitive/string-set type and exact encrypted secret
envelopes. Keystore aliases and basic-protection key files do not change, and
no credential is decrypted or re-encrypted merely to move storage. Legacy values
are cleared only after DataStore commits migration. Initial read/migration errors
remain visible and retry the same DataStore instance. Existing synchronous readers
use a DataStore-backed adapter; `apply` updates memory immediately and coalesces
pending slider drafts, while `commit` waits for its own durable result. Failed
writes report an observable error and the next save includes prior pending choices.
The main launch loading gate initializes on IO; a service launched without an
activity performs its first small settings read synchronously for compatibility,
without scanning or hashing model weights on that path.

Local source bindings record verified imports' app-private copies, file sizes,
modification metadata and selected SAF source URI. On every app launch, an IO
check discovers older installed registry/speech/GGUF models too and checks local
path existence, readability, sizes and retained source grants. A selected missing
model remains selected and receives a repair message. A retained grant is
explicitly **not proof that the original source is currently accessible**: an
explicit Check source action opens the original descriptor. Losing that grant
does not invalidate an already verified private copy. Relocating/importing uses
the ordinary integrity-checked importer before a new binding is recorded. Full
publisher-pinned or TOFU file/tree hashes are still checked before native use;
launch metadata checks do not substitute for those hashes. Portable setup JSON
never exports device-local paths, SAF grants or secret values.

Every process entry now starts the same asynchronous `StartupModelAudit`, including
reading shortcuts and service entry without the main activity. The main activity
awaits that shared job instead of starting another scan. Failed initialization or
metadata checks remain retryable; successful work is cached for the process.
Binding mutations and scans share a mutex so a launch scan cannot overwrite a
newly imported source URI. Existing and selected OCR assets and active Supertonic
core/style files also participate using pinned catalog sizes. Snapshot failures
retain their actual expected/observed size or access error in the saved binding.

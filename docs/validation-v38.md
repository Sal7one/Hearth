# Hearth 0.21.1 / code 38 — caption translation selection

20 September 2026. v78 QA patch. No native/model artifacts changed.

## Root cause and correction

The Home CC chip cleared `localTranslationEnabled`. The advanced screen and
overlay Translate chips changed only `mode`. A selected local translator then
remained visible while routing rejected it before loading either model.

`withCaptionMode` is now shared by mode controls, stored configuration reads and
updates, and route resolution. Translate activates the explicitly selected text
provider or selected local translator for native speech. Existing contradictory
saved states recover on read. Original captions keep translation off. Integrated
OpenAI/Soniox and legacy Whisper routes retain their existing behavior without an
explicit text override. Missing models/providers still fail actual validation;
no model is silently downloaded or guessed.

Home opens translator setup when required, instead of labeling a blocked
translation start as Start CC. The language picker follows the same explicit
translator even when an older configuration contains the false bridge flag.

## Validation

On Samsung SM-S908E / Android 16, v77 reproduced the exact reported diagnostic:
“The selected speech path cannot translate with this setup.” Starting from the
installed Nemotron/Hy-MT2 easy preset, select Original captions in Home, Translate
in advanced settings, then launch the Live captions tile. The selected models
remain present while preflight rejects translation.

Four new host regression cases cover saved-state recovery across local speech
engines, mode round-trips preserving language/appearance, explicit local/cloud
route and language agreement, and original/integrated route preservation.

This patch does not change model inference, native kernels or translation quality.

Required gates passed: `./gradlew test :app:compilePlayQaKotlin
:app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa`. All six app
suites contain 269 tests, zero failures/errors; existing skips are one per Play
variant and two per FOSS variant. Common JNI debug/release: 74 cases each.
`python3 scripts/verify-release.py` passes for both final packages, including
vendored hashes, 16 KiB native alignment and FOSS network permissions.

Upgrading the same phone without changing its broken saved setup restores Start
captions. The Live captions tile then passes preflight and reaches Android's
screen-sharing consent instead of the unsupported-translation diagnostic. No
model import, re-download or Easy setup reset was needed. Android capture consent
was granted; the overlay ran, reported active audio processing and “Translator
ready · waiting for a completed caption.” No media was playing, so this checks
startup/readiness rather than a translated utterance. Capture was then stopped.
Repeating Home CC → advanced Translate on the fixed build retains Start captions
and no longer shows the missing-translation setup prompt.

## Artifacts

QA builds are release-optimized, ARM64 and debug-signed. Both use the same package
and replace one another. Source remains local; GitHub draft delivery is binaries
only until the owner pushes the matching commit.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `hearth-v78-caption-translation-cloud.apk` | 36,038,297 | `e516341c31b7d6b853bc701c52c660dddb848a5ac51bf037a02e0df3309a0264` |
| `hearth-v78-caption-translation-offline.apk` | 28,189,175 | `d690e4e068fc46570b4ce3027165613a106e134efd2bec1ad297863f67347728` |

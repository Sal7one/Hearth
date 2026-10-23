# Model Lab readiness and language experiments

Android candidate **0.30.0 / code 99**, tag **v0.30.0-rc.2**, branch
`codex/hearth-desktop-kmp`. Existing QA package/certificate retained. Desktop
binaries are unchanged from RC1. No adb, GitHub Actions or owner device driving.

## User-visible fixes

- The empty personal Saudi set now explains why Run is unavailable beside the
  persistent Run/Stop footer. Record/import and Use bundled Arabic are explicit
  actions. Personal Saudi audio is never silently replaced by another dialect.
- Custom WAVs and Saudi drafts have independent bounded private copies, labels
  and hashes. Custom text/reference and Saudi prompt/transcript preferences are
  separate. Reopening the screen restores the actual recording and selections.
- Refresh publishes usable candidates before catalogue mapping, which now uses
  install receipts and bounded metadata instead of hashing every installed
  speech weight. Actual selected runtime initialization retains integrity checks.
- English/Russian/Chinese publisher audio and transcripts are directly available
  as a multi-language batch; Arabic is an explicit additional corpus. The preview
  shows source/transcripts/references and plays verified audio with benchmark gain
  normalization. Playback releases on Stop, dismissal and lifecycle backgrounding.
- Translation's destination picker uses every selected source language, including
  EN/RU/ZH → Arabic with an old stored Arabic single-source preference. The direct
  bundled-test action resets stale empty/Arabic-only matrix selections.

## Language control and scoring

`BenchmarkLanguagePlan` separates the known sample language from recognizer
configuration. Match sample language requests its language hint where supported;
Automatic requests detection; Compare both creates distinct experiments only for
models supporting both. Fixed-language and automatic-only engines run once and
are labelled accurately. Unsupported combinations return explicit omissions.
Pinned Vosk tree identities establish their known fixed languages; unknown
imports do not acquire invented multilingual coverage.

Rows, JSON and CSV record decoder control/code. Recognition variants have
different experiment identities and resume hashes, while their shared sample
language, input and reference hashes remain unchanged. Old results remain
readable and show Unrecorded where evidence is absent.

Reference score is `100 × max(0, 1 − WER)` for speech, using CER for Chinese;
translation is `100 × chrF++`. Raw metrics and speed remain separate. Failed,
incomplete, unreferenced and voice-quality comparisons do not receive invented
scores. All languages shows latest exact input/device/protocol cohorts per
direction within one task; no winner spans unrelated languages or inputs.

The matrix planner accepts hundreds of selections and shares exact publisher
cases within each route. Its host regression plans 500 models / 1,500 language
comparisons without loading model weights. Execution remains sequential and
application-owned through the existing benchmark foreground controller.

## Verification

Required `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin` passed
after all edits. Each of the six Android variants reports **837 cases, zero
failures/errors**, with 3 Play / 15 FOSS intentional skips. All variants execute
four real Compose screen regressions for missing Saudi input, preserved model
selection/custom WAV reopening, matrix Arabic targets and stale-language recovery.
Policy, private input corruption/interruption, metadata-only discovery, real
publisher WAV/PCM pins, 500-model planning, language identity/resume, score and
export regressions also pass.

Both QA variants were assembled. `scripts/verify-release.py` passed all ten APKs,
including signer/identity, ABI/native pins, alignment, permissions and FOSS
absence of aria2/network access. Actual ARM64 upgrade identities were compared
against RC1 (code 98). All 28 bundled WAV/PCM hashes were rechecked offline.
No C++ source, shared engine contract or desktop source was changed in this patch.

Evidence is staged under ignored `build/benchmark-resume/evidence` and packaged
with the GitHub draft. This is host coverage; actual Android inference, audio
playback and device lifecycle/battery behavior remain owner acceptance checks.

## Owner test

Update the existing QA app using Play ARM64. Open Model Lab → Models →
Use bundled English / Russian / Chinese tests. Review the language chips, preview
audio/text, choose Match sample language or Compare both, select installed models
and Run. Inspect results per language and the decoder/backend evidence. Test
background/return and navigating through model imports without losing the input
or selection. Personal Saudi recordings remain a separate selectable test set.

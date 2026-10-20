# Branch and PR integration audit — 20 October 2026

This records the branch snapshot before the final default-branch integration.
The repository default is `main`; there is no `master` branch in this snapshot.
The audit was read-only. Archiving, merging, PR closure and branch deletion are
separate actions whose remote results must be verified by the release owner.

## Audited commits

| Reference | Exact commit |
| --- | --- |
| Current model/settings branch | `8ecd6501a9cb1067d388784d835742cb431cfc79` |
| Remote `main` | `d7c6be41907f4dca7bab4aa987564c0a0ac31e0e` |
| Local `main`, one commit behind remote | `06ef54f90fad2258b9f67130b4d254f2de3baca3` |
| [PR #2: draft models, read-aloud, voice notes and document OCR](https://github.com/Sal7one/Hearth/pull/2) | `49699e5ceb092803c8fce53c9226ecfdd8bdda96` |
| [PR #3: TV onboarding, phone setup and remote overlay controls](https://github.com/Sal7one/Hearth/pull/3) | `4061a631305d574e70d4f9a2a2cdb7a472caa70f` |

`origin/main` is an ancestor of the current model/settings branch. The latter
contains 19 commits absent from remote `main`, with no commits unique to
`origin/main`. Every other remote branch tip is also an ancestor of this current
branch **except PR #2**. `git cherry` reports PR #2's sole commit as `+`, so its
complete patch must not be described as already merged or patch-equivalent.

PR #3's four commits are all reachable from the current branch. In particular,
merge `7886d00fb3dc32c3880bb7f5ac031c5b45528c1b` has parents
`4061a631305d574e70d4f9a2a2cdb7a472caa70f` and
`d7c6be41907f4dca7bab4aa987564c0a0ac31e0e`. Integrating the current branch into
`main` therefore preserves the actual TV commit ancestry. Recheck PR #3's GitHub
state afterward instead of replaying its old changes.

## PR #2: present, replaced and deferred work

PR #2 changes 162 files, with 5,696 insertions and 171 deletions. Compared with
the audited current tree, 80 PR-added files are absent, 21 changed paths still
match the PR's original base, and 61 changed paths have later modifications.
None of its 162 complete file blobs matches the PR tip exactly. These counts
describe file identity, not whether individual ideas or hunks were adopted.

| PR #2 area | Current evidence and disposition |
| --- | --- |
| Five additional Marian directions | **Present.** `MarianPackage.kt` contains the same pinned AR→EN, EN→RU, EN→ZH, EN→ES and ES→EN artifact revisions. `MarianCascade.kt` also builds explicit two-model routes from compatible English pivot pairs. Current coverage additionally includes FR/DE pairs. These additions do not require merging the obsolete whole commit. |
| Shared translation runtime initialization | **Present.** `translation/text_model.h` already has `initializeRuntime()` with `std::call_once`, log registration and `llama_backend_init()`. UTF-8 Java error construction also remains in the current JNI adapter. PR #2's additional shared vision error header is absent because its vision adapter is absent. |
| Speech/translation/model setup goals | **Replaced in part by later work.** The current branch has the five new Arabic/English STT families, compact CT2 translations, five native TTS adapters, reusable TTS SDK, independent stages, verified packages and the settings/download redesign. This does **not** mean every PR #2 model or feature is supported. |
| Parakeet TDT v3 and SenseVoice | **Unique and deferred.** Their Sherpa adapters, engine/profile IDs, source-language handling, pinned downloads and related tests are absent from the audited current branch. Phonon 2's English Parakeet-derived model is not equivalent to the separate 25-language Parakeet profile. The five newer STT additions do not constitute adoption of SenseVoice. |
| Seven additional Paddle OCR readers | **Unique and deferred.** The English-specific, Korean, Greek, Thai, Devanagari, Tamil and Telugu profiles, matching dictionaries and native smoke utilities are absent. Existing Latin/CJK/Arabic/Cyrillic/manga/Meiki profiles do not establish support for all seven exports. |
| Kitten and Kokoro hosted voice adapters | **Unique and deferred.** PR #2's revision-pinned Python adapters and tests are absent. Current Qwen/Chatterbox/Fish/Ming server routes and native local families are different integrations. |
| Standalone read-aloud page | **Partly replaced, with unique controls deferred.** Current `hearth-tts` article streaming and shared `VoicePlayer` support the existing app consumers. PR #2's separate `TtsScreen`, page 17, `TtsPlaybackRequest`, explicit per-request Android engine/voice override UI and its resources/tests are absent. Do not advertise that standalone page as delivered. |
| Cross-feature audible ownership | **Unique enhancement deferred.** PR #2's `VoicePlaybackOwner` registry and cloud/note callers are absent. Current `VoicePlayer` has its own audible-owner handling and Android audio focus; cloud speech also requests audio focus. Those mechanisms are not an implementation of PR #2's shared registry. The draft's missing-voice discovery helper concerns its absent standalone screen; current playback already rejects an unavailable explicitly saved Android voice. |
| Voice-note recording | **Unique and deferred outside the current scope file.** WAV capture/storage/export, credential-screen gating, notes UI and `Pcm16WaveWriter` are absent. Optional caption history is a different feature. |
| PDF/DOCX and GLM-OCR document workflow | **Unique and deferred outside the current scope file.** The document UI/view model, DOCX extractor, GLM vision session/decoder/projector integration, dictionaries and tests are absent. Current camera/image OCR and the Audar audio-projector runtime do not implement this document workflow. |
| Version, navigation, documentation and native payload changes | **Obsolete as a unit.** The draft's 0.24.0/code 83 bump, pages 17–19, home cards and old runtime DSOs/provenance cannot be applied over the current release. They must not overwrite current native artifacts, source hashes, limits or release verification. Preserve the draft as research/history rather than loading its binaries into the current APK. |

The current `AGENTS.md` limits scope to speech/captions, independent translation
and TTS, overlays, camera/image OCR, model import and direct downloads. PR #2
also edits that file to describe voice notes and PDF/DOCX as approved, but an
unmerged branch's instruction text is not authorization to expand the current
task. Future adoption of deferred features needs an explicit scope decision and
current integration/validation; this audit does not classify them as completed.

No absent PR #2 hunk was identified as a demonstrated critical fix for the
current production paths. The ownership and optional model additions remain
useful candidates, with their original implementation and evidence preserved.
This is a source/ancestry review, not a new execution of the draft's native
models or tests.

## Preserve PR #2 before cleanup

The chosen cleanup plan is to archive the exact unique commit under the annotated
tag `archive/pr-2-models-notes-documents`, close PR #2 with the above deferred
scope clearly stated, and remove its old branch only after the remote tag is
verified. The archive is historical source, not an installable release or an
assertion that its features were merged. Do not use an empty-tree/`ours` merge
just to label its unadopted functionality as merged.

The integration owner executed the archival step after this audit:

```sh
git tag -a archive/pr-2-models-notes-documents 49699e5ceb092803c8fce53c9226ecfdd8bdda96 -m "Preserve deferred PR 2 model, read-aloud, notes and document draft"
git push origin refs/tags/archive/pr-2-models-notes-documents
git ls-remote origin refs/tags/archive/pr-2-models-notes-documents refs/tags/archive/pr-2-models-notes-documents^{}
```

The signed tag object is `19939c72716fc21f185d5933940345170bf7b673`. Its
remote peeled target was verified as
`49699e5ceb092803c8fce53c9226ecfdd8bdda96` before deleting
`codex/hearth-models-notes-documents`. Keep the branch if archival verification
does not succeed. [PR #2 was closed with an explicit archival/disposition record](https://github.com/Sal7one/Hearth/pull/2#issuecomment-6298024346). The closure distinguishes the retained Marian
work from deferred Parakeet/SenseVoice, extra OCR, hosted voices, standalone TTS,
voice notes and documents.

## Remaining branch inventory

Remote branch heads were checked against live `git ls-remote --heads origin`;
they matched the local remote-tracking refs at audit time. Every row below is
already reachable from the audited current model/settings branch. Delete these
only after that branch is integrated into `main` and the same ancestry test
passes against the final `main`. Keep all existing release tags.

| Remote branch | Exact audited tip |
| --- | --- |
| `codex/android-all-abis` | `3c54501ba155a7f8e7244b31741811fa466ef18a` |
| `codex/caption-history-sqlite-fix` | `6b0c4e97dbec6b0fe95161d6dd3022b7c313181a` |
| `codex/caption-session-history` | `b07cf06d19d1a442070eb0b4ea11342e8acbc921` |
| `codex/cloud-voice-providers` | `12cf5d0f10cb952f1d6538e84a16dadba7b52739` |
| `codex/conversation-turn-reliability` | `f30900c05ce5d85c74d8c103675c2ad9721cd35f` |
| `codex/model-phase` | `6ad8305eb2aa6eccfd15037fbaa968a3f041d656` |
| `codex/s25-models-tts-provider` | `8ecd6501a9cb1067d388784d835742cb431cfc79` |
| `codex/whisper-reset-lifecycle` | `7b44a0092683f2a403c06f4d8a650d55c8c0539b` |
| `feat/phonon-2-speech` | `135849008bf3802ad2769c2ee071f02f5a9478e8` |
| `release/v0.25.0` | `7886d00fb3dc32c3880bb7f5ac031c5b45528c1b` |
| `release/v0.25.1` | `1c7c1e90b07a78e88e26a58596242123c5515124` |
| `release/v0.25.2` | `a879d362b49e76692d4dc6db298f3afd996f5c17` |
| `release/v0.25.3` | `9e4c0c20b349efa2052ed707dfe420d03fc34dfa` |
| `release/v0.25.4` | `6c15890b284b6c74074d13d7d45d1311d0cf6035` |
| `review/common-jni-audit` | `420c2bc49225a4156a0753b3f4887c0ca3d4a82f` |
| `tv` | `4061a631305d574e70d4f9a2a2cdb7a472caa70f` |

Local `release/v0.25.0`–`release/v0.25.4` and `tv` match their corresponding
remote tips. Local `codex/tv-readme-images` is
`d7c6be41907f4dca7bab4aa987564c0a0ac31e0e`, already reachable from both remote
`main` and the current branch. Local `main` is retained and advanced through the
final integration. Switch the sole active worktree to `main` before removing
the checked-out model/settings branch. No additional managed worktrees, active
custom hooks or `.git` instruction files were found; only standard sample hooks
were present.

An ancestry-gated deletion should re-read each remote tip immediately before the
operation. A branch advanced after this audit requires another comparison. For
an already-integrated tip, the relevant read-only gate is:

```sh
git merge-base --is-ancestor origin/BRANCH main
```

PR #2 uses the verified archive tag instead of this ancestry rule. Do not delete
`main`, release tags or its unique unarchived draft commit.

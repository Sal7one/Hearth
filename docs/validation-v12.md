# 0.5.1 / versionCode 12 — model sources and grouped setup

Date: 2026-09-12. Follows 917d31c (0.5.0).

## Delivered

- Models has persistent Speech / Translation / Cloud groups, with a scroll area
  per group and source browsing separate from the active model selection.
- Speech contains Nemotron, Qwen, Moonshine, Whisper and Vosk. Matching installed
  models, imports and source/file links appear together. Marian is under Translation.
- Native model source cards cover every accepted profile. Verified publisher archive
  URLs are available for Qwen 0.6B and Moonshine Tiny/Base; Nemotron links its pinned
  Q8 file. The download action uses the existing DownloadManager destination.
- Publisher archives are explicitly labeled as requiring preparation into a speech
  ZIP. The app still does not automatically convert tar.bz2 or bare speech GGUF files.
  Qwen 1.7B is labeled custom-package-only, with no invented ready-made download.
- Translation groups HY-MT1.5 / Hy-MT2 / TranslateGemma by family and size. Browsing
  a family does not silently change the active translator. Installed models have a
  Use action; download/import retains the existing exact hash and size checks.
- ML Kit uses the shared searchable language picker and concise pack controls;
  source/target pack names are readable. Longer storage/pivot details are expandable.
- Cloud contains provider documentation and key setup, not local-weight downloads.
  The cloud key screen shows the selected provider's key; unrelated batch/voice
  options are collapsed for streaming. Key entry is masked. Readiness callbacks now
  follow Soniox/Scribe/Deepgram/AssemblyAI keys as well as OpenAI.
- Advanced setup reuses the source panels, separates speech from local translation,
  and exposes the local bridge for compatible streaming-cloud paths. Soniox’s live
  translation status no longer calls itself OpenAI.
- All sources are also collected in model-sources.md, linked from README and model docs.

## Validation

Required Gradle tests, both QA Kotlin compiles and both QA APK builds passed.
Source-catalog tests cover every accepted native profile, direct-download input
validation and the distinction between downloadable artifacts and custom/cloud
paths. No JNI/runtime changes; native binaries are unchanged from validated 0.5.0.
The release verifier checks both APKs, including native hashes, 16 KB alignment and
foss network isolation. Source URLs were checked against publisher documentation
and the sherpa release asset list; three stale cloud-documentation URLs were corrected.

Authorized device UI check: Samsung SM-S908E, Android 16. The installed test build
shows the grouped model screen, installed Nemotron, publisher/import actions, HY
family sizes and all cloud documentation links. Browsing HY leaves the active ML Kit
translator unchanged. This UI work does not add new model inference claims, repeat
large downloads, or validate new paid-provider accounts.

The remaining automatic publisher-archive conversion is recorded separately in
BACKLOG.md; the source buttons do not promise one-tap speech installation.

Final QA verification: 129 app tests per flavor/build variant (six variants),
63 common-jni tests per variant (two), zero failures. Final build completed with
268 tasks: 47 executed / 221 up-to-date. Play APK: 32,840,578 bytes; foss:
24,475,043 bytes. Release verifier PASS for both. Final APK installed on the test
phone. Soniox key screen showed only Soniox key/docs and collapsed optional batch
settings; original Batch preference restored. The final compact ML Kit layout and
searchable language-pack picker were opened successfully. Capture remained stopped.

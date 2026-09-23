# 0.22.6 QA validation

The intermittent Nemotron → Marian CC-only notice came from final captions
whose source metadata was empty or `mul` in Auto spoken-language mode. The
translator previously rejected these before checking the selected model's
direction. The shared bridge now resolves a source only when a unique installed
direction and the caption's non-Latin script agree. Explicit spoken-language
selection still wins, and ambiguous language switches still surface an error.
The native Nemotron adapter also treats multiple locale tags with the same
primary language as one source language.

Validation: speech sanitizer host suite (57 checks), speech package tests (6),
shared translation regressions, `./gradlew test :app:compilePlayQaKotlin
:app:compileFossQaKotlin :common-jni:externalNativeBuildDebug
:app:assemblePlayQa :app:assembleFossQa`, and
`python3 scripts/verify-release.py` all passed. The pinned arm64 Nemotron
runtime was rebuilt and staged with 16 KiB alignment. The FOSS APK has no
network permissions. The Play QA APK installed and launched on the Samsung
SM-S908E (Android 16); its process stayed alive through the startup smoke
check. This does not establish a sustained live stream or translated-caption
latency. No paid provider checks were made.

| QA artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Play | 44,618,338 | `7a9a041f41bc3ba9b7f6844c693891e8a441fec5827b88013913c158d3902bb7` |
| FOSS | 36,693,381 | `b0963c74aa0f51ae9694f35e0cb1e3e2d1fe3e50e3ba896448a7b5cbc174ff57` |

Owner device check: with Nemotron in Auto and the Russian → Arabic Marian
cascade selected, play a Russian stream for several consecutive captions,
including captions with an English proper name. Translation should continue
on source-tag gaps. Switch to an unsupported language: its CC should stay
visible and the actual unsupported-source notice should appear. For a stable
single-language stream, forcing Russian remains the fastest explicit route.

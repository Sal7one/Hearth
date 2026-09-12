# 0.4.2 / versionCode 10 — spoken-language control and Clear

## Changes

- Tap-through is false for a new session, including upgrades that saved true.
  The live switch and recovery handle still work; tap-through is no longer persisted.
- Spoken language uses explicit per-language recognition/force capabilities and the
  actual selected model. Notes are visible beside the field in app and overlay.
  Model details also use the shared picker instead of the legacy Nemo-only chips.
  Automatic-only/fixed paths use plain text, not a disabled language list. Unknown
  cloud models no longer inherit Whisper coverage; known cloud routes use a
  conservative common-language subset, not an exhaustive provider catalog.
- Qwen supports all 30 profile languages plus Auto through the pinned runtime's
  existing per-stream language option. ISO codes are validated and converted to
  the prompt's English names; source metadata remains ISO for local translation.
- Local Whisper inspects actual GGML vocabulary rather than filenames: English-only
  weights get English, earlier multilingual weights omit Cantonese, v3 includes it.
- Clear is beside pause/settings/stop and exits held history. It discards local
  in-flight/queued recognition, pending translation and TTS; local ASR weights stay
  loaded. Cloud engines reconnect to discard prior server context and buffered audio.
  The local translator is retired/reopened after clear, so its next result can have
  a loading delay. Capture consent stays active; no additional permission prompt.
- Controls retain 48 dp targets and wrap on narrow bubbles. Diagnostic counters now
  precede captions so scrolling to the bottom leaves the newest text visible.

## Checks

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin` passed.
  120 app tests in each of six variants; 60 common tests in each of two build types.
- Both `assemblePlayQa` and `assembleFossQa` passed. Native Android debug build passed.
- Speech host suite: 43 checks passed under ASan/UBSan. Common utility host suites passed.
- Rebuilt Qwen for Android against the existing pinned source, staged the binary,
  updated SHA256SUMS and adapter/header build provenance. No model weights included.
- `python3 scripts/verify-release.py` passed for both final APKs: permissions,
  native dependency set, integrity and 16 KB alignment.
- Regression checks cover unsupported languages, actual Whisper vocabulary headers,
  invalid/truncated headers, saved tap-through migration, reset during inference,
  queued-audio discard, a fresh zero-based timeline and Stop during reset without
  leaking the native session or hanging the reset caller.

## Connected-phone observations

Samsung SM-S908E / Android 16; existing QA installation upgraded without clearing
app data. Microphone was not used for the controlled sample test.

- Nemotron's supported picker rows and adjacent model explanation were visible.
- Qwen started with English explicitly selected; no Auto-only native rejection.
- A browser played the bundled upstream JFK English sample using device-audio
  capture. Qwen produced English CC, and the installed local translator produced
  Arabic. This is an end-to-end functional check, not a speed comparison.
- Clear removed held history and returned to live empty captions. Replaying the
  sample produced fresh English/Arabic content without restarting capture consent.
- The compact bubble opened touchable with Clear, pause, settings and Stop visible.
  Height was temporarily expanded to inspect the paired text, then restored to
  Compact (160 dp). The controlled sample was stopped after testing. The phone was restored to
  Nemotron, Microphone, Auto and the existing Arabic translator, with capture stopped.
  The final compact reading
  layout showed the newest Arabic reply, and Clear returned it to empty captions.

Limits: no claim that every language/model pair has been device-tested, no latency
improvement percentage, and no new cloud-paid session test in this change. Existing
cloud transport is preserved; its Clear path uses the established start/cleanup
lifecycle. A full auditory TalkBack pass still needs a device with that service.

## Delivered artifacts

- `real-time-transiber-v10-cloud.apk`: 24,731,967 bytes,
  SHA-256 `a7efd4bc7486b1c8295846af570b268e7cf093f1f6880deca87a62b9cdea7184`.
- `real-time-transiber-v10-offline.apk`: 24,449,387 bytes,
  SHA-256 `63314688ee18c59d15036831982287adf3b87d6f556b903435b39a83fa06f1be`.

Served from `/tmp/apk-serve` at `http://192.168.100.199:8899/`.

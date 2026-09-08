# 0.2.0 optional local translation — 2026-09-08

Qwen and Nemotron now feed finalized original-language captions into an optional
local text translator. Original CC does not wait for translation. Disabling the
bridge cancels/unloads it without restarting ASR. Model replacement and transcript
clearing reject old results. Model imports require pinned publisher size/SHA-256.
See [local-translation.md](local-translation.md) for setup and runtime limits.

## Gates

The final build passed:

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :common-jni:externalNativeBuildDebug :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

- App: 107 tests in each of six variants; common JNI: 55 in each of two variants.
  Zero failures, errors or skipped tests.
- Speech host suite: 36 checks; common utility host suites: all five pass.
- Vosk loader host fixture: local symbol scope, C invocation and error paths pass.
- Bridge tests cover bounded work, stable line IDs, unknown/unsupported language,
  cancellation during loading, disabling and stale-output rejection.
- Import tests cover corrupt input, atomic publication and duplicate imports.
- Both APKs pass native hashes/provenance, JNI-only translation exports,
  dependency closure, 16 KB alignment and network permission separation.

## Real model checks

Both official Q4_K_M models were downloaded and their pinned SHA-256 verified.
The native host runner produced nonempty translations with successful exit:

| Model | Source → target | Example result |
| --- | --- | --- |
| HY-MT1.5 1.8B Q4 | English → Arabic | الطقس اليوم رائع. |
| HY-MT1.5 1.8B Q4 | Chinese → English | The weather is great today. |
| HY-MT1.5 1.8B Q4 | Russian → Arabic | اليوم، الطقس جيد. |
| Hy-MT2 1.8B Q4 | Russian → English | Today, the weather is good. |
| Hy-MT2 1.8B Q4 | English → Arabic | الطقس جميل اليوم. |

These are functional examples, not a multilingual accuracy benchmark. Q6_K/Q8_0
catalogue identities were checked against publisher metadata; those weights have
not been independently run or benchmarked here.

## Connected phone

Device operation was explicitly authorized. Tested on Samsung SM-S908E (S22 Ultra),
Android 16. The originally reported SM-S938B was not connected or retested.
HY-MT1.5 Q4 was imported through the app's document picker, verified and selected.

- Qwen captured a controlled English audio clip through Android's single-app
  capture consent for Brave. The bridge displayed Arabic translation locally.
- Turning translation off in overlay settings kept the same Qwen session running;
  replaying the clip produced original CC.
- Nemotron captured the clip through the microphone while it played on the phone.
  With the same local translator, the overlay displayed:
  “هذا اختبار لعرض الترجمات الحية. الطقس اليوم رائع، يرجى عرض هذه الكلمات على الشاشة.”
- Neither inference stage used a cloud provider or API key. Audio playback used
  the task's LAN-hosted synthetic test clip.

These combined checks used the 0.2.0 candidate before the final translation timing
display, clear-transcript cancellation and JNI error-allocation checks. The final
gated APK was subsequently installed and launched successfully with models and
configuration retained. Combined inference was not repeated after that last
installation. Capture was stopped and the original media volume of zero restored;
the app had no running services at handoff.

No long-stream latency, thermal endurance or all-language accuracy claim is made.
Only HY-MT1.5 Q4 was exercised on this phone; Hy-MT2 Q4 was exercised on the host.
Translation adds a second inference stage; its bounded queue retains CC and reports
overload or stale work instead of letting delay grow without limit.

# Hearth 0.7.0 — phone validation

Date: 2026-09-13. Samsung SM-S908E (S22 Ultra), Android 16 / API 36, arm64. These measurements are from the connected test phone, not the earlier SM-S938B crash reports.

## Local comparison

The new in-app benchmark ran three serial passes per model, retaining its loaded session between passes. Warm median is the median of passes two and three. The recording is the 3.85-second English JFK sample previously used for Moonshine validation, normalized to PCM16. Speech language was forced to English where the adapter supports it.

| Model | Load + verify | First inference | Warm median | Warm compute/audio |
|---|---:|---:|---:|---:|
| Moonshine Tiny | 0.56 s | 0.19 s | 0.15 s | 0.04× |
| Qwen3-ASR 0.6B | 5.54 s | 1.31 s | 1.33 s | 0.35× |
| Nemotron 3.5 ASR 0.6B | 1.10 s | 2.50 s | 1.63 s | 0.42× |

All three produced the expected words in each pass. Moonshine Tiny was fastest on this English clip; it is English-only and utterance-windowed. Qwen is also utterance-windowed, while Nemotron maintains streaming caches. Accelerated replay is not time-to-live-caption: these numbers do not establish a universal multilingual winner or sustained thermal behavior.

Text translation used the exact same entered input (including the trailing space): `here is the train station? I need two tickets, please. `. Source English, destination Arabic. This is a timing comparison, not a translation quality score.

| Translator | Load + verify | First inference | Warm median |
|---|---:|---:|---:|
| HY-MT1.5 1.8B · Q4_K_M | 2.13 s | 4.66 s | 4.993 s |
| ML Kit · installed packs | 0.06 s | 0.36 s | 0.036 s |

HY-MT1.5 Q4 is functional but much slower on this short input. Inspect its actual output in the raw results; fluency/inflection differences remain. ML Kit was the fastest tested translator, with already installed packs. No new HY quantization, TranslateGemma, Russian/Chinese accuracy, combined ASR+MT thermals, or cloud provider was measured in this run.

[Raw exported results with exact model/runtime fingerprints](benchmarks/2026-09-13-s22-ultra.json). Benchmark exports and input recordings were saved under Downloads/Hearth/files on the test phone. No conversation/microphone transcript is included in the repository.

## Functional checks

- Upgraded existing standalone 0.6.0 installation to Hearth 0.7.0 without resetting saved settings or imported models. Technical application/JNI identities stay stable for upgrade compatibility.
- Benchmark compared one Moonshine Tiny installation, Qwen3-ASR 0.6B and Nemotron 3.5, then HY Q4 and ML Kit. Saved results survived an APK update; JSON export succeeded.
- Traveler typed English → Arabic preserved both texts. Microphone turn produced original and translated text; Finish returned to Ready. Installed Arabic system TTS entered Speaking and finished.
- Conversation history and translations survived an APK update. Test conversation deletion was exercised; test microphone transcripts are not shipped.
- Backgrounding a listening traveler session stopped microphone app-ops activity; reopening returned to Ready.
- Dark preference survived APK upgrade; status/navigation icons and Arabic text remained readable. Downloads shows Downloads/Hearth by default; a real HTTPS file downloaded into its public files directory and was then deleted through the UI. Earlier files retain their truthful original location.
- Light-theme action text was darkened for contrast; direct URL/filename inputs disable keyboard autocorrection.
- Shared workload lease tested against concurrent caption/conversation/benchmark acquisition and stale release. Clear invalidation and final-drain edge cases have targeted unit tests.

## Gates

- `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin`: PASS. 150 app tests in each of six variants, 67 common-jni tests in each of two variants, zero failures/errors/skips.
- Speech native host suite: 50 checks PASS. No C/C++ or bundled inference binary changed.
- Both QA APKs assembled. `python3 scripts/verify-release.py`: PASS (native hashes/dependencies/16 KB alignment and flavor permissions).
- QA remains debug-signed. GitHub publication and a stable production signing identity are separate from this locally verified build. Full auditory TalkBack and multi-device/long-session coverage remain pending.

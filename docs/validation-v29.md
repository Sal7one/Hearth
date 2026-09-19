# Hearth 0.15.0 / code 29 — shared translation choices

20 September 2026. Kotlin/UI routing only; native models, runtime pins, permissions
and signing identity are unchanged.

## Scope

One reusable translator chooser, a Translation settings hub, direct local artifact
setup links, shared encrypted connections, explicit apply-across-Hearth action,
and local/cloud text translation for caption engines. Existing feature provider
choices are preserved. See [translation choices](translation-choices.md).

## Validation

The required `test`, Play/FOSS Kotlin compile and both QA assemble gates are run
centrally. Ten initial new cases covered routing, persistence and a simulated cloud
caption request; two additional cases cover retaining the source validated for the
loaded Whisper weights and allowing source hints for OpenAI/batch recognition when
an explicit text stage replaces integrated translation.

Native code/runtime pins are unchanged, so native host/inference suites were not
rerun. Shared common-JNI unit tests run as part of `test`.

Samsung SM-S908E, Android 16: installed cloud QA via ADB and checked the direct
translator entry on typed text, the camera settings sheet and the cloud setup form.
The current local model and saved LibreTranslate language capabilities appeared;
Google, Microsoft and DeepL correctly required setup. No API key was entered or
paid request made. The production queue/DeepL integration uses an intercepted HTTP
response in host tests, not a live provider quality/latency test.

App unit suites contain 236 cases per variant, zero failures/errors (one expected
conditional skip in Play, two in FOSS). Common-JNI debug/release each contain 74
passing cases. `verify-release.py` checks both APKs for flavor permissions and
16 KB native alignment. No new live billing/quota, sustained camera, thermal or
TalkBack session is claimed; existing shared pipeline/transport tests cover those
routing contracts, not model quality.

Final gates completed successfully in 1m 51s; artifact verification passed for both
flavors. The final cloud APK was installed via ADB. On-device checks confirmed the
On device / Cloud tabs, selected model ordering, all four cloud options and the
model-management link opening directly on Models → Translation. Camera settings
also showed the same chooser. The apply-across-Hearth control is present; it was not
activated on the owner's saved configuration during this smoke check. The new
caption source-routing cases and persisted provider field are covered by host tests.

These exact APKs were not submitted to a fresh browser/Play Protect scan. They are
QA test builds, debug signed. No permissions or protection settings were changed.
No APKs, model files, keys or research assets are included in the commit.

Artifacts:

- `hearth-v69-shared-translators-cloud.apk` — 35,139,447 bytes; SHA-256
  `7ae0d1c8736521a971a19bd57f958d692529bde0362bc9ebdfcffbcb6a292e0a`.

- `hearth-v69-shared-translators-offline.apk` — 27,351,309 bytes; SHA-256
  `89aac851d7de57e981e14fce011decba15eb061c5a6981dddfc42c22f1d059b1`.

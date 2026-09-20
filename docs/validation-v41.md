# Hearth 0.21.4 / code 41 — language capabilities

20 September 2026. v81 QA. No native code, model weights or inference backend changed.

## Changes and scope

See [capability inventory](language-capabilities.md). Easy setup and live captions
now share a searchable OpenAI destination picker, including explicit 2–3 letter
codes. Unknown local models and empty conversation capabilities no longer receive
invented language sets. ML Kit derives choices from its SDK; cloud text translation
and remote voices use checked connection capabilities. Paddle/Scribe metadata no
longer uses the previous small UI subsets. Connection generations prevent stale
translation discovery writes, and credentials stay scoped to their API roots.

## Automated validation

Final command passed:

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin \
  :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

Six app suites: **289 cases each**, zero failures/errors. Existing skips: one per
Play variant, two per FOSS variant. Common JNI Debug/Release: 74 cases each, zero
failures/errors/skips. Eight capability cases cover setup/preferences/request target
preservation, provider-validated code entry versus restricted models, absent model
metadata, SDK/FOSS isolation, discovered translation directions, Scribe metadata and
OCR reader selection. Two additional protocol cases cover connection identity and
server-advertised voice languages. Existing tests were updated for real FOSS limits.

Both final QA APKs pass library hashes, 16 KiB alignment and flavor permission
verification. No native host suite was required because native files did not change.
Final build log: `/tmp/hearth-v41-final-gates.log`; artifact verification:
`/tmp/hearth-v81-artifacts.log`. `git diff --check` passed before commit.

## Phone evidence

Final Play QA installed over the existing QA app on Samsung SM-S908E / Android 16.
The UI journey searched Swedish, selected it, reopened with Swedish visible and
selected, entered Cantonese code `yue`, and reopened with Cantonese selected.
Backing out retained the Local/Cloud chooser behavior. Camera's source picker found
Persian from the Arabic-script OCR catalog. The app then relaunched normally.
No cloud configuration was saved, API key changed, capture started or model downloaded.

Visually inspected screenshots: `/tmp/hearth-v81-easy-swedish.png`,
`/tmp/hearth-v81-picker-swedish.png`, `/tmp/hearth-v81-ocr-persian.png`.
The Persian screenshot also includes the device keyboard's own theme warning;
that is outside Hearth's picker. UI script log: `/tmp/hearth-v81-phone.log`.

Limits: no paid OpenAI/Scribe requests or per-language OCR accuracy tests ran.
OpenAI validates a requested code on connection; ISO suggestions are not a claim of
universal model support. Cloud discovery generation handling was reviewed and its
endpoint/protocol boundaries host-tested, not exercised against paid accounts or a
live preference-write race. TalkBack semantics remain in the shared picker; no full
spoken TalkBack session was performed. FOSS received host/build/artifact validation,
not a second device installation that would replace the user's Play QA setup.

## APKs

- `hearth-v81-language-capabilities-cloud.apk` — SHA-256 `a0be5828e2705e69252f8c7da34143d6a5f555235849bf6081aa0824a50191b9`.
- `hearth-v81-language-capabilities-offline.apk` — SHA-256 `1ea916f255f1c88e0373843fd12d8e5c9707b4c3c8594132de25e859168d27cc`.

Private draft delivery only; source remains unpushed. Push matching source and
retarget the draft before publishing matching source archives.

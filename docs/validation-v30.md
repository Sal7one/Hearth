# Hearth 0.16.0 / code 30 — Local and Cloud settings

20 September 2026. UI refactor; no JNI changes, new permissions, runtime/model
updates or credential migrations. See [settings architecture](settings-ui.md).

## Checks

Run centrally:

```
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa
python3 scripts/verify-release.py
```

Six settings tests cover FOSS destinations, local-only OCR, explicit Cloud entry
after browsing Local, preserved selected tab when returning from models, and
restored Cloud state in an offline build. Existing provider, import, navigation,
caption queue and transport tests remain in the full suite. Native inference/host
suites were not rerun because native code and runtime pins are unchanged.

## Phone checks

Samsung SM-S908E, Android 16, cloud QA installed over the existing app through ADB.
No credentials entered, connection checks, paid calls, permission changes or
Play Protect bypass. Existing Hy-MT2 selection and LibreTranslate capabilities
remained visible. No model downloads or imports were started.

- Local and Cloud root settings contain separate, readable groups.
- Cloud Translation lists only service connections; Local lists model choices.
- Local Translation scroll position was identical before and after switching tabs
  (compared the same model row's accessibility bounds).
- Model-management link opens Models → Translation with installed models and no
  caption enable/disable switch.
- Phone testing found that a saved Local tab could override a new explicit Cloud
  entry. Entry revisions and host regression cases were added to preserve scroll
  while honoring explicit navigation.

This is a setup/navigation check, not a new speech/translation-quality, sustained
memory/thermal, or TalkBack audio-session claim. QA APKs are debug-signed tests;
these APKs were not submitted to a new browser/Play Protect scan.

## Final gates and artifacts

Final required gates succeeded in 2m 21s (268 tasks: 47 executed, 221 up-to-date).
App suites contain 242 cases per variant, zero failures/errors, with one expected
conditional skip in Play and two in FOSS. Common-JNI debug/release contain 74
passing cases each. Artifact verification passed both flavors, permissions and
16 KB native alignment. Added-line credential pattern checks found no matches.

- `hearth-v70-local-cloud-settings-cloud.apk`: 35,165,971 bytes;
  SHA-256 `e5d53f9bff75e3709f57e2dcc163006c047ee81bb0b5f31dc9731d95afe94be6`.
- `hearth-v70-local-cloud-settings-offline.apk`: 27,335,837 bytes;
  SHA-256 `993bba04da7e77bcba8101a192380819ee40caf8a2f4f24051511324f48b30f7`.

Both LAN download URLs returned HTTP 200. The final cloud APK was installed on the
phone; the explicit Cloud-after-Local translation navigation regression passed,
and the active Hy-MT2 model remained selected.

Final phone navigation checks also passed explicit Local/Cloud speech entries,
translation scroll restoration across tabs and a model-management round trip,
and separate local/server voice editors. The Supertonic F1 custom default stayed
unchanged. The phone was left on the Local settings directory.

# Hearth 0.18.0 / code 32 — simple navigation and themes

20 September 2026. Scope: direct feature home, shared settings shortcuts, persisted
minimal palettes, and selectable classic navigation/appearance. No native model,
provider, audio capture, OCR or translation implementation changed.

## Gates

`./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa`, then `python3 scripts/verify-release.py`.

Each app variant has 252 host cases, zero failures/errors (Play one expected skip;
FOSS two). Common JNI debug/release each have 74 passing cases. New checks cover
light/dark text contrast, stored classic palette compatibility and navigation
return/restoration for appearance and phone shortcuts. Native inference suites
were not rerun because native/runtime inputs are unchanged.

## Phone observations

Samsung SM-S908E / Android 16, QA build installed over existing saved setup.

- Simple home displayed all six feature entries without bottom navigation.
- Face to face opened the split layout directly. Returning Home and choosing
  Conversation opened the conventional layout rather than the last split view.
- Typed translation opened directly. Its toolbar settings sheet opened the Cloud
  translation editor; Back returned to typed translation.
- Screen & manga opened reader setup directly without starting capture.
- Dark, Ink and Simple home persisted through a force-stop and cold restart.
- Light/Sky and Organic applied immediately. Classic tabs restored all five
  destinations; choosing Simple home removed the bar. Back from appearance opened
  from Home returned to Home.

These are navigation and appearance checks, not another inference benchmark. No
paid provider request, model download or newly recorded audio was used. Host color
checks and exposed UI semantics do not constitute a full TalkBack, RTL, large-font
or device-matrix audit. Existing Android permission/setup steps remain explicit.
QA artifacts are debug-signed test builds.

Final gates passed in 2m 39s (268 tasks). Both optimized QA APKs passed artifact
verification, including flavor permissions and native 16 KB alignment.
The final offline APK was installed and its quick settings sheet checked: local
speech/translation/voice/OCR entries were present with no Cloud tab. The cloud APK
was then restored with Clean, System brightness and Simple home selected.

## Delivery

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| `hearth-v72-simple-home-cloud.apk` | 35,229,735 | `05b9b01b3097718f85ff701e19f957a6f0145192c8fa8087506172ad0f214475` |
| `hearth-v72-simple-home-offline.apk` | 27,395,601 | `1440b6a3b151e02bd44d924a661c04881f3de30389649f577404cf01134017ff` |

Both returned HTTP 200 under `http://192.168.100.199:8899/`. APKs and phone captures
remain outside Git. No capture session was started by these UI checks.

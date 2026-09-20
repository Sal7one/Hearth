# Hearth 0.17.0 / code 31 — Quick Settings overlay launch

20 September 2026. Two system tiles, a local preflight/diagnostics activity, shared
setup visibility and add-tile controls. Native model/runtime code and artifacts
are unchanged. See [phone shortcuts](quick-settings.md).

## Validation scope

Required gates: `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
:app:assemblePlayQa :app:assembleFossQa`, followed by `python3 scripts/verify-release.py`.

Host coverage includes verbatim multi-failure reports, readiness, cancellation,
native loader errors, absent/truncated files, traversal and symlink rejection.
Visibility ownership tests prevent overlapping or repeated releases from exposing
an overlay before setup is finished. Existing native unit tests run in `test`;
native inference suites were not rerun because JNI/runtime inputs are unchanged.

## Phone observations

Samsung SM-S908E, Android 16. Installed cloud QA over the existing app using ADB.
Added both tiles through Hearth's buttons and Android's native Add confirmation.
Invoked them through SystemUI's tile click path.

- Screen translation passed setup using the existing Meiki/Japanese → English
  configuration and opened Android's Share screen dialog without microphone or
  camera permission requests.
- Granting consent started ReadingOverlayService as a mediaProjection foreground
  service. Existing saved settings were used; no model download was requested.
- Invoking Live captions while reading was active opened diagnostics identifying
  the conflicting overlay. Nemotron, Hy-MT2 and Russian → Arabic were recognized
  as ready in the same report.
- Invoking the running reading tile recovered its overlay controls without a
  second consent prompt.
- The open reading panel could initially cover diagnostics and intercept its
  button. Shared setup visibility was added to both overlays to address this
  observed defect without adding permissions or stopping an existing session.

No cloud API request, new credentials, permission revocation, full TalkBack
session or new transcription/translation quality benchmark was performed. Runtime
memory/thermal and provider quota are not guaranteed by setup checks. QA is debug
signed; these APKs were not submitted to a new browser/Play Protect scan.

The visibility fix was retested on the phone: Stop other overlay & retry became
tappable, stopped the reader and opened Android's audio sharing prompt. Granting
that prompt started CaptionCaptureService as a mediaProjection foreground service.
The test used the saved Nemotron + Hy-MT2 Russian → Arabic configuration with no
external audio played; it validates startup, not a new quality benchmark. The
notification Stop action shut down capture. A stale CC notification label during
engine initialization was also corrected by refreshing it after engine startup.

Cancellation was verified on the phone: dismissing Android's screen-sharing
prompt opened a compact diagnostics page with Check again & start immediately
visible and five successful checks collapsed. Neither capture service was running
after cancellation. The setup-visibility lease follows the activity's visible
lifecycle, including Home/Back, rather than only releasing at activity destruction.


On the final installed build, pressing Home from conflict diagnostics restored the
reading overlay's small recovery handle on the launcher. The notification Stop
action then ended screen capture; neither capture service remained active.

## Final gates and artifacts

All required Gradle gates passed in 2m 15s (268 tasks). Each app variant ran 249
cases with zero failures/errors: Play has one expected skip and FOSS has two.
Common JNI debug and release each ran 74 cases with zero failures/errors.
`verify-release.py` passed both APKs, including flavor permissions and native
16 KB alignment. No native/runtime inputs changed.

QA artifacts are debug-signed, version 0.17.0 / code 31. Both LAN downloads returned
HTTP 200. The cloud build is installed on the tested phone; both tiles are added.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `hearth-v71-overlay-shortcuts-cloud.apk` | 35,202,599 | `43cfeca8153426319c70f40af96513ae8d0067e11ce7af73dc928f72fb08b5ef` |
| `hearth-v71-overlay-shortcuts-offline.apk` | 27,368,829 | `758cf1da0255252c9bfda808e4ecc3fe33a1238d648714bb89bd83044d43b10f` |

Served under `http://192.168.100.199:8899/`. Build outputs and phone screenshots
remain outside Git.

# 0.3.0 — simpler app navigation and start flow

September 12, 2026. This changes app UI/navigation and capture entry, not the cloud
clients, saved-key format, local inference libraries or translation weights.

## Visible changes

- One home screen replaces the three bottom tabs and long default setup form.
  It shows audio source, CC/translation, languages and processing choice. Start
  stays in a fixed bottom action area, including when content must scroll.
- Setup groups Models, Downloads and Cloud connection. Advanced setup preserves
  detailed engine options and live cloud presets. Help contains diagnostics.
- Models uses expandable Speech recognition / Translation / Other models sections.
  Model details, source hints and larger translation variants are collapsed.
- Start passes the already-chosen source into the existing permission activity.
  Android consent is preserved; there is no second source-selection step. After a
  denial/cancellation the user can retry or cancel rather than being reprompted
  automatically.
- The service publishes its existing running state to the home screen. Show
  captions recovers an active bubble; the adjacent Stop action ends the session.
  Home setup controls are disabled while capturing, with live changes available
  in the bubble. Existing keys/models/appearance preferences are retained.
- Missing speech/key setup routes to its setup screen; missing translation allows
  original CC with an explicit notice. Settings-save errors remain visible.

## Verification

Required Gradle tests, both QA Kotlin compilations and both QA assemblies passed.
App tests: 111 per six variants; common JNI: 55 per two variants; no failures/errors.
APK verification passed for both flavors, including hashes, 16 KB native alignment,
dependency closure and offline/network permission separation. Native code did not
change, so no new native model benchmark or test infrastructure was introduced.

On the explicitly authorized connected Samsung SM-S908E / Android 16, with its
existing large display scale:

- Home displayed all current choices and Start without the old tab bar or technical
  paragraphs. Setup showed three clear destinations plus Advanced and Help.
- Models opened with its speech choices and compact details; translation and other
  model setup were reachable as separate expandable sections.
- A single Start tap using the saved microphone source created the existing
  Nemotron overlay and returned to MainActivity without a second source choice.
- Home changed to Show captions with a Stop button. That Stop action removed the
  capture service; no capture remained running at handoff.

The candidate phone check preceded small final changes to save-error handling,
translation-readiness messaging, disabling setup while running, and the bubble's
full-setup link. The final gated APK was installed afterward. Live cloud API calls,
first-time permission denial and Android 9 were not repeated on this phone. Existing
provider logic is unchanged. No claim of a faster/new translation model is made.

Owner check: open the app, select source/mode/language, press Start once; stop from
home; open Setup → Models/Downloads/Cloud connection and return with Done.

# 0.3.1 — overlay settings keep their place

September 12, 2026. Settings retain the selected section, separate Appearance and
CC & translation scroll positions, and read-aloud expansion while switching to
captions and back within the current overlay session. Section chips have 14 dp
horizontal padding, aligned with the settings content. No inference/native changes.

Required Gradle tests, both QA Kotlin compilations and both QA assemblies passed.
App: 111 tests per six variants; common JNI: 55 per two variants; no failures,
errors or skips. `scripts/verify-release.py` passed for both APKs.

Installed the final cloud QA APK on the authorized Samsung SM-S908E / Android 16.
With the existing compact 160 dp bubble and capture paused:

- Scrolled Appearance to Background 70%, switched to captions and back: the same
  control and position returned.
- Switched to CC & translation, scrolled to its language row, switched to captions
  and back: the section and scroll position remained.
- Switched back to Appearance: Background 70% remained in view.
- Verified spacing before the two section chips. Existing height and opacity
  preferences were unchanged. Stopped capture after checking.

State retention is for the current overlay composition/session, not a new capture
session after stopping the service. Read-aloud expansion retention is implemented
but was not separately exercised on the phone.

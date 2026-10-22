# Ming local TTS — Android UI proof

Device: SM-G781B, Android 13, 8 GB, `playDebug` 0.27.3, arm64.
Owner-authorized UI Automator run; no system setting was changed. Evidence:
`build/agents/ming-ui-evidence/REPORT.md` (ui*.xml, PNGs, logcat).

## Flow

Home → mode chip **Basic** → **Advanced** → Easy setup → **Open full Settings** →
**3 · Read captions aloud** → **Edit Local setup** → **Voices & read aloud / Local** →
**Voice engine: Android voices** → **Ming-3.0 TTS 0.5B · experimental**.

- Toggle **Enable experimental Ming local TTS**.
- Real runtime reason: `MingSpeechEngine.available()==true`; logcat:
  `nativeloader: Load .../lib/arm64/libming_jni.so ... ok`.
  `/proc/cpuinfo` has `asimddp`, so the dotprod library is selected.
- Bundle: 17 flat files were copied from the external test fixture into
  `files/voice-models/ming-local` as the app user. No download was used.
- Removing `part-014.bin` changed the UI to
  “1 of 17 components missing”; restoring it returned to ready.
- First synthesis materialized `parts/` as 15 symlinks (hardlinks refused on this
  filesystem), no duplicate bytes; temp WAVs were cleaned up.

## Playback (all real AudioTrack, not fabricated)

| # | Voice / language | Text | Frames | Duration |
|---|---|---|---:|---:|
| 1 | US_Emma / en | Hearth Ming local test fourty two. | 110,250 | 2.50 s |
| 2 | US_Emma / en | Ming test two quick brown fox. | 114,660 | 2.60 s |
| 3 | 灵小柔 / zh | same text | 110,250 | 2.50 s |
| 4 | US_Emma / en | Hello. Where is the nearest train station? | 127,890 | 2.90 s |

Each run used `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` +
`CONTENT_TYPE_SPEECH`. Run #2 sampled `/proc/<pid>/status`: RSS held ~1.01 GB,
peak **VmHWM 1,275,876 kB (~1.28 GB)**; ~9 s tap-to-first-audio.

## Persistence and gating

Cold restart restored Advanced mode, Ming, switch on, US_Emma and ready. The
values live in `files/datastore/hearth_user_preferences.preferences_pb`, not
shared prefs. With the flag off the panel collapses to “Enable the experimental
engine first”, so the Ming screen cannot download. Leaving settings silently
falls back to Supertonic 3.

fossDebug merged manifest = 0 INTERNET / 0 ACCESS_NETWORK_STATE; playDebug = 1
each. The installed APK is Play; **FOSS-on-device is not verified**.

## Remaining

- Read-aloud on live captions/conversation (same `VoicePlayer.speak` path, but
  no caption content was driven).
- A real FOSS APK on device.
- Per-run RTF from the UI (the engine discards the native stats JSON).
- Mid-synthesis cancel (no native cancel; not exercised).
- The global “Read captions aloud” card toggle was left off.

The ready label used to imply `parts/` existed before the first synthesis; the
string now says “created on first synthesis”. No synthesis behavior changed.

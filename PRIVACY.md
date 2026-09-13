# Privacy

The app includes no advertising or first-party analytics. Local speech recognition and local
translation process audio on the device. Local models are imported into app storage.
The offline (`foss`) build has no Internet or network-state permission and cannot
start system downloads. Models can still be imported through Android's file picker.

Cloud mode sends captured PCM audio and related transcription configuration to the
provider endpoint you choose, using your API key. Provider retention, pricing and
account policies apply. Optional cloud read-aloud sends caption text to that provider.
Use local mode if you do not want audio sent to a service. Error messages may contain
provider response details; redact these before posting public bug reports.

API keys are encrypted using Android Keystore when available. If Keystore fails,
speech keys can use a weaker app-private file-backed encryption key;
the OpenAI settings UI reports its storage mode. Each key records its own mode.
Removing one provider key preserves the encryption material required by other keys. Keys and model files are excluded from
Android backup and device transfer. Clearing app data removes saved credentials.
No credentials are included in this repository. Download requests do not receive
speech provider API keys.

New downloads use an app foreground download service. The app stores the requested
URL (including query parameters in signed links), progress and file metadata locally.
Requests may use mobile data. On Android 10+, new originals are saved under
`Downloads/Hearth/models` or `files`; a folder picker can select another location.
Android 9 requires a chosen folder. Public files remain after uninstalling; installed
models are verified copies in app-private storage. Earlier downloads retain their
original locations and may use Android DownloadManager. Any sharing/export destination
follows its own privacy policy. HTTPS is required for direct download URLs.

Overlay captions and their bounded history are held in memory during the session.
Conversation mode saves original and translated text in a local database excluded from backup;
saving can be disabled, and saved conversations can be deleted or explicitly shared.
No conversation audio is saved. System read-aloud in both captions and conversation
selects installed offline voices only; it does not silently select a different language.
Conversation and Face to face can separately send finalized text to Google Cloud
Translation, Microsoft Azure Translator, DeepL, or a chosen LibreTranslate server.
Choosing local speech does not make a selected cloud text translator local. Setup
labels this explicitly; language discovery contacts the selected server on request.
These new translation keys use a separate Android Keystore AES-GCM key with no
plaintext/file-key fallback. Provider text handling, account limits and charges
apply. No cloud text translator is available in the foss build.
Local benchmarks save results and transcripts locally, with explicit JSON export and Clear history.
Imported benchmark audio is held only in memory and is not copied into the app. Copying text uses Android's
clipboard. The capture notification stays visible and offers Stop. Playback capture
uses MediaProjection consent and microphone permission; microphone capture uses
microphone permission. No camera, contact or broad media-library permissions are requested.

Caption text and provider response bodies are not written to routine caption logs.
Local troubleshooting stores the most recent speech startup stage, timestamp and
available RAM. The Copy report action includes device/build and Android process-exit
metadata. Optional trace export saves an Android-provided crash/ANR trace, which may
contain process and file-path details, to the destination you choose. No report or
trace is uploaded automatically.

The play build includes the optional Google ML Kit translation SDK. Caption text
is translated on-device. Explicit language-pack downloads contact Google on Wi-Fi;
ML Kit manages its own app-private model files and can collect SDK/device usage
and diagnostic data under Google’s terms. This is distinct from the app’s direct
file downloads. The foss build excludes ML Kit and its native library.
See [ML Kit data disclosure](https://developers.google.com/ml-kit/terms).
Soniox and ElevenLabs are optional cloud providers subject to their own policies;
Soniox integrated translation sends audio through the selected cloud connection.

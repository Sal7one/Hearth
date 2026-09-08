# Privacy

The app includes no analytics or advertising SDK. Local speech recognition and local
translation process audio on the device. Local models are imported into app storage.
The offline (`foss`) build has no Internet or network-state permission and cannot
start system downloads. Models can still be imported through Android's file picker.

Cloud mode sends captured PCM audio and related transcription configuration to the
provider endpoint you choose, using your API key. Provider retention, pricing and
account policies apply. Optional cloud read-aloud sends caption text to that provider.
Use local mode if you do not want audio sent to a service. Error messages may contain
provider response details; redact these before posting public bug reports.

API keys are encrypted using Android Keystore when available. If Keystore fails,
the inherited key store can use a weaker app-private file-backed encryption key;
the settings UI reports the storage mode. Keys and model files are excluded from
Android backup and device transfer. Clearing app data removes saved credentials.
No credentials are included in this repository. Download requests do not receive
speech provider API keys.

Downloads are handled by Android DownloadManager. The system stores the requested
URL (including query parameters in signed links), progress and file metadata.
Requests may use mobile data; roaming is disabled. Files remain in app-specific
external storage until deleted or the app is uninstalled. Export creates a separate
copy in your chosen document provider. Any sharing/export destination follows its
own privacy policy. HTTPS is required for direct download URLs.

Captions and their bounded history are held in memory during the session; no audio
recording or persistent transcript database is created. Copying text uses Android's
clipboard. The capture notification stays visible and offers Stop. Playback capture
uses MediaProjection consent and microphone permission; microphone capture uses
microphone permission. No camera, contact or broad media-library permissions are requested.

# Owner device checks

Host builds and tests cannot prove microphone, MediaProjection or overlay behavior.
Test this standalone app separately from Hearth; both can be installed together.

1. Fresh install: launch, deny each permission once, then grant it and start successfully.
2. Save a cloud key; restart the app; verify mode and key remain available. Remove the key.
3. Play a Russian and Chinese stream that permits audio capture. Check live Arabic and
   English translation, pause/resume, hide/show and stop from the notification.
4. Drag the bubble to all four edges; rotate the device; resize it; enable tap-through;
   tap the notification body to recenter and recover controls.
5. Read grey previous captions and scroll history while transcription continues.
6. Import a Whisper file and Vosk folder; verify offline captions. Import prepared
   Qwen/Nemotron ZIPs, verify both appear in the engine picker, and test start/stop/restart.
7. Download a direct HTTPS file; leave the app; return and check progress/completion.
   Export it, open it and import it if it is a compatible model. Cancel another download.
8. Install the foss QA APK (same QA application ID, so switch builds rather than running
   both at once). Confirm no cloud/download controls start network work; test local captions.
9. Start a second time after stopping capture; confirm there is one notification/session.
10. Verify TalkBack labels, larger system font, right-to-left Arabic and long transcript lines.

Record raw errors, model/profile, source language, device/Android version and steps.
No device checks were performed by the extraction agent.

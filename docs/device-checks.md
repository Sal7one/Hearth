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

## Local startup investigation (0.1.1)

Import Qwen and Nemotron ZIPs using Models → Import model file or speech ZIP, as
well as the per-engine import button. Both should select the imported local engine.
If the process closes on Start, reopen the app and tap **Copy startup / crash report**
before starting another engine. Paste that report with the APK name and whether the
last visible step was permission consent, loading, or first captions. The report
contains device/build, the last local initialization stage, RAM and Android's recent
exit reasons. It contains no captured speech or provider credentials. Android 11+
provides exit records; Android 9/10 still show the persisted startup stage.

If Android retained a native/ANR trace, **Export Android crash trace** saves its
original bytes through the file picker. This may be a binary tombstone rather than
readable text; attach the exported file for analysis. Nothing is uploaded automatically.

## Camera translate (0.9.0)

- Download and install one OCR group; check originals in Downloads/Hearth/models.
- Import a photo without granting camera permission; verify Original and Translation.
- Grant camera permission, Start live, hold a printed sign steady, Capture & translate,
  Retake, Stop and leave/reopen the page. Confirm Stop releases the native workload.
- Check Arabic reading order and numbers, Chinese/Japanese and Russian with their
  matching readers. The first release handles level horizontal text, not perspective
  or vertical layout. Test actual signs and menus; synthetic fixtures are insufficient
  to establish broad OCR accuracy.
- Check local behavior in airplane mode after model/language-pack installation.
- For cloud text translation, verify selected direction, source preservation on error,
  and that only recognized text is sent. Test large fonts, TalkBack and rotation.

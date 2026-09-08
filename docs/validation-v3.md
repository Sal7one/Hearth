# 0.1.2 native startup fix — 2026-09-08

## Root cause and change

Reproduced 0.1.1 Qwen startup SIGABRT on the explicitly authorized connected
Samsung SM-S908E (S22 Ultra), Android 16. Android reported `Pointer tag ... was
truncated`. The symbolicated stack runs through Qwen's ORT environment creation,
DeviceDiscovery logging and C++ locale cleanup, before model session creation.

The bundled libvosk.so statically embeds and exports libc++ symbols. common_jni
had a DT_NEEDED dependency on Vosk before libc++_shared. Its runtime definitions
contaminated the native dependency scope. VoskEngine now resolves its C functions
through a lazy RTLD_NOW | RTLD_LOCAL loader. No C++ objects cross that boundary;
the successful library remains loaded for process life. Loader errors reach the
existing initialization error path verbatim. Vosk logging is no longer suppressed.
The APK guard rejects any direct common_jni dependency on libvosk.so.
No model weights or Qwen/Nemotron binaries changed. Pointer tagging stays enabled.

## Verification

- Gradle test and both QA Kotlin compilation gates passed.
- App: 106 tests in each of six variants; common JNI: 48 in each of two variants;
  zero failures/errors.
- Speech native host suite: 36 checks; common host utility suites: all five pass.
- New Vosk loader host fixture: C function invocation, local symbol scope,
  missing-library error and missing-symbol error pass.
- externalNativeBuildDebug and both QA assemblies passed.
- verify-release.py: hashes, native dependency closure, 16 KB alignment and
  offline/network permission separation passed for both APKs.

## Device evidence

User explicitly authorized ADB/device operation for this investigation.
The connected SM-S908E differs from the originally reported SM-S938B; the latter
has not been retested here. Both existing ZIPs were copied to Downloads over USB
and imported through Models > Import model file or speech ZIP, successfully
selecting the imported engine. No private model directory manipulation was used.

- Qwen: reproduced old crash; fixed build starts microphone overlay. Final 0.1.2
  starts after fresh install, captures Brave playback with Android's single-app
  consent, and transcribes the full controlled English test sentence. Previous
  text remains gray; pause/resume and dragging were exercised.
- Nemotron: fixed native code starts microphone overlay and transcribes the same
  browser-played sentence correctly. Switched from Qwen in the same process
  without crashing. Final 0.1.2 has the same native code as that test build.
- Test audio: synthetic speech, “This is a test of live captions. The weather is
  pleasant today. Please show these words on the screen.”

These are short functional checks, not a long-stream latency benchmark or a
multilingual accuracy evaluation. Cloud translation and Vosk model inference were
not retested. The Vosk C loader is covered by the host fixture and APK guard.

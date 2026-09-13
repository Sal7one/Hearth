# Add a speech model, translator or cloud provider

Keep changes behind the existing engine contracts. Do not edit capture, overlay
geometry or key encryption to add a model. Model capabilities describe what our
specific adapter accepts, not everything the model paper claims.

## 1. Another artifact for an existing family

ASR: add a profile to `common-jni/.../speech/SpeechModels.kt`, required roles to
`SpeechModelPackage.kt` and `scripts/speech/make-package.py`, and its display mapping
in `app/.../caption/LocalSpeechModels.kt`. Start with the Moonshine Tiny profile.
Declare languages and windowed/cache-aware behavior. Keep root manifests, role
validation and per-file digests. Test missing files and an unsupported language.
A new file extension is not a new runtime. Models cannot supply executable plugins.

Translation: add an exact revision, SHA-256, byte size, language directions and
license to `TranslationCatalog.kt`. Existing families use the same verified import,
Downloads install flow, model picker and session lifecycle. Do not guess a GGUF’s
compatibility from its name. Keep family prompts separate and caption text as data.

## 2. A new local runtime or model family

ASR example: `common-jni/src/main/cpp/speech/backends/qwen_backend.cpp` exports the
Qwen and Moonshine backend tables. `speech_session.cpp` resolves the corresponding
entrypoint and validates configuration. The existing `SpeechRuntime` and
`LiveSpeechProcessor` handle package verification, bounded PCM, reset and ownership.
Keep offline utterance decoding labeled as windowed even when used with live audio.

Native translation example: `translation/text_model.h` explicitly selects HY or
Gemma architecture and turn tokens; `TranslationCatalog.prompt` supplies the
family’s language instructions. Verify a real pinned artifact with
`scripts/translation/build-runtime.sh host`, then rebuild/stage Android. Do not
reinterpret an incompatible file as an empty successful result.

Managed translation example: `app/src/play/.../translation/PlatformTranslation.kt`
implements `CancellableTextTranslator`. Its foss counterpart excludes the SDK.
`TranslationOptions` feeds model/setup language coverage. `CaptionTranslationBridge`
serializes final captions, limits queued work, cancels old generations and attaches
results to the original caption ID. Keep downloads explicit and inference local.

Rebuild commands and pinned source hashes are in `scripts/speech/` and
`scripts/translation/`; run their host checks, stage native binaries and provenance,
then the normal Gradle and release verification gates in AGENTS.md.

## 3. A new cloud protocol

Start with `ElevenLabsStreamingClient` for source-only partial/final callbacks, or
`SonioxStreamingClient` + `SonioxTranscript` for separate original/translation runs.
`CloudCaptionUpdate` carries identity, revision, language, kind and finality. Never
invent one-to-one correspondence between source and translated token streams.

Add a `CloudConfigStore.SttMode`, its encrypted key accessors and setup control,
then wire construction in `CaptionEngineController`. Update `CaptionLanguages`,
`CaptionTranslationRoute` and Home readiness together. Provider language hints can
be soft guidance; do not describe them as a forced monolingual decoder.
`StreamingCloudEngine` owns bounded queues and rejects callbacks after release.
Cloud clients and download delegation must check `ByokPolicy.FEATURE_BYOK`.

Tests: Soniox interim replacement and final grouping, Scribe metadata deduplication
and raw error preservation, route precedence and language choices. A parser test is
not a paid-account check: record live-account verification separately in validation
notes. New providers stay under existing Setup, without another permanent tab.

## 4. A cloud text translator

Use `CloudTranslationProtocol` for request/response and language discovery behavior,
then wire the provider in the existing cloud translation configuration, credential
store and connection screen. Reuse the cancellation-aware HTTP adapter and its
bounded response handling; reject redirects so credentials cannot move hosts.
Advertise supported source/target directions, preserve the original text on failure,
and check `ByokPolicy.FEATURE_BYOK` inside the client. Add protocol/HTTP tests for
translation, malformed responses, auth, cancellation and offline rejection.
See [connection setup](conversation-cloud-translation.md). Live paid-account checks
remain separate from mock-server tests.

## Optional native-runtime rebuild prerequisites

Normal app builds use the included runtimes and SDK CMake 3.22.1. Rebuilding the
Qwen/Moonshine runtime additionally requires CMake **3.26 or later** on PATH and
Ninja. Set the NDK explicitly on Linux or nonstandard SDK installations:

```sh
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
export NINJA="$(command -v ninja)"
bash scripts/speech/build-runtimes.sh all android
bash scripts/translation/build-runtime.sh android
```

Use the staging scripts only after a successful build. They record source hashes,
licenses and binary provenance; never update hashes to bless a stale binary.
Host smoke tests that decode real media additionally require `ffmpeg` on PATH;
FFmpeg is not included in the app. Keep downloaded build sources under ignored
`build/`, never in the public source archive.

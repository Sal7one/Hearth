# Bundles, Advanced mode and portable setups

Hearth keeps its existing appearance and its independent speech → optional text
translation → optional voice pipeline. Basic mode uses a small bundle gallery;
Advanced exposes the full catalogs, engine controls and tuning. Changing the UI
mode does not replace a model or reset other settings. The same persisted mode
applies to the main app and its setup screens, including the full TV interface.

## Basic setup

Open **Presets** from Home and choose captions or translation, then set the
spoken or source language. Hearth shows the exact checkpoints for that choice.
Captions include language-specific Moonshine, multilingual Phonon and streaming
Nemotron where supported. Translation offers a direct Marian or QuickMT model
for supported pairs and a broader route for more languages. Translation-only
setup leaves speech alone. Local presets are unavailable on Android TV.

The preview resolves actual installed models and verifies their identity. It
shows missing assets before downloading or applying anything. Play downloads
reuse the pinned catalog and existing queue; FOSS provides document/folder import
without network or system-download delegation. Downloads continue if you leave
the preview. Select **Apply** when all required models are ready. Portable JSON
imports also require an explicit Apply. Active speech/reading/benchmark work
must stop first.

**Measured speed** in Models separates reference tests from Benchmark lab
results saved on the phone. The reference filter respects the current language
and exact checkpoint. Phone results compare completed runs on the same input
and timing protocol in the latest eligible batch. The Mac library and starters
show CPU timings with the test workload, including the
[same-clip Moonshine and Phonon replay](benchmarks/2026-11-10-m4-pro-speech-replay.json).
Native replay measures compute after the audio is available. Capture, caption
windowing and display add separate delays.

Advanced retains model family/checkpoint/quant choices, custom imports, GPU
backend controls and utterance-window tuning. Typed text keeps language, input,
translation and playback direct; detailed provider selection and raw runtime
diagnostics belong to Advanced. Engine errors and explicit CPU retry remain
visible in either mode.

## Sharing a setup

The setup card can import a JSON recipe or capture the current configuration.
Choose which stages to include, review the concrete stages and required models,
then save or apply. People can assemble a custom configuration in Advanced and
export it. This is a versioned data recipe with three existing pipeline stages,
not a general executable graph or a new inference engine.

Importable examples: [English → Arabic captions](examples/hearth-en-ar-setup.json)
and [French → English text only](examples/hearth-fr-en-text-setup.json). The
second leaves speech and voice preferences alone. Both reuse pinned catalog
references. The caption example is:

```json
{
  "schemaVersion": 1,
  "name": "English to Arabic captions",
  "speech": {
    "model": {"family": "whisper", "catalogId": "whisper-base-q5_1"},
    "sourceLanguage": "en"
  },
  "translation": {
    "modelId": "marian-en-ar",
    "sourceLanguage": "en",
    "targetLanguage": "ar",
    "enabled": true
  },
  "voice": {"enabled": false, "choice": "SYSTEM", "gender": "ANY", "volume": 100}
}
```

Custom speech references identify a model family and SHA-256 file/tree digest,
plus the profile required by packaged speech engines. They do not depend on
another phone's random installation ID. Missing custom bytes must be located or
imported on the receiving device. Catalog references resolve against Hearth's
existing pins, never a URL supplied in JSON.

Files are bounded to 16 KiB, versioned and limited to the declared shallow schema.
Unknown fields, unsupported types and invalid model/language references fail
visibly. Model files, provider credentials, endpoints, device paths and GPU
preferences are excluded. References to cloud stages require a separately saved
connection on the receiving device and remain subject to the FOSS policy.

A speech recipe without enabled translation selects captions and disables the
caption text-translation stage. A translation-only recipe leaves STT alone. An
omitted voice stage preserves the existing voice preference. Preview notes make
these effects explicit before Apply.

## Preferences and model locations

Appearance/navigation, conversation, typed text, reading, camera, voice,
translation, connection and encrypted-key settings migrate to DataStore without
changing the existing Keystore encryption. The download-folder choice migrates
too; download jobs, the model index and diagnostics remain operational records.
Existing caption geometry/configuration and speech tuning already use DataStore.
The compatibility adapter preserves immediate reads/listeners and durable
`commit` acknowledgements, coalesces slider drafts, and surfaces disk errors.

Imports keep immutable app-private copies for native inference. Hearth records
the chosen document URI and retains its access grant when the provider permits
it. The Application starts one shared asynchronous audit for each process,
including shortcut, reading and service entry points. Launch checks discover existing model copies, check local file metadata and
saved grants off the UI thread, and show missing/changed files with repair
actions. They do not hash every gigabyte or contact document providers on launch.
The ordinary pinned/full integrity checks still run before model use.

Advanced model sources distinguishes three facts: private-copy availability,
retained source permission, and an explicit check that opens the original
document. A retained grant alone does not claim that the original file exists.
Losing the original does not invalidate an intact verified private copy. Moving
or replacing files is recovered through the ordinary verified import flow.

## Validation

Production consumers have host tests for bundle language/size/stage isolation,
portable schema and resolution, typed DataStore migration, durable writes and
failure/retry, concurrent edits and coalescing, and model-path containment,
missing/changed files and symlink rejection. Android GPU and end-to-end UX
measurements belong to the owner; no device is driven by these host checks.

Final host/build gate (19 October 2026): `test`, both QA Kotlin compilations,
`common-jni:externalNativeBuildDebug` and both QA APK assemblies passed in
2 minutes 14 seconds. Each QA distribution passed 566 app tests. Common/speech
ASan/UBSan suites and translation/loader host checks also passed. The final APK
verifier passed all ten splits/universal artifacts, including native source and
binary hashes, exact private exports, optional OpenCL declaration, process
Application, alignment and FOSS permissions. ARM64 artifacts are 82.8 MiB Play
and 75.0 MiB FOSS, debug-signed, with model weights downloaded/imported separately.

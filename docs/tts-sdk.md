# Embedding Hearth article speech

Hearth's reusable API is `:hearth-tts`. It contains article splitting, voice
selection, bounded PCM delivery and lifecycle handling. It contains no model,
network client, Android audio player, app settings or automatic download code.
The 0.28.0 build prepares one `hearth-tts` AAR containing the core and every
adapter, plus the shared `hearth-onnx-runtime` AAR. This is not a Maven publication
or an Android system TTS service.
First-party source follows the repository's [Apache-2.0 license](../LICENSE);
model and native dependency licenses remain separate.

## Dependencies

Inside this repository:

```kotlin
dependencies {
    implementation(project(":hearth-tts")) // core + all adapters
    implementation(project(":hearth-onnx-runtime")) // shared ONNX runtime
}
```

For local AAR integration in another Android app, include **both** AARs and
`org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1`. Local AAR files do not supply
Gradle transitive dependency metadata. This source uses Kotlin 2.2.0, Java 17,
compile SDK 36 and minimum Android API 28. Native bindings remain in
`com.sal7one.common_jni`; retain the AAR's consumer ProGuard rules. Do not copy
or rename JNI classes, or package a second conflicting native library.

`The merged AAR carries Supertonic, Magpie, OmniVoice, MOSS, Oute, Chatterbox and
Ming adapters. All TTS native code is **ARM64-only**; the AAR contains both the
JNI wrappers and the prebuilt family runtimes. The separate
`hearth-onnx-runtime` AAR owns the one shared ONNX Runtime 1.20.0 binary set.
The ONNX runtime contributes its actual native footprint; this is not a claim
that ONNX itself is tiny.

When combining multiple optional native AARs, the consuming app can need an
explicit rule for their matching NDK27 `libc++_shared.so` copies:

```kotlin
android {
    packaging {
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}
```

Use the same compatible NDK C++ runtime for the merged TTS AAR. ONNX is supplied
once by the shared `hearth-onnx-runtime` AAR; `hearth-tts` does not bundle another
copy. `python3 scripts/verify-tts-aars.py` checks the two actual release archives,
including the merged native inventory, no model weights, and absence of app/STT/MT
classes. Verified archive sizes are in the [validation record](handoff/validation.md#sdk-and-product-boundaries).

## Installing a model

The consuming app owns explicit downloads/imports, consent, storage locations
and policies. No library call downloads models. Keep weights in app-private
storage or copy a verified SAF import there. Public model hosts must not receive
provider credentials. Preserve each model's terms and attribution.

For Magpie, use `MagpieCatalog.weights` and the exact pinned generator/codec in
that catalog. Use `MagpieModels.importWeight` for each local component, and
`MagpieModels.installTokenizerSegments` for the exact authenticated 31 MB
`tokenizer.ranges` file. The explicit developer fetch script is
`scripts/voice/fetch-magpie-test-assets.py`; it is not called by Gradle or the SDK.
The app's downloader demonstrates exact HTTPS range validation. Required files are:

```text
<modelDirectory>/magpie_tts_multilingual_357m.v2607.f16.gguf
<modelDirectory>/nemo_nano_codec_22khz_1.89kbps_21.5fps.decoder.f16.gguf
<modelDirectory>/tokenizer/<the ten final pinned tokenizer files>
```

`MagpieModels.ready()` is a cheap presence/length check for UI. Call `verify()`
before activation. The engine verifies full hashes again before creating its
first native handle. It retains one verified model across article chunks.
Check `MagpieSpeechEngine.available()` before offering installation or playback;
the current native runtime requires an ARM64 process. The core itself has no
such ABI restriction. The Hearth app additionally blocks local models on TVs.

## Reading an article

```kotlin
val reader = ArticleReader(MagpieSpeechEngine(modelDirectory))
try {
    reader.read(
        text = articleText,
        language = "ar", // bare Arabic maps to ar-MSA; en is also supported
        voice = VoicePreference(id = "Aria"),
        options = SpeechOptions(rate = 1f),
    ).collect { event ->
        when (event) {
            is ArticleSpeech.VoiceSelected -> showSelectedVoice(event.resolution)
            is ArticleSpeech.Audio -> playPcmWithBackpressure(event.pcm)
        }
    }
} finally {
    withContext(NonCancellable) { reader.closeAndJoin() }
}
```

`showSelectedVoice` and `playPcmWithBackpressure` are consuming-app functions.
Supply Android audio focus, one continuous player across frames, playback rate,
volume and lifecycle controls. Each PCM frame is finite mono float audio, with
its own sample rate and no more than one second of samples. Do not enqueue an
unbounded number of frames. Hearth's `VoicePlayer.streamArticle` is a production
example using one `AudioTrack` across article chunks.

The flow is cold, accepts one article per reader, and propagates model errors.
Normal completion means the article finished. Cancel the collection and call
`reader.stop()` for an explicit stop; await `closeAndJoin()` before releasing an
engine reservation or replacing the model. Cancellation may wait for native
initialization or the next PCM callback. Closing twice is safe. Do not block the
main thread waiting for native cleanup.

For Magpie use native `SpeechOptions(rate=1f)` and apply a user's playback-speed
preference in the player, as Hearth does. Magpie exposes Aria, Jason, John, Leo
and Sofia with **unknown gender metadata**. A strict request for a verified male
or female voice must fail rather than guess from a name. Supertonic supplies
verified `M1`–`M5` and `F1`–`F5` for Arabic/English:

```kotlin
VoicePreference(id = "M1", gender = VoiceGender.MALE)
VoicePreference(id = "F1", gender = VoiceGender.FEMALE)
```

Fallback is opt-in through `VoiceFallback.ALLOW_SAME_LANGUAGE`, with a reason
returned to the caller. It never authorizes replacing Arabic with English.
Ming's hosted voices are separate app connections and do not add server/Python
dependencies to this local SDK. See [the implementation handoff](handoff/tts-provider-implementation.md)
for API internals, tested cancellation cases and the remaining provider work.

Hearth also supplies [a Magpie voice setup](examples/hearth-ar-en-magpie-voice-setup.json)
and [a Supertonic male voice setup](examples/hearth-ar-en-male-voice-setup.json).
Install the required model components in Voices first, then import and preview
the setup. These examples select the voice stage without replacing STT or MT.
They enable caption read aloud as well as choosing the shared local voice. They
contain no weights, server addresses, keys or executable workflow nodes.


## OmniVoice experimental provider

`:hearth-tts-omnivoice` supplies a separately packaged CPU ARM64 runtime,
with no model/network/app dependency. Install the two exact pinned GGUF files
from `OmniVoiceCatalog.assets` using `OmniVoiceModels.importWeight`, call
`verify()`, and create `OmniVoiceSpeechEngine(directory)`. The package is
945,284,608 bytes; model files are not in the AAR. Check `available()` before
installation or playback. Arabic maps to the upstream Standard Arabic `arb`
language ID; regional Arabic tags currently request Standard Arabic.

Use `VoicePreference(id="auto")`, `"design-female"` or `"design-male"`.
Design IDs request an attribute and do not claim a fixed speaker or verified
gender. Strict gender preferences fail. Voice identity can vary across article
parts. Native rate is 1.0; apply speed in playback. The pipeline emits bounded
24 kHz mono float PCM, polls cancellation between native chunks and preserves
callback exceptions. Host real-model smoke exercised Arabic/English PCM,
cancellation and callback errors; S25 latency, quality, heat and memory remain
owner measurements. The generator is noncommercial and the Higgs codec uses
Boson Community plus incorporated Meta Llama 3 terms. This provider is not an
unrestricted commercial default for the Arabic-app SDK.

## OuteTTS 1.0 experimental provider

`:hearth-tts-oute` depends only on the core API and packages an isolated ARM64 CPU
runtime. Use `OuteCatalog.assets`, `OuteModels.importWeight`, `verify()` and
`OuteSpeechEngine(directory)`. Install **both** `oute-tts-1b.bin` and
`en-female-1-neutral.json`: 1,438,441,535 bytes total. The `.bin` is ChatLLM's
GGMM format containing the Q8 generator and IBM DAC speech **v1.0** codec;
GGUF files and Oute 0.3 WavTokenizer models are incompatible with this adapter.
No model, reference profile, downloader or network client is packaged in the AAR.

The pinned English reference supports the tested English and cross-lingual Arabic
paths. `VoicePreference(id="EN-FEMALE-1-NEUTRAL")` selects it; gender is unknown
for strict matching. Regional Arabic tags use the same cross-lingual path.
Generation finishes before bounded 24 kHz mono PCM delivery begins. Utterance
chunks are limited to 180 codepoints; reaching token/context limits throws an
error instead of publishing truncated speech. Native rate is 1.0, with speed
applied by the consuming player. CPU graph cancellation is cooperative;
cancelled native sessions are recreated before reuse. Callback exceptions retain
their Java identity, and close retires the handle before releasing model memory.

This is a **slow experimental option**, available under More engines in Basic
settings and directly in Advanced. The recorded two-thread desktop runs took
35.073 seconds for 3.080 seconds of English audio and 38.656 seconds for 4.146
seconds of Arabic audio. These are host observations, not phone performance or
Arabic intelligibility scores. See [the smoke receipt](research/oute-native-smoke-2026-10-20.json).
The model uses **CC-BY-NC-SA-4.0 plus Llama 3.2 Community License** terms;
its DAC codec uses CDLA-Permissive-2.0 and the reference source Apache-2.0.
The MIT ChatLLM/ggml runtime licenses remain separate from downloaded model terms.
This provider cannot be an unrestricted commercial SDK default.

To reproduce the optional runtime, clone `https://github.com/foldl/chatllm.cpp`
under the ignored `research/tts-expansion/chatllm.cpp`, check out
`74b8237a4c1796f76bb0ed7f483b8e52e7d4d5c7`, then run
`bash scripts/voice/build-oute-runtime.sh android`. The script checks the pin and
tracked source cleanliness, prepares the explicit cancellation/sampler patches
outside the checkout, and records the staged binary plus build-source hashes.
`bash scripts/voice/run-oute-host-tests.sh` is offline and needs no weights.
For explicit real-model reproduction, build the `host` runtime, fetch the pinned
fixtures with `python3 scripts/voice/fetch-oute-test-assets.py`, then run
`bash scripts/voice/run-oute-host-smoke.sh`. That developer fetch uses exact HTTP
ranges and full final hashes, and is never called by Gradle or the SDK.

## MOSS Nano experimental provider

`:hearth-tts-moss` uses the core API and shared `:hearth-onnx-runtime` AAR,
without STT/translation binaries. Install the14 pinned `MossCatalog.assets`
(717,868,602 bytes) with `MossModels.importWeight`, verify the package, and
construct `MossSpeechEngine(directory)`. No SDK call downloads files.
`MossCatalog.voices` lists18 actual reference IDs; default `Ava` and every
other reference have UNKNOWN gender. The SDK exposes19 publisher-enumerated
languages including Arabic/English; owner listening remains outstanding.

The native frontend uses real SentencePiece and cached generation/codec state.
Incremental stereo48kHz codec output is explicitly averaged into mono; the
adapter flushes .48-second frames rather than accumulating an article. Raw text
has no WeText number expansion and custom reference encoding is not implemented.
Each120-character part is capped at30seconds; absence of EOS at the bound is an
error. Native rate1, playback speed belongs to the consuming player. Active ORT
calls are cancellable, and failed/cancelled sessions are recreated. See the
[actual Arabic/English JNI receipt](research/moss-native-smoke-2026-10-20.json).
The model/source is Apache2.0; private SentencePiece/JSON/ORT notices ship with
the runtime. These remain experimental CPU results, without phone/GPU claims.

## Chatterbox Multilingual V3 experimental provider

`:hearth-tts-chatterbox` needs the core plus the shared ONNX AAR. Check
`ChatterboxSpeechEngine.available()` before installation or playback. Its current
native frontends expose Arabic/English and one pinned `default` reference with
UNKNOWN gender. It does not import arbitrary voice-cloning references. Other
upstream languages/frontends are not silently treated as tested native support.

Use `ChatterboxCatalog.assets` and `ChatterboxModels.importWeight` for the exact
V3 Q4 graph/data/tokenizer/reference files. Paths such as `onnx/embed_tokens.onnx`
are relative to the model directory. The LM import verifies the raw publisher
SHA, removes only three absent optional inputs from each of30attention nodes,
and verifies the final `onnx/language_model_q4.ort20.onnx` SHA. This is a bounded
protobuf conversion with no Python or ONNX parser dependency. The other engines
continue sharing the existing ONNX Runtime1.20.0.

The required watermark is an additional explicit source component:
`ChatterboxPerth.SOURCE_URL`, `SOURCE_BYTES` and `SOURCE_SHA256`. Download/import
it yourself, then call `ChatterboxPerth.install(sourceFile, modelDirectory)` from
a worker. This exact stored-ZIP converter validates every tensor and output hash,
never executing pickle, and produces `perth-encoder.bin`. `ready()` and `verify()`
require both the whole model and watermark. Total source download1,593,714,936B;
final installed files1,593,672,348B. No weights/checkpoint are included in the AAR.

Create `ChatterboxSpeechEngine(directory)` and select `VoicePreference(id="default")`.
The full-case NFKD frontend preserves case, crops the reference to6seconds and
uses the exact V3 token positions/cache shape and960-sample ending trim. Greedy
Q4 has a500-token/20second bound per120-character part. The waveform is decoded
and watermarked before bounded24kHz mono delivery begins; native cancellation
interrupts ORT and checks during Perth processing. Native rate1; playback applies
saved speed. No raw unwatermarked fallback is exposed.

[Same-runtime graph parity](research/chatterbox-native-smoke-2026-10-20.json)
produced identical Arabic/English tokens and bit-identical seeded waveform plus
Perth output. Separate ORT versions differ by one English token, so cross-version
bit parity is not claimed. [Original Torch watermark parity](research/perth-watermark-parity-2026-10-20.json)
records a1.64e-6 maximum waveform error at32kHz. None of these tests establishes
Arabic intelligibility, verified speaker gender or S25 performance. Runtime,
model/export and Perth are MIT, with real HF tokenizers/JSON/ORT dependency
notices packaged separately. Owner listening and sustained device tests remain.

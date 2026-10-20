# Embedding Hearth article speech

Hearth's reusable API is `:hearth-tts`. It contains article splitting, voice
selection, bounded PCM delivery and lifecycle handling. It contains no model,
network client, Android audio player, app settings or automatic download code.
The 0.26.0 draft provides the core AAR and optional Magpie AAR for integration
testing. This is not a Maven publication or an Android system TTS service.
First-party source follows the repository's [Apache-2.0 license](../LICENSE);
model and native dependency licenses remain separate.

## Dependencies

Inside this repository:

```kotlin
dependencies {
    implementation(project(":hearth-tts"))
    implementation(project(":hearth-tts-magpie")) // optional ARM64 CPU engine
}
```

For local AAR integration in another Android app, include **both** AARs and
`org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1`. Local AAR files do not supply
Gradle transitive dependency metadata. This source uses Kotlin 2.2.0, Java 17,
compile SDK 36 and minimum Android API 28. Native bindings remain in
`com.sal7one.common_jni`; retain the AAR's consumer ProGuard rules. Do not copy
or rename JNI classes, or package a second conflicting native library.

`:hearth-tts-supertonic` is another working adapter and the best current choice
for explicitly gendered Arabic/English voices. It still depends on the broad
`:common-jni` module, so it needs dependency extraction before distribution as
a small standalone native package. Do not assume its small Kotlin AAR includes
that dependency closure. Magpie's separate adapter depends only on the core.

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

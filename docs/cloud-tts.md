# Cloud read-aloud — 0.23.5

Checked against primary provider documentation on 2026-10-02. These are protocol
integrations, not measured voice-quality or latency rankings. No paid synthesis
request was made for this release.

## Setup

Open **Settings → Voices → Cloud → Providers**, choose a provider, enter its key,
choose a model and voice, and **Save voice setup**. **Use in caption overlay**
selects Cloud without enabling read-aloud or changing speech/translation. Enable
read-aloud in the overlay's third **Audio** tab when wanted. Preview uses the same
caption speaker and can incur provider charges.

**Local STT → optional translation → optional cloud voice** is supported. Only
finalized caption text goes to TTS. Raw microphone/playback audio continues through
the selected speech engine. Voice configuration is independent of the speech and
translation connection settings. Other manual Hearth playback buttons still use
Android or the saved Supertonic/self-hosted default; this provider page configures
the caption overlay.

## Provider research and implemented routes

| Provider | Selection and controls | Protocol / important limits |
| --- | --- | --- |
| [OpenAI](https://developers.openai.com/api/docs/guides/text-to-speech) | `gpt-4o-mini-tts`, `tts-1`, `tts-1-hd`; model-specific voice list, speed; style instructions for mini TTS | Bearer key; `/v1/audio/speech`; MP3. Legacy models expose six voices. Provider voices are optimized for English; Arabic quality needs listening tests. |
| [OpenRouter](https://openrouter.ai/docs/guides/overview/multimodal/tts) | Load live models whose output modality is `speech`; known Google/OpenAI voice lists, editable other voice IDs and model-page links | Bearer key; `/api/v1/audio/speech`; raw MP3, not JSON. Send speed only for OpenAI models. Explicit provider-default option omits voice; unsupported defaults produce the provider error. |
| [Google Gemini](https://ai.google.dev/gemini-api/docs/speech-generation) | 3.8 Flash-Lite / Flash TTS, 3.1 Flash preview, 2.5 Pro preview; 30 prebuilt voices, manual voice IDs; 3.8 style metadata | `x-goog-api-key`; 3.8 Interactions API, inline WAV in `steps[].content[]`. Legacy `generateContent` returns PCM24k/mono/16-bit, wrapped once as WAV. Language is inferred from text; Saudi-accent quality is unverified. |
| [ElevenLabs](https://elevenlabs.io/docs/api-reference/text-to-speech/convert) | Load [TTS-capable models](https://elevenlabs.io/docs/api-reference/models/list) and paginated [account voices](https://elevenlabs.io/docs/api-reference/voices/search); advertised gender labels and explicit overlay gender IDs | `xi-api-key`; `/v1/text-to-speech/{voice_id}`; MP3. [Speed is 0.7–1.2](https://elevenlabs.io/docs/overview/capabilities/text-to-speech/best-practices). No retired sample voice ID is assumed. |
| OpenAI-compatible | HTTPS endpoint, model and voice IDs, speed | Compatibility requires the speech API, not just a chat-completions endpoint. Uses a separately scoped key. HTTP is allowed only for loopback tests. |
| [Self-hosted Hearth voice server](voices.md) | Existing Chatterbox, Qwen3-TTS and Fish Speech bridge and custom default | Separate subpage and credentials; existing protocol and native/system voice choices retained. |

The public OpenRouter discovery endpoint returned **23 speech models** during
research, including Google, Microsoft, Qwen, Deepgram, Fish, MiniMax and Mistral.
The application loads this list on demand instead of freezing those 23 entries.
Discovery is not proof that every model supports every language, default voice,
voice cloning, or paid account. Model pages remain the source for provider IDs;
Hearth surfaces refusals and unsupported options rather than switching engines.

[Google Cloud Chirp 3 HD](https://docs.cloud.google.com/text-to-speech/docs/chirp3-hd)
is a separate product with Cloud billing/IAM and its own voice API. An AI Studio
key is not presented as a Cloud credential. A direct Chirp adapter, Azure, cloned
reference-audio inputs and streaming TTS are not implemented by this release.
Gemini and OpenRouter provide the new Google and multi-provider routes now.

## Pipeline and ownership

- Cloud voice uses `cloud_voice_v1` preferences; provider and canonical endpoint
  determine the encrypted credential slot. Editing a Custom endpoint cannot send
  its old key to another address. Provider switches restore their saved voice
  configuration. Saving TTS never writes speech model, endpoint, mode or key.
- Existing legacy OpenAI-compatible voice settings/key are copied once. The old
  records remain intact; later speech changes cannot alter that copy. The known
  old OpenRouter Google+`alloy` mismatch migrates to the documented `Kore` voice. Persisted
  migration and Keystore behavior still require Android verification.
- Each queued line retains a configuration/voice/loudness snapshot. Credentials
  are resolved on the IO worker. One active utterance and three waiting lines
  bound work; excess lines are rejected visibly. Saved changes apply to future
  requests. Stop/disable/close discard queued work and cancel active HTTP/playback.
- No automatic retries or fallbacks create additional billable requests.
  Redirects are disabled. Audio is bounded to 12 MiB, JSON audio to 17 MiB,
  catalogs to 2 MiB, errors to 64 KiB and preview/captions to 5,000 characters.
  Empty, malformed and unsupported audio produce errors. Account voices load
  page-by-page, capped at 1,000 entries in the UI; advanced IDs remain available.
- MP3/WAV playback uses speech/navigation audio attributes, avoiding Hearth's
  media playback capture. Audio focus/ducking, gain, optional STT/translation
  routing and existing native ownership stay unchanged. Android/player policy
  controls media ducking; this does not add a private per-app audio mixer.
- Temporary files are deleted after playback, errors and cancellation, including
  cancellation between disk IO and player construction. Preview stops on leaving
  the page or backgrounding. Provider failures remain visible without failing STT.
- FOSS rejects speech/catalog calls before opening any connection and exposes no
  provider web links. Native libraries, speech model pins and ABI are unchanged.

## Acceptance evidence

`CloudVoiceProtocolTest` checks wire schemas, separate credential scopes,
model/voice filtering, explicit provider-default semantics, gender isolation,
provider-specific options, malformed audio, size bounds, and exact PCM/WAV bytes.
`CloudVoiceClientTest` uses local MockWebServer fixtures for Google REST replay,
headers, redirects, cancellation of a waiting request, caller responsiveness,
credential redaction, catalog bounds and the FOSS connection gate.
`CloudVoiceFilesTest` checks file removal on completion, playback error and Stop.
Existing `CaptionSpeechQueueTest` covers queue order/capacity/generation invalidation.

Early validation failures were fixed before delivery:

- `CloudVoiceClientTest > activeNetworkStopCancelsAndRedirectNeverForwardsKey FAILED`
  (`java.lang.AssertionError`): the fixture throttled incoming request bytes as
  well as the response. It now delays response headers, observes the actual
  request and verifies Stop without throttling the upload.
- `MainActivity.kt:138:131 Unresolved reference 'speakerChoice'.`
  The shortcut was reading a Flow as a config; it now reads the first snapshot
  from a coroutine before opening the selected caption voice tab.

Final build/test/packaging results are recorded in the release validation note.
No adb/device, paid cloud call, Arabic listening test, Android audio-focus test,
or provider-side latency/thermal measurement is claimed by this host session.

Owner checks: keep local STT running while selecting/configuring a cloud voice;
preview Arabic and English with your provider account; enable read-aloud in Audio;
change gender/loudness; Stop during synthesis; switch providers and confirm that
local speech and translation continue with their existing model selections.

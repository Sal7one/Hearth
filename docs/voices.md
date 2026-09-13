# Shared voices and typed translation — Hearth 0.10.0

Home → **Type to translate** gives an input field above its translation, explicit
source/target languages, swap, copy, clear, Play original and Play translation.
It uses the translation connection selected for Conversation: an installed local
model, ML Kit packs in play, or Google Cloud/Azure/DeepL/LibreTranslate. Automatic
mode waits 500 ms after typing. A single worker keeps only the newest pending edit,
rejects stale results, and reuses its translator until settings change or the page
leaves the foreground. No typed history is written to the conversation database.
The draft survives visiting a settings page within the current activity.

**Setup → Voices & read aloud** is shared by Type to translate, Conversation,
Face to face and Camera. Their Play actions use the selected engine. Captions have
an explicit **Shared voice settings** read-aloud choice, alongside Android,
Supertonic and the existing separately configured OpenAI-compatible cloud voice.
Automatic traveler speech remains optional. Starting microphone capture stops
traveler playback. Camera speech is manual, for either original or translated text.

## Available paths

| Engine | Where it runs | Languages / voice selection | Distribution |
| --- | --- | --- | --- |
| Android TTS | Phone's installed TTS service | Enumerated offline voices; language/region match, saved voice per language | Both flavors; default |
| Supertonic 3 | Hearth JNI, ONNX Runtime CPU | 31 languages including Arabic; F1–F5/M1–M5; speed and 2–12 quality steps | Both flavors; import in foss, direct downloads in play |
| Chatterbox Multilingual V3 | Your Python server | 23 languages including Arabic, discovered from the actual runtime; default or your reference voice | Self-hosted connection, play only |
| Qwen3-TTS 12Hz 0.6B Base | Your Python server | en/zh/ja/ko/de/fr/ru/pt/es/it; **no Arabic**; requires a reference WAV and matching transcript | Self-hosted connection, play only |
| Fish Speech | Your official Fish server plus Hearth bridge | Explicit language list for the checkpoint you deployed; optional reference ID | Self-hosted connection, play only |

Chatterbox Turbo/Nano are not the multilingual model. Qwen Base is not CustomVoice
and has no preset speaker. These Python routes are not advertised as native Android
ONNX downloads. Fish languages are configured by the operator because the bridge
cannot infer a trustworthy matrix from an arbitrary server URL. Unsupported pairs
fail before synthesis; no voice silently changes the requested language.

## Supertonic download and import

Select Supertonic 3 and a voice, then **Download engine & voice**. Hearth saves the
originals directly to `Downloads/Hearth/models`, or your chosen download folder,
and installs verified copies automatically. Progress/cancellation use Downloads.
No export/reimport step is needed. Selecting a different voice downloads only its
missing style; installed engine files are reused.

Offline/import users can select the four ONNX files, `tts.json`,
`unicode_indexer.json` and one or more style JSONs using **Import voice files or ZIP**.
A ZIP containing the publisher's `onnx/` and `voice_styles/` directories is accepted
without a Hearth manifest or packaging script. Each file must match the pinned
publisher asset. Unknown files, traversal, excessive files/size and invalid checksums
are rejected. README/LICENSE metadata is bounded. Partial imports are not ready
until all required engine files and the selected voice are present.

- [Publisher and weights](https://huggingface.co/Supertone/supertonic-3)
- [Pinned ONNX files](https://huggingface.co/Supertone/supertonic-3/tree/3cadd1ee6394adea1bd021217a0e650ede09a323/onnx)
- [Pinned voice styles](https://huggingface.co/Supertone/supertonic-3/tree/3cadd1ee6394adea1bd021217a0e650ede09a323/voice_styles)
- [Official implementations](https://github.com/supertone-inc/supertonic)

All files together are about 383 MiB. Runtime memory is higher and is not inferred
from download size. Code is MIT; model weights are **OpenRAIL-M**, with use
restrictions. Both notices ship in APK assets. This is not an unrestricted-model
license. Supertonic languages are:
`en ko ja ar bg cs da de el es et fi fr hi hr hu id it lt lv nl pl pt ro ru sk sl sv tr uk vi`.

The new native adapter uses four sessions in the existing public ONNX Runtime
1.20.0 library, two CPU threads, 44,100 Hz float PCM and bounded chunks. It does not
introduce another colliding ONNX library. It uses pinned tokenization/voice styles,
NFKD text preparation, shape/finite checks, duration/output bounds and the existing
lease registry. Cancellation terminates ORT runs before exclusive destruction.
One local voice model is loaded per utterance, reused across its chunks, then
released. Cold loading is included in the time users wait for the first audio;
there is no claim of persistent warm playback between separate Play taps.

## Self-hosted setup

The adapter is first-party code in `scripts/voice/hearth_voice_server.py`. Use a
**separate virtual environment per engine**, following its upstream dependencies;
Qwen, Chatterbox and Fish pin incompatible PyTorch/transformers generations.
The bridge itself uses Python's standard library; in-process synthesis also needs
the selected upstream package and NumPy. It binds to localhost by default. Put a
trusted HTTPS reverse proxy in front before connecting the Android app. Do not use
this small development server as an unauthenticated public service: add request and
connection limits at the proxy. There is one inference at a time; overlapping
requests receive HTTP 429, not an unbounded queue.

Generate `HEARTH_VOICE_API_KEY` in your private shell/environment (at least 24 ASCII
characters, without whitespace). Never put it in Git or a shared command transcript.
Paste that same key and the HTTPS base URL into **Self-hosted → Save & check voices**.
Hearth retrieves capabilities and offers only the server's languages/voices. It
stores the key with Android Keystore AES-GCM, with no plaintext/file-key fallback.
The app never sends this key to Hugging Face or model downloads.

### Chatterbox Multilingual V3

[Official source/install instructions](https://github.com/resemble-ai/chatterbox).
The adapter was inspected against revision
`5de7a54aa4e5e2baadb0182dde554908b48b85c2`; install that revision in a dedicated
Python environment when reproducing this integration:

```sh
python -m pip install 'git+https://github.com/resemble-ai/chatterbox.git@5de7a54aa4e5e2baadb0182dde554908b48b85c2'
python scripts/voice/hearth_voice_server.py chatterbox --device cuda
```

Optional `--reference /private/my-voice.wav` selects your own reference. The bridge
uses `ChatterboxMultilingualTTS.from_pretrained(..., t3_model="v3")`, its published
language inventory and `generate(..., language_id=...)`. MIT code/model terms are
published upstream; preserve attribution and the model's watermark behavior.

### Qwen3-TTS 0.6B Base

[Official model/API card](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base).
Install `qwen-tts` using the card's dedicated environment instructions, then:

```sh
python scripts/voice/hearth_voice_server.py qwen --device cuda:0 \
  --reference /private/my-voice.wav --transcript /private/my-voice.txt
```

Use a reference you have permission to use and its accurate UTF-8 transcript.
No reference recording from tts-bench is copied or substituted. The adapter passes
explicit language names to `generate_voice_clone`. Qwen uses Apache-2.0 terms.
Model load/download follows the upstream package; record its resolved model revision
and installed package versions in your own deployment lockfile.

### Fish Speech

[Official Fish server setup](https://github.com/fishaudio/fish-speech).
Inspected code revision: `befe4001745417f8c42131739d862b8a6fdbd15a`.
Run its `/v1/tts` server in its own environment using your chosen checkpoint.
Then launch the Hearth proxy; the example language list is intentionally narrow:

```sh
python scripts/voice/hearth_voice_server.py fish \
  --fish-url http://127.0.0.1:8080 --fish-model s2-pro \
  --fish-languages en,ar --fish-reference my-voice
```

Set `FISH_API_KEY` separately if the upstream server requires authentication.
Omit `--fish-reference` when using its default voice. List only languages supported
by your deployed checkpoint. The official API infers language from text: the bridge
does not invent a language override. Its request is JSON, `format="wav"`,
`streaming=false`, with optional `reference_id`. Fish code/weights have **Fish Audio
Research License** terms; commercial use may require separate permission. They are
not bundled or relicensed as part of Hearth.

## Bridge protocol and extension

Authenticated `GET /capabilities` returns:

```json
{"model":"checkpoint-id","label":"Readable engine name","languages":["en","ar"],"voices":["default"]}
```

`POST /speech` accepts JSON `{ "model": "checkpoint-id", "text": "Hello",
"language": "en", "voice": "default" }`. It returns complete uncompressed PCM16
WAV (mono/stereo, 8–48 kHz, ≤120 seconds and ≤12 MiB). The app splits text into
≤300-character requests; the supplied bridge enforces that limit. Errors use a
non-2xx status with their actual cause; keys are redacted. The app blocks redirects,
bounds response size, and cancels HTTP on Stop. Server computation may finish after
a client disconnects; the result is discarded, and the server remains busy until
that inference ends. This is chunked read-aloud, not token-streaming TTS.

A new hosted model implements `model_id`, `label`, `languages`, `voices`, and
`speech(text, language, voice) -> PCM16 WAV`, then registers in the bridge. A new
embedded model instead adds a capability/catalog entry, verified installation and
an owned cancellable JNI adapter behind VoicePlayer. Keep user language choices
separate from TTS capability data. Reuse PcmWave and VoiceText only where the
backend's actual tokenizer/audio contract matches.

## Research and evidence

The requested `tts-bench` repository was cloned to ignored `research/tts-bench` at
`9dfe4bec808f5af5ea9cf374fc1fa04546e63dbe`. Supertonic code was inspected at
`1e9799e964ea4c0dad7cde993b65c3c813a7b373`. Other research checkouts and downloaded
weights remain ignored. No benchmark celebrity/reference samples are redistributed.
Desktop leaderboard numbers are not presented as Android measurements.

Run `python3 -m unittest discover -s scripts/voice -p 'test_*.py'` for server protocol
and adapter contract tests. Kotlin tests exercise import safety, capabilities,
HTTP cancellation/redaction/bounds, WAV parsing and the real typing controller with
a controlled translator. The native voice bounds suite runs with ASan/UBSan.
See [v22 validation](validation-v22.md) for actual phone checks and limitations.

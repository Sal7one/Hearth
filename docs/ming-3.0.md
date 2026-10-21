# Ming-3.0 in Hearth

Verified from the publisher's repositories and checkpoint file metadata on **20 October 2026**.
The five exact revisions, SHA-256 hashes, component sizes, source revisions and
runtime profiles are in [the machine-readable model cards](models/ming-3.0.json).
Quantizations are variants of these models, not extra families.

| Checkpoint | Announced backbone / active parameters | Required published runtime files | Hearth route |
| --- | --- | ---: | --- |
| [TTS 0.5B](https://huggingface.co/inclusionAI/Ming-3.0-tts-0.5B) | 0.5B dense backbone | 3,003,264,578 B | Hearth voice bridge, official CUDA/BF16 API |
| [TTS Tiny](https://huggingface.co/inclusionAI/Ming-3.0-tts-tiny) | 7.9B / 1.3B | 18,005,190,338 B | Hearth voice bridge, separate official Tiny API |
| [ASR Tiny](https://huggingface.co/inclusionAI/Ming-3.0-asr-tiny) | 8.6B / 2B | 17,746,272,457 B | Existing custom OpenAI-compatible batch STT connection |
| [Audio Tiny](https://huggingface.co/inclusionAI/Ming-3.0-audio-tiny) | 9.3B / 2.7B | 17,757,238,725 B for transcription | Hearth Ming speech bridge → official SGLang server |
| [Audio Flash](https://huggingface.co/inclusionAI/Ming-3.0-audio-flash) | 126B / 6.5B | 256,388,580,051 B for transcription | Hearth Ming speech bridge → official SGLang server |

These are decimal byte counts for the listed profile, including required configuration,
tokenizer and code files; they exclude repository screenshots and documentation.
They are **disk/download sizes, not peak RAM or VRAM requirements**. The optional
Talker expands Audio Tiny's runtime files to **20,771,991,029 B** and Audio Flash's
to **259,403,332,355 B**. Hearth's transcription bridge does not load the Talker.
Reference clips and unused bytecode caches are not required transcription components.

The advertised parameter counts describe the backbone, and active MoE parameters
describe per-token computation. They do not describe the full download. For example,
TTS 0.5B's published safetensors contain **1,479,454,868 parameters** across the
complete generation/codec stack. The catalog separately records announced backbone
counts and the Hub's top-level stored tensor counts without replacing one with the other.

All five are MIT releases, with bundled third-party notices retained by upstream.
Read the pinned upstream legal and license files when deploying. The catalog records
Hugging Face and ModelScope source links. ModelScope is a source link, not an
unverified fallback download that bypasses the pinned Hugging Face hashes.

## Language and latency boundaries

The two TTS models publish **Chinese, English, German, Spanish, French, Korean and
Japanese**. The ASR/audio models publish **Chinese and English**, with dialect and
accent coverage. **None publishes Arabic support.** Hearth does not advertise Arabic
for these connections or route Arabic text to an English voice automatically.

These adapters return completed utterances or completed article chunks. They do not
add streaming partial captions, native Android execution, Adreno acceleration or an
S25 benchmark. CUDA runs on the user's server; phones and TVs are network clients.
The FOSS build cannot make these network connections. TV local-model policy stays intact.

Use Hearth's independent MT stage for caption translation, and its independent TTS
stage for read aloud. Ming ASR's `/audio/transcriptions` endpoint does not implement
Whisper's `/audio/translations` task. Leave integrated STT-to-English translation off.
The audio-chat models' tools, reasoning and multimodal interaction are not exposed
as speech pipeline controls.

## TTS: two isolated environments

Clone the [official TTS repository](https://github.com/inclusionAI/Ming-3.0-tts)
outside the app and check out the `source.revision` in the model card. Do not copy
Python into Android or add Torch to `:hearth-tts`. The core library and Supertonic /
Magpie adapters remain independent from these server dependencies.

Use a dedicated environment for each model. At the pinned revision, 0.5B uses
PyTorch/torchaudio **2.6.0** and Transformers **4.46.3**. Tiny uses Python **3.10.15**,
PyTorch/torchaudio **2.7.1+cu118**, Transformers **4.46.3** and its own requirements.
Install the appropriate pinned upstream `requirements.txt` or `requirements-tiny.txt`.
Do not merge these environments with Ming ASR or Ming Audio's newer Transformers stacks.

Download one exact checkpoint revision explicitly, for example:

```sh
hf download inclusionAI/Ming-3.0-tts-0.5B --revision e5bd8da31ecf1b9276cdbdda821fa891068f1368 --local-dir /models/ming-tts-05b
python3 scripts/ming/ming_catalog.py inclusionAI/Ming-3.0-tts-0.5B /models/ming-tts-05b
```

The bridge verifies the pinned component sizes and full hashes again before loading.
No model package is imported until its backend is selected. Set a strong server
credential in `HEARTH_VOICE_API_KEY` (at least 24 non-whitespace ASCII characters).
Keep that credential on the voice connection; it is never sent to model hosts.

```sh
PYTHONPATH=/srv/Ming-3.0-tts python scripts/voice/hearth_voice_server.py ming-0.5b --device cuda:0 --ming-model-dir /models/ming-tts-05b
```

For Tiny, use its isolated environment, `ming-tiny`, and the directory downloaded at
revision `b170071302cb0b06435ea784787b119f40a08be8`. 0.5B has an explicit
`--ming-no-text-normalization` option if optional Pynini is unavailable; this bypasses
normalization and can change pronunciation of numbers, dates and symbols.

Without reference arguments, each adapter advertises only English and these five
publisher-listed presets:

| Voice ID | Published gender | Accent |
| --- | --- | --- |
| `US_Emma` | Female | American |
| `US_James` | Male | American |
| `US_Stella` | Female | American |
| `UK_George` | Male | British |
| `UK_Charlotte` | Female | British |

The pinned cookbook includes `US_Stella`; the older `EN_IP_VOICES` helper tuple in
that same revision lists only four. Hearth follows the five explicitly documented
voice IDs and preserves the names verbatim. These are publisher claims, not owner
listening-test results.

Add **both** `--reference /owned/voice.wav` and `--transcript /owned/voice.txt` to use
your own reference voice across the seven published languages. The server then
advertises `reference` instead of the English presets. Tiny dispatches `seed` for
Chinese/English and `multilingual` for the other five languages. 0.5B uses its
different `synthesize` contract. Neither adapter silently swaps checkpoints, voices
or languages. Tiny decode-budget exhaustion is a failed request.

Put HTTPS and access control in front of the loopback service, then use **Cloud
voices → Self-hosted → Save & check voices** in Hearth. The existing capability
check supplies the model, languages and voice IDs. Requests remain bounded at
300 characters and complete PCM16 WAV output; longer articles are split by the client.

### Desktop app

The desktop app exposes Ming TTS as the self-hosted **`ming-tts`** provider, not a
local dylib. Set the full `/speech` endpoint (default
`http://127.0.0.1:8787/speech`), the exact model id
(`inclusionAI/Ming-3.0-tts-0.5B` or `inclusionAI/Ming-3.0-tts-tiny`) and the
`HEARTH_VOICE_API_KEY` credential. Requests are capped at 300 characters; published
languages are zh/en/de/es/fr/ko/ja (no Arabic) and the voice presets are US_Emma,
US_James, US_Stella, UK_George and UK_Charlotte. `DesktopMingTtsTest` exercises the
exact contract with a loopback mock; a real CUDA server is not run in CI.

The upstream `fusor-rs/ming-wasm` project has a **desktop-only** native CLI for
macOS arm64 (experimental v0.1, 850 MB bundle, ~2 GB memory, phones unmeasured).
There is no Android runtime for it; Android uses the hosted bridge above.

### Desktop local engine (experimental)

The desktop app also exposes **`ming-local`**, a local Ming-3.0-tts-0.5B engine built
from the pinned `ming-ffi`/`ming-jni` Rust source over fusor-ai 0.1.2. It downloads the
850 MB `fusorlabs/ming-3.0-tts-0.5b-web` bundle (17 pinned files), converts the flat
catalog layout into the runtime's `parts/` layout, and writes 44.1 kHz mono WAV through
JNI. It advertises **English and Chinese only**, because the shipped runtime hardcodes
`language: None`; the other five published languages need a library-level wrapper.
Peak RSS is ~1 GB and each one-shot call reloads the bundle (~0.9 s). `DesktopMingLocalRealSmokeTest`
is owner-opt-in. Android is **build-verified only** (aarch64 `.so`, API 28, 16 KB pages)
and has not been run on a device; the default Android kernel is scalar and much slower.

## ASR Tiny: direct connection

Follow the pinned [official ASR setup](https://github.com/inclusionAI/Ming-3.0-asr)
and the [SGLang-Omni cookbook](https://sgl-project.github.io/sglang-omni/cookbook/ming3_asr.html).
This is the Omni deployment; the older GitHub SGLang patch uses a different request
contract and served-model alias. The Omni deployment uses CUDA and provides:

```text
POST /v1/audio/transcriptions
model=inclusionAI/Ming-3.0-asr-tiny
file=<WAV>
response_format=json
```

In Hearth select **Cloud speech → Batch → Custom**, enter your HTTPS `/v1` URL
and its server key, then choose **Ming-3.0 hosted speech models → ASR Tiny**.
The template changes only the speech model ID. It does not change the server URL,
credentials, voice defaults or translation model. The normal model browser also
recognizes this exact model ID. Optional endpoint `language` hints are `en` / `zh`;
context and hotword prompts use the publisher's documented contract.

## Audio Tiny / Flash: transcription bridge

Deploy the pinned [official audio repository](https://github.com/inclusionAI/Ming-3.0-audio-flash)
using its SGLang **text-only** profile for the chosen variant. This is a server task,
with resources appropriate to the full stored checkpoint. The 126B Flash checkpoint
does not become a phone model because only 6.5B parameters activate per step.

Run the dependency-free HTTP adapter separately:

```sh
python3 scripts/ming/hearth_ming_speech_server.py --model inclusionAI/Ming-3.0-audio-tiny --upstream http://127.0.0.1:8008/v1
```

Set `HEARTH_SPEECH_API_KEY` for client authentication and, if the upstream requires
one, a separate `MING_SGLANG_API_KEY`. Use an HTTPS front end. Hearth's custom base
URL points to this bridge's `/v1`, and the selected speech model must match it exactly.
Use `audio-flash` instead of `audio-tiny` for the other variant.

The bridge forwards the official ASR prompt with inline WAV audio to
`/chat/completions`, strips the documented language-and-tab prefix, and returns
`{"text":"..."}`. It rejects other tasks, tools, unsupported languages, incomplete
responses, truncated generation, malformed WAVs and concurrent engine work. Uploads
are limited to 30 seconds / 6 MiB. There is no hidden request queue or tool execution.
Its `/v1/models` advertises transcription explicitly; generic Ming audio endpoints
without that evidence are not classified as batch STT by Hearth.

## Validation and remaining evidence

`python3 -m unittest discover -s scripts/ming -v` verifies inventory/profile totals,
corrupt-file rejection, multipart upload and real loopback HTTP contracts, language
prefix handling, error/auth behavior and the official SGLang request shape.
`python3 -m unittest discover -s scripts/voice -v` checks the two distinct TTS API
contracts, preset selection, multilingual dispatch and incomplete decode failure.
These tests use injected inference responses. **No Ming checkpoint inference or
CUDA performance benchmark has been run in this workspace.** Owner/server testing
must verify quality, memory use, latency and the chosen server version before a
production deployment. Nothing here changes that evidence level to hardware-verified.

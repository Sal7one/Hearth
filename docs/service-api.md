# Hearth service API

How another program on the same computer asks Hearth to transcribe, translate or speak.
The first caller is LocalHut on the owner's Windows PC, which already serves that PC to
GoodChat over the LAN.

**Status (15 November 2026).** The desktop service is built and proven on Windows with
real models: the contract in `hearth-core`, the headless host, client tokens, routes from
the owner's saved setup, jobs, outputs, forget, the engine worker process, runtime,
release, idle unload and the measurements. Not built yet: an app screen for approving
clients and defining extra routes, video-memory measurement for GPU routes, LocalHut's
recipes, and the Android service. macOS runs the same code but has not been exercised.

## Rules

1. **Hearth never listens on the network.** The service binds `127.0.0.1` only. LocalHut
   stays the only program on the LAN and keeps its own pairing, pinned TLS, device
   tokens and job queue.
2. **One contract.** The operations, bounds and errors are defined once in `hearth-core`
   (`com.sal7one.hearth.core.service`), on top of the existing `PipelineService`. A
   transport only carries them: loopback HTTP on Windows and macOS, a bound service on
   Android later.
3. **Callers choose a route, never an engine, a model or a path.** A route is what the
   owner saved in Hearth. A caller can never cause a download or load an arbitrary file.
4. **No request stays open.** Every call answers within seconds. Work is a job that the
   caller polls. There are no streamed responses in version 1.
5. **Results are files, not JSON bodies.** A job record stays small; transcripts,
   translations and audio are outputs fetched separately, with range requests.
6. **A failure is never an empty success.** Errors carry a stable code and the engine's
   actual message.
7. **A job leaves nothing behind.** Forgetting a job removes its input, outputs and
   record. Logs never contain audio, text or tokens.

## The path of a request

```text
GoodChat -> Mac backend -> LocalHut (LAN, pinned TLS, pairing, jobs, assets)
                              |  loopback HTTP, per-client token
                              v
                     Hearth service host  ->  engine worker process  ->  models
```

LocalHut maps one of its jobs to one Hearth job with the same 32-hex id. LocalHut's
assets carry the audio and the results; Hearth holds its own copy only while the job
exists.

## Service host

`Hearth --serve` runs Hearth without a window, on the same settings as the desktop app.
LocalHut's panel starts and stops it as a third service beside ComfyUI and the bridge.
Adding it to the logon task is the owner's decision.

| Need | Contract |
| --- | --- |
| Executable | The installed launcher with one argument: `Hearth.exe --serve` on Windows, `Hearth.app/Contents/MacOS/Hearth --serve` on macOS. The install folder is chosen at install time, so the panel stores the path. |
| Single instance | A lock in Hearth's data folder. A second `--serve` exits at once with code 3 and starts nothing. |
| Start-up | Listening and healthy within 10 seconds; measured at 2.5 seconds for the packaged Windows app. Start-up loads no model and hashes no model file. |
| Health | `GET /health` needs no token and answers `{"service":"Hearth","protocol":1,"state":"ready"}` and nothing else. |
| Where it listens | Hearth picks a free loopback port and writes `service/host.json` (`protocol`, `port`, `pid`, `started`, and `executable` when it runs from an installed launcher) in its data folder once it is listening. The file is removed on a clean stop. |
| Stop | `POST /v1/host/stop` answers 202, then cancels and joins the running job, purges job data and exits 0. If the panel has to end the process tree instead, the next start purges what was left. |
| Log | `logs/service.log` in the data folder, size-bounded and rotated. It records job ids, route ids, sizes, durations and error codes only. |

The data folder is `%LOCALAPPDATA%\Hearth` on Windows and
`~/Library/Application Support/Hearth` on macOS; `HEARTH_DATA_DIRECTORY` overrides it.

### Client token

The owner approves a client by name with `Hearth --service-approve localhut`. Hearth
writes that client's token to `service/clients/localhut.token`, readable by the current
user only (a single-owner ACL on Windows, mode 600 elsewhere). Approving again keeps the
token; `Hearth --service-revoke localhut` deletes it. Every `/v1/` call carries
`Authorization: Bearer <token>`. LocalHut reads the file at job time and never caches,
returns or logs the token. Tokens never appear on a command line, in an environment
variable or in a message between sessions.

### Engine worker process

The host is a small supervisor that loads no engine library itself. Models are loaded in
one child process, the engine worker, which the host starts on demand and talks to over
a local socket inside the service folder.

This is what makes three guarantees possible. A native library that unloads a model
returns memory to its own process, not to the operating system, and a CUDA context stays
allocated until its process exits. Ending the worker is therefore the only release that
is immediate and measurable. It also bounds clean-up for an engine call that cannot be
interrupted, and a crash in native code ends one job instead of the host. The cost is
that the first job after a release loads its model again.

## Routes

A route is what the owner saved in Hearth. The speech, translation and voice setups
chosen in the desktop app become up to four routes:

| Route id | Offered when the saved setup has |
| --- | --- |
| `transcribe` | a local speech engine |
| `translate` | a local translation engine |
| `speak` | a local or system voice and a declared language for it |
| `pipeline` | local speech plus translation or voice, with every saved stage local |

A caller may ask only for the languages and the voice the owner saved. A stage that
uses a cloud provider is never offered, so a caller cannot spend the owner's provider
account. The host re-reads the saved setup every ten seconds, so changing a model in the
app changes what the route runs. Routes with their own names and bounds, for example a
large model kept only for batch transcription, are not built yet.

`GET /v1/capabilities` lists **only routes that are ready now**: the engine library
loaded when the host started and the model is on disk. It answers from memory, loads
nothing, and was measured at 54 ms at worst. Each route carries:

| Field | Meaning |
| --- | --- |
| `id`, `operation` | The route and `transcribe`, `translate`, `speak` or `pipeline`. |
| `stages` | For each stage: `stage`, `engine`, `model` and `acceleration` (`cpu`, `cuda` or `metal`). Informational. |
| `sourceLanguages`, `targetLanguages`, `voices` | What a request may ask for. |
| `limits.maxAudioSeconds`, `limits.maxTextCharacters` | Hard bounds for one job. |
| `speed.secondsPerAudioSecond` | Measured on this computer during the route's last completed job; `null` until measured. For `speak` it is seconds per second of generated audio. |
| `memory.ramBytes`, `memory.vramBytes` | The worker's memory after the route's last completed job; `null` until measured. It is the whole worker's memory, so it includes any other route loaded at that moment: treat it as an upper bound for this route, not a sum to add across routes. `vramBytes` is 0 for a CPU route and `null` for a GPU route, because Hearth does not measure video memory yet. |
| `resident` | Whether every model of the route is loaded in the worker right now. |

A route that stopped being ready fails job creation with `route_not_ready` and the
actual reason.

## API version 1

All bodies are JSON unless stated. Unknown request fields are rejected. A request that
carries an `Origin` header, or a `Host` other than the published loopback address, is
refused.

| Method and path | Purpose |
| --- | --- |
| `GET /health` | Liveness without a token. |
| `GET /v1/capabilities` | `protocol`, the ready routes, accepted input types and the limits below. |
| `PUT /v1/jobs/{key}` | Create a job under the caller's key (32 lower-case hex). Idempotent. 201 when created, 200 when it already existed. |
| `PUT /v1/jobs/{key}/input` | Upload the job's audio as the raw body. Idempotent. |
| `GET /v1/jobs/{key}` | The job record. |
| `GET /v1/jobs/{key}/outputs/{name}` | One output file; supports a single `Range`. |
| `DELETE /v1/jobs/{key}` | Forget: cancel, join and purge. Idempotent. |
| `GET /v1/runtime` | What is loaded and whether a job is running. |
| `POST /v1/runtime/release` | Unload everything and report what was freed. |
| `POST /v1/host/stop` | Clean shutdown. |

### Creating a job

```json
{
  "route": "transcribe",
  "sourceLanguage": "en",
  "targetLanguage": "ar",
  "voice": "F1",
  "text": "Only for translate and speak routes.",
  "deadlineSeconds": 780,
  "requireResident": false
}
```

- `route` is required. The route decides the operation. Language and voice values must
  be ones the route lists; when omitted, the owner's saved values apply.
- `text` is required for `translate` and `speak` and refused for the others. It is
  bounded by the route's `maxTextCharacters`, at most 12,000.
- `deadlineSeconds` is the caller's remaining time budget. Hearth refuses an audio job
  whose estimate exceeds it and cancels its own job when it passes, so the caller's kill
  is a backstop only.
- `requireResident: true` fails at once with `not_resident` unless the route's models
  are already loaded. This is for short synchronous calls (see below).
- Sending the same key with the same body returns the existing job. The same key with a
  different body, or a key that was forgotten, is refused with `key_conflict`.
- One job runs at a time. Creating a second job while one is active is refused with
  `busy`, and the message names the active key. LocalHut is the scheduler; Hearth does
  not queue behind it.

### Input

For `transcribe` and `pipeline` the job waits in `awaiting_input` until
`PUT /v1/jobs/{key}/input` completes. The request carries `Content-Type`,
`Content-Length` and `X-Content-SHA256`. A repeated upload with the same digest is a
no-op; a different one is refused. An upload that does not match its digest stores
nothing. A job whose upload does not arrive within 120 seconds is cancelled. No file
path is ever accepted.

| Accepted type | Notes |
| --- | --- |
| `audio/wav`, `audio/x-wav` | RIFF PCM. |
| `audio/mp4`, `audio/x-m4a`, `audio/aac` | AAC in MP4 or ADTS. |
| `audio/mpeg` | MP3. |
| `video/mp4`, `video/quicktime` | The first audio track is used. |

Decoding uses the operating system (Media Foundation on Windows, AVFoundation on
macOS) through Hearth's bundled decoder, which is unpacked once under
`service/helpers` and reused, never into a job's folder. Windows cannot start a
program from a path of 260 characters or more, so a data folder too deep for that
fails the job with `unsupported_media` and a message that says so.
After the upload, and before any model is loaded, Hearth decodes the audio,
refuses it with `input_too_long` if it exceeds the route's bound, and puts
`inputAudioSeconds` and, once the route has been measured, `estimatedSeconds` in the
job record.

### Job record

```json
{
  "key": "0123456789abcdef0123456789abcdef",
  "route": "transcribe",
  "operation": "transcribe",
  "state": "completed",
  "created": 1794709915, "started": 1794709916, "finished": 1794709918,
  "inputAudioSeconds": 10.009375,
  "summary": {"segments": 1, "language": "en", "audioSeconds": 10.009375},
  "outputs": [
    {"name": "transcript.json", "mime": "application/json", "bytes": 152, "sha256": "..."},
    {"name": "transcript.vtt", "mime": "text/vtt", "bytes": 123, "sha256": "..."}
  ]
}
```

`mime` is the bare media type: `application/json`, `text/vtt`, `text/plain` or
`audio/wav`. Every text output is UTF-8, and its download carries
`Content-Type: <mime>; charset=utf-8`.

States are `awaiting_input`, `queued`, `running`, `completed`, `failed` and
`cancelled`. A failed or cancelled job has `"error": {"code": "...", "message": "..."}`.
The record never contains transcript or translation text.

| Operation | Outputs | Summary |
| --- | --- | --- |
| `transcribe` | `transcript.json` (segments with `id`, `startMs`, `endMs`, `text`), `transcript.vtt` | `segments`, `audioSeconds`, and `language` when the request declared one |
| `translate` | `translation.txt` (UTF-8) | `characters` |
| `speak` | `speech.wav` (PCM16 mono at the voice's own rate) | `audioSeconds`, `sampleRate` |
| `pipeline` | The outputs of each stage the route enables; `transcript.json` then also carries each segment's `translation` | The union of the above |

The shared pipeline does not expose the language a recognizer detected, so a job that
asked for `auto` reports no `language`.

### Forgetting a job

`DELETE /v1/jobs/{key}` cancels the job if it is running, waits for the engine to stop,
deletes the input, every output and the record, and answers
`{"key": "...", "state": "forgotten"}`. If the engine does not stop within 3 seconds
the host ends the worker process, so the call answers within about 5 seconds in every
case. It is idempotent: an unknown or already forgotten key gets the same answer. Jobs
that are never forgotten are purged one hour after they finish, and at host start.

### Runtime and the shared card

`GET /v1/runtime` answers

```json
{"busy": null, "resident": ["transcribe", "translate"],
 "worker": {"ramBytes": 2086715392, "vramBytes": 0, "idleSeconds": 4},
 "idleUnloadSeconds": 120}
```

`busy` is the active job's key as a string, or `null`. Memory belongs to the one worker
process, not to a route, so it is reported once; `worker` is `null` when nothing is loaded. `ramBytes` is the worker's committed private
memory on Windows and its resident set elsewhere.

`POST /v1/runtime/release` is synchronous. It ends the worker and answers
`{"released": ["route ids"], "freed": {"ramBytes": 2086715392, "vramBytes": 0}}`,
measured from the worker just before it exits. While a job is active it is refused with
`busy`; forget the job first. Releasing when nothing is loaded is not an error.

The intended sequence on the PC:

1. Before a Hearth job on a GPU route, LocalHut makes ComfyUI unload.
2. Before a ComfyUI job, LocalHut calls `release`. This matters for CPU routes too,
   because a resident speech model holds RAM that a large ComfyUI model needs.
3. As a backstop, Hearth ends the worker by itself after `idleUnloadSeconds` without a
   job.

### Errors

Errors answer `{"error": {"code": "...", "message": "..."}}`.

| Code | HTTP | Meaning |
| --- | --- | --- |
| `unauthorized` | 401 | Missing or wrong token. |
| `invalid_request` | 400 | Malformed body, unknown field, a value the route does not list, or an upload that does not match its digest. |
| `unknown_route`, `unknown_job` | 404 | No such route or job. |
| `route_not_ready` | 409 | The route cannot run now; the message is the reason. |
| `key_conflict` | 409 | The key exists with a different request, has a different input, or was forgotten. |
| `busy` | 503 | Another job is active; the message names its key. |
| `not_resident` | 503 | `requireResident` was set and the models are not loaded. |
| `input_too_large`, `unsupported_media` | 413, 415 | The upload exceeds 64 MiB or is not an accepted type. |
| `input_too_long`, `would_exceed_deadline`, `unsupported_media` | on the job record | Found after the upload was decoded: too long for the route, estimated past the deadline, or not decodable. |
| `output_too_large` | on the job record | The generated speech would pass 64 MiB. The voice is stopped while it is writing. |
| `engine_failed` | on the job record | The engine's own error, in `message`. |
| `cancelled` | on the job record | Stopped by its deadline or a missing upload, with the reason in `message`. |

### Limits in version 1

| Limit | Value |
| --- | --- |
| Input upload | 64 MiB |
| One output file | 64 MiB (about 12 minutes of 44.1 kHz speech) |
| Text | 12,000 characters |
| Decoded audio | One hour |
| Jobs | One running, none queued |
| Wait for an upload | 120 seconds |
| Unforgotten job lifetime | One hour after it finishes |
| Idle unload | 120 seconds |

## What LocalHut adds

- Four fixed recipes, `speech.transcribe`, `text.translate`, `speech.speak` and
  `speech.pipeline`, each naming the Hearth route of the same operation. A recipe is
  offered only while Hearth is healthy and lists that route.
- Audio as an asset type, with a header check per container in the table above, and
  `audio/wav`, `application/json`, `text/vtt` and `text/plain` as output assets.
- When the job starts: LocalHut cannot know an upload's duration, so Hearth reports
  `input_too_long` or `would_exceed_deadline` on the job record seconds after the
  upload, and the LocalHut job fails with that message. LocalHut passes its remaining
  budget as `deadlineSeconds`. A longer budget for long recordings is the owner's decision.
- On cancel, deadline, release of a temporary job and expiry: `DELETE /v1/jobs/{key}`.
- Short synchronous calls, if wanted, live in LocalHut under their own prefix (for
  example `/openai/v1`) with their own limits. They use `requireResident: true` and
  answer 503 "PC is busy" instead of waiting for a model to load.

## Beside the Hearth window

The window and `--serve` are separate processes with separate engines. Neither refuses
to start because of the other, and neither attaches to the other. Each loads models only
when it runs something: the window when the owner starts captions, translation or a
voice there, the host's worker when a job runs. Used at the same time they hold two sets
of models. The host cannot see or unload what the window has loaded, so a caller that
must protect memory checks free memory before a job, as LocalHut does. Making the window
ask the host to release first, and letting the host report that the window is open, is
not built.

## Proof on the Windows PC

`DesktopServiceRealSmokeTest` runs the real worker process with catalog models on CPU:

| Step | Result |
| --- | --- |
| Host start, including the engine list from a worker | 1.7 s |
| `transcribe`, Whisper tiny, 10.0 s of audio uploaded as WAV | exact transcript, about 2 s |
| `translate`, CTranslate2 QuickMT English to Arabic | `يفتح المتحف في الساعة التاسعة صباحًا.` |
| `speak`, Supertonic, Arabic | 2.42 s at 44.1 kHz, non-silent |
| `runtime` after the three jobs | worker holds 2.09 GB |
| `release` | reports 2.09 GB freed, answers in 0.6 s, worker gone |

The packaged `Hearth.exe --serve` was exercised separately: client approval, health,
capabilities, a second instance exiting with code 3, and a clean stop.

## Later, and separate

- **An app screen** for approving clients and defining named routes with their own
  bounds.
- **Video-memory measurement** for GPU routes, after the CUDA build is validated here.
- **Android.** An exported bound service with a typed interface, audio through pipes
  rather than binder transactions, and trust by the calling app's signature plus a
  one-time owner approval. It needs no network permission, so it works in the `foss`
  flavor. It does not involve LocalHut and is to be agreed with the GoodChat session.
- **Live captions over the LAN.** Needs a streaming route in LocalHut with its own
  admission and duration bound.
- **Hearth on a phone using the PC's models.** Needs a network path to the PC, which
  the firewall does not allow today, and a Hearth provider that trusts LocalHut's pinned
  certificate. Both wait for the owner.

## Decisions that belong to the owner

1. Whether LocalHut may run a job longer than 13 minutes for long recordings.
2. Whether the Hearth host joins the logon task.
3. Installing the NVIDIA CUDA Toolkit, without which every route on this PC is CPU.
4. On the GoodChat side, new recipes need the owner's word.

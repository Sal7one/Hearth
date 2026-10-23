# Desktop integration and OBS captions

This optional JVM module connects real `hearth-core` pipeline sessions to external
apps. It binds **127.0.0.1 only**, starts only when requested, and has no Android
dependency. Starting the server does not start recognition, select a provider or
download a model. The desktop app supplies its saved, independent STT/translation/
voice routes through `configuration`.

```kotlin
val integration = DesktopIntegrationServer(pipeline, configuration = { savedConfig })
integration.start() // Run binding/close on a background dispatcher; failures are thrown.
val apiAddress = integration.baseUrl
val obsAddress = integration.overlayUrl
// Explicit Copy API token action only: integration.apiToken
val observation = integration.observe(desktopOwnedSession)
// Detach when no longer needed. This does not cancel desktop-owned inference.
observation.close()
integration.close() // Cancels/joins HTTP-owned sessions, not the shared desktop service.
```

`start()` returns the same server and is idempotent while running. A closed server
cannot restart; create another instance. `baseUrl`, `port` and `overlayUrl` are
available after start. `directory` optionally chooses the parent for a private
temporary upload directory; `requestedPort=0` chooses an available port.

## External-app API

Set `Authorization: Bearer <the explicitly copied API token>` on every `/v1/`
request. No provider secret or raw filesystem path is accepted in HTTP JSON.
Requests use the configured routes. Optional `sourceLanguage`, `targetLanguage`,
`translationEnabled` and `voiceEnabled` overrides may disable stages or use a
stage already configured in the app; they cannot silently choose another engine.

| Method and path | Request/result |
| --- | --- |
| `GET /v1/capabilities` | Actual pipeline engine descriptors, availability/errors, supported input and transport limits. No saved credentials, model paths or provider connection settings. |
| `POST /v1/sessions` | JSON `{"text":"Hello","targetLanguage":"ar","voiceEnabled":false}`. Returns HTTP 202 and the actual pipeline session snapshot. Empty text and text above 12,000 characters fail. |
| `POST /v1/sessions/audio` | Raw `audio/wav` body, or `audio/pcm` plus `X-Hearth-Audio-Format: pcm-s16le-16000-mono`. Optional route overrides are query parameters. The server validates/rebuilds a canonical private WAV before invoking `AudioFile`. |
| `POST /v1/sessions/media` | Streamed MP4/MOV/M4A/MP3/AAC upload, at most 64 MiB. Uses the saved speech route and the actual native platform decoder. No local path is accepted; container/codec failures appear in the session. |
| `GET /v1/sessions` | Up to 32 retained session snapshots. Completed history is reclaimed when necessary. |
| `GET /v1/sessions/{id}` | Actual status, stage, original/translated caption segments, errors and generated-audio download references. |
| `DELETE /v1/sessions/{id}` | Cancels and joins an HTTP-owned session before reporting its resulting state. Desktop-observed sessions must be stopped in the desktop UI. |
| `GET /v1/sessions/{id}/audio/{index}` | Authenticated generated audio, at most 24 MiB; no native output path is exposed. |
| `GET /v1/events` | Server-sent `snapshot`, `session` and `resync` events. Optional `?session=ID` filter; `Last-Event-ID` resumes the bounded event history or explicitly requests a fresh snapshot. |

For example, after explicitly setting `HEARTH_INTEGRATION_TOKEN` in your own shell:

```sh
curl -H "Authorization: Bearer $HEARTH_INTEGRATION_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"text":"Hello","sourceLanguage":"en","targetLanguage":"ar","voiceEnabled":false}' \
  http://127.0.0.1:PORT/v1/sessions

curl -H "Authorization: Bearer $HEARTH_INTEGRATION_TOKEN" \
  -H 'Content-Type: audio/wav' --data-binary @speech.wav \
  http://127.0.0.1:PORT/v1/sessions/audio

curl -H "Authorization: Bearer $HEARTH_INTEGRATION_TOKEN" \
  -H 'Content-Type: video/mp4' --data-binary @footage.mp4 \
  'http://127.0.0.1:PORT/v1/sessions/media?voiceEnabled=false'
```

Media MIME types are `video/mp4`, `video/quicktime`, `audio/mp4`, `audio/x-m4a`,
`audio/mpeg` and `audio/aac`. Hearth streams the request to a private file with
the appropriate extension; AVFoundation on macOS or Media Foundation on Windows
decodes its first audio track through the configured speech engine. This requires
the packaged native helper and a codec supported by that operating system. The
server advertises this dependency and the 64 MiB byte limit in capabilities.
Pending media uploads share the four-job admission limit. Interrupted uploads or
failed creation responses release their reservation/file; accepted jobs retain
their private input until terminal state or joined cancellation. Decoded duration
is bounded by the platform adapter; the canonical audio/live limit below applies
to those endpoints separately.

### Live PCM input

`POST /v1/streams` with JSON `{}` or the same route overrides creates an actual
`AudioStream` session **before** its audio is finished. Speech recognition must
already be configured. Send sequential `PUT /v1/streams/{id}` requests containing
mono little-endian PCM16 at 16 kHz, with `Content-Type: audio/pcm` and
`X-Hearth-Audio-Format: pcm-s16le-16000-mono`.

Each frame must contain whole, nonempty samples and be at most 6,400 bytes
(200 ms). The input queue has eight frames. HTTP **429 means that frame was not
accepted**: retry it before sending the next one. Do not reorder or send
concurrent frames. `POST /v1/streams/{id}/finish` closes input; the pipeline then
produces its actual final status. Input closure never fabricates model completion.
Status, SSE and cancellation use the ordinary session endpoints.

Canonical WAV/PCM and live inputs are limited to 120 seconds per session. WAV supports PCM
format 1, 16-bit, 16 kHz, mono only; compressed formats, stereo, other sample rates,
RF64 and incomplete/duplicate audio chunks fail explicitly. Microphone capture in the desktop app can
use its native `AudioStream` producer and `observe()` directly.

## OBS Browser Source

Start integration in Hearth and copy **OBS overlay URL**, then paste it into an
OBS Browser Source. Use a viewport such as 1920×1080 with a transparent background.
The page uses the actual session's original and translated text, including partial
segments when the selected recognition engine emits them. Inference latency and
quality still depend on the selected engines and device.

The copied URL's fragment contains a **separate, read-only caption capability**.
It is not the write API bearer token and is never a provider API key. The page
exchanges it for an HttpOnly/SameSite=Strict cookie and removes the fragment from
the page address. Fragments do not enter HTTP request paths or referrers. Treat
the OBS project/URL as private because it grants access to captions until that
server stops. Cookies are accepted only by `/overlay/events`, never by the write
API. Refreshing the page reuses the cookie; a new server needs its new OBS URL.

The desktop UI must call `observe(session)` for sessions it starts, including an
already-active session when integration is enabled. Without that call, the server
can observe only sessions it started through HTTP. Closing an observation detaches
it; it never cancels the desktop session.

## Limits and failures

- Four active HTTP-owned sessions/uploads, four SSE clients, 32 retained sessions, a
  64-event replay window and 64 queued events per client. Slow clients receive a
  `resync` with a replay watermark and disconnect rather than silently losing a
  final caption. Event streams leave HTTP workers available for input/status/cancel.
- JSON bodies are bounded to 64 KiB, 16 nesting levels and strict UTF-8. Audio
  uploads are bounded before allocation; request body reads have a 30-second
  deadline. Media uploads spool to disk through a 64 KiB buffer with a 64 MiB
  bound. Worker and pending-request queues are bounded.
- The integration view retains the most recent 256 segments and marks that
  truncation explicitly. Oversized individual transcript/audio metadata returns
  an explicit observation error rather than an empty successful result. The
  server still observes terminal state and releases uploads/admission slots.
- API errors and actual engine failures remain visible. Common credential query,
  bearer and URL-userinfo patterns are redacted from error messages. The server
  never returns saved engine options, credential references or local model paths.
- Strict loopback Host and Origin checks, no CORS, no external scripts/resources,
  no URL-based write authentication. Stop revokes the in-memory API/overlay
  credentials. It also releases server-owned uploads after cancellation joins.

HTTP tests use injected sessions to exercise the real sockets, authentication,
streaming, backpressure and lifecycle. They do not claim model or OBS execution.
The root build owns the coordinated `:desktop-integration:test` gate.

## Image reading service and website clients

The desktop can inject its existing reader without giving HTTP a separate OCR or
translation engine:

```kotlin
val integration = DesktopIntegrationServer(
    pipeline, configuration = { savedConfig },
    reading = desktopOwnedReadingAdapter,
    extensionOrigins = explicitlyPairedExtensionOrigins,
)
```

`IntegrationReadingService.start` returns an asynchronous, desktop-owned
`IntegrationReadingSession`. Its `StateFlow` publishes real outlines, recognized
text, translations and errors. `cancel()` must join native cleanup. The HTTP
server stops only its own reading sessions; it never closes the shared reader or
pipeline service. The desktop adapter must serialize native reader admission
with its own Reading screen and report a busy reader explicitly.

| Method and path | Request/result |
| --- | --- |
| `POST /v1/reading/image` | Raw PNG or JPEG, at most 16 MiB. Optional `sourceLanguage`, `targetLanguage`, `translationEnabled` query overrides. Returns HTTP 202 with an image-reading session. The selected manga reader defaults to Japanese; unsupported source languages fail before inference. |
| `GET /v1/reading` | Up to 32 retained image-reading snapshots. |
| `GET /v1/reading/{id}` | Real progress, regions with stable IDs/bounds, original text, translations, actual error and terminal state. |
| `DELETE /v1/reading/{id}` | Cancels and joins this HTTP-owned reader before returning its resulting state. |
| `GET /v1/reading/{id}/events` | Separate authenticated SSE `snapshot`, `reading` and `resync` events. Initial/resync objects contain `readings`. Does not send manga text into OBS caption events. |

Image headers are inspected before decoding: at least 8 × 8 pixels, no side above
32,768 pixels and no more than 100 megapixels. Hearth subsamples to a maximum
2,048-pixel edge before inference and rejects a MIME/format mismatch. The response
contains the decoded `width`/`height` and original `sourceWidth`/`sourceHeight`.
Region coordinates always refer to the decoded image; an external app can scale
those coordinates back to its own viewport. No screenshot file or provider
credential is returned. Snapshots contain at most 256 regions and 32,000 characters
of recognized plus translated text; invalid or oversized reader output is an
explicit `observation_failed` result, while native completion is still observed.
Images, media, caption jobs and pending uploads share the same four-active-job
limit. Image event streams share the four-client SSE limit with caption events.

For websites with accessible text, clients should use `POST /v1/sessions` with
`voiceEnabled:false`; this skips image capture and OCR. Use the image endpoint for
image-only content, such as a manga reader. Only submit a changed viewport, wait
for its result or cancel and join it before submitting another; a capture timer
must not enqueue unbounded screenshots or infer that unchanged text means model
success. Hearth's desktop screen reader owns native pass-through overlay and
capture controls; these HTTP endpoints do not grant input injection.

### Explicit browser-extension pairing

The default remains no cross-origin access. An owner can explicitly pair at most
eight exact origins through the desktop app. Accepted identities are
`chrome-extension://` followed by the browser's 32-character extension ID, or
`moz-extension://` followed by its UUID, without a trailing slash. Website origins,
wildcards and approximate matches are rejected. `DesktopIntegrationServer.validExtensionOrigin`
lets settings validate before persisting. Changing the allowlist requires restarting
the integration server and revokes its old tokens.

For a paired origin only, `/v1/` accepts a bounded OPTIONS preflight for
GET/POST/PUT/DELETE with Authorization, Content-Type, Last-Event-ID and
X-Hearth-Audio-Format headers. Actual requests **still require the copied API
bearer token**. CORS never grants cookies, OBS capabilities or an unauthenticated
write path. Every unpaired extension and every ordinary website remains blocked.
Extensions must keep the bearer in their background context and must not expose
it to webpage scripts, page storage, URL parameters or content-script DOM.

The optional reference extension is in [integrations/browser-extension](../integrations/browser-extension/README.md). It translates selected visible DOM text without OCR; pairing and activation are explicit.

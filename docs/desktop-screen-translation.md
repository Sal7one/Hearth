# Desktop screen, image and website translation

Open **Screen** in the sidebar or **Screen translation** on Home. The image and
screen paths share the selected Japanese reader and Hearth's saved translator and
destination. Reader/model changes stop a live watcher with a restart instruction;
source-of-truth changes cannot silently resume an old reader.

## Screens and manga images

**Open image** accepts bounded PNG/JPEG images. Select **Fast**, **Koharu** or
**Koharu + cleanup**, get or import that pinned bundle, and choose **Read page**.
Text and geometry appear progressively. Only translated regions receive replacement
pixels; original art and untranslated text stay visible. Original image and area
selection remain available. Japanese source is explicit; the reader is not a
general multilingual desktop OCR engine.

**Watch screen area** asks for a display, then lets you drag a region (Escape
cancels). macOS requires Screen Recording permission for Hearth. Capture bounds
use logical display coordinates, including negative-coordinate monitors; frames
are downsampled to a 2048-pixel maximum edge. The watcher checks changed, settled
content at a saved 1/2/5-second interval with one frame in flight. It checks again
after inference, discarding results if the page moved. Identical text/configuration
uses a bounded translation cache; resident models are reused.

The replacement window passes clicks to the underlying app. A separate movable
control window offers **Pause**, **Original**, **Show Hearth** and **Stop**.
Show Hearth pauses screen reading. Stopping or cancelling selection restores the
saved caption-overlay visibility; it does not rewrite that preference. Only
Hearth's registered reading/control windows briefly hide during screenshot capture
(~80 ms) to prevent self-OCR; this can flicker. This is changed-page translation,
not video-frame processing. Fast is the sensible live default. Koharu's CPU quality
pass and LaMa cleanup are still-image features with substantial memory/latency.

## Websites with selectable text

**Website text** opens Integrations. The unpacked extension in
[`integrations/browser-extension`](../integrations/browser-extension/README.md)
uses the normal saved translation pipeline without OCR. Start only for the tab you
want translated. Links, forms, input fields and page controls retain their behavior;
Stop restores untouched originals. Scrolling/dynamic content is processed in bounded
visible batches. Images/canvas/iframes/protected browser pages are outside this path.

Pair the exact extension origin using **Save browser access**, start the local
service, and copy its address and API token into the extension. Saving/removing
access stops the service and revokes the old token. The bearer token authenticates
API requests; the exact-origin allowlist is an additional browser restriction, not
an alternative to authentication. Provider keys remain in Hearth's secure store.
The chosen translator determines supported languages and local/cloud processing.

## Image service

The explicit loopback service also accepts bounded PNG/JPEG uploads at
`POST /v1/reading/image`, and returns progressive region/text/translation snapshots
through status and SSE routes. It accepts image bytes, never arbitrary filesystem
paths or remote URLs. Captures are supplied by the client; the HTTP endpoint does
not grant remote control of the desktop screen. Current image OCR source is Japanese.
See [the API contract](../desktop-integration/README.md).

UI and API readers share native admission. Requests queue without overlapping OCR;
cancel/delete joins actual work before another request acquires ownership. Uploads
and native jobs share the existing four-session cap. Each API session keeps its own
image identity, so a queued request cannot receive a previous page's regions.

## Validation boundary

Host tests cover native retirement, duplicate cleanup publication, final-call budget
reporting, screenshot bounds, stale frames, rapid pause/resume, translation cache,
transparent replacement rendering, API isolation, authentication and cancellation.
Real pinned Japanese OCR/RF-DETR/LaMa inference ran on the Mac (10/10 fixture regions;
first text ~7.8 seconds, full quality cleanup ~28.6 seconds after model loading under
host load). This is integration evidence, not a broad quality or speed benchmark.

macOS package/runtime checks and dark/light layout fixtures are separate from real
OS capture acceptance. Windows installers, NVIDIA inference and native screen
interaction need a Windows machine. Android device acceptance belongs to the owner;
its cross-app flow is described in [screen-reading.md](screen-reading.md).

[Artwork, saved paths and exact generation prompt](artwork/screen-translation.md).

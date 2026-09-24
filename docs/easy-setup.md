# Easy setup

Home has a direct **Easy setup** button. The same entry is at the
top of quick Settings and in full Settings. The wizard is optional and does not
replace the feature carousel or advanced setup.

1. Choose **On this phone** or **Cloud**.
2. Confirm the local preset, or enter a cloud connection and save.

Local selects Nemotron 3.5 ASR 0.6B and one explicit translation route. The default
in the Play build is the small Marian English→Arabic pair; Russian→English and
Chinese→English are one-tap alternatives. Each pair supports only its shown
direction. Selecting one also forces Nemotron's spoken-language hint, avoiding
automatic detection and the unknown-source local-translation failure. These pairs
were fast in a short Samsung phone test but have not passed bilingual quality review;
important names and numbers need checking. **More languages** selects Hy-MT2 Q4,
with spoken and destination language pickers. That route covers more pairs, but
its six-sentence phone run took 53 seconds, so it is not presented as a fast option.
The FOSS wizard offers the importable Hy-MT2 route and any already installed
Marian pair; it never downloads models.

Installed models are reused. One download action queues only missing files through
the existing verified downloader; originals stay in Downloads/Hearth/models or the
selected folder. A Marian package is about 235–242 MiB plus the speech model and
requires extra installation space; the broad-language two-model download is about
1.9 GB. Existing imports, hash checks, model manifests and native ownership remain
unchanged.

The page waits for both verified installations before selecting the preset. Downloads
can continue in the background; returning to the requested setup finishes selection.
Finish later cancels that automatic selection, not the downloads; Downloads provides
cancellation and retry controls. Errors remain visible. Active caption, reading or
local inference sessions must stop before a preset is applied. Applying the local
preset also chooses its translator for the shared conversation/text/camera setup.
On the Samsung SM-S908E, the Play QA chooser displayed all three installed Marian
pairs, switching to Chinese→English updated the installed state, and applying it
opened Live captions with Nemotron, forced Chinese, English output and Translation
selected. The earlier Russian→English setup was then restored. This was a setup
route check, separate from the live-caption smoke in
[Marian phone smoke](marian-phone-smoke-2026-09-23.md).

Cloud contains only OpenAI, OpenRouter and Local server. OpenAI uses Hearth's existing
live translation adapter and the same searchable destination picker as captions.
Language names are suggestions; enter a 2–3 letter code when needed. OpenAI validates
the requested language on connection, so the picker does not promise every ISO
language is supported. Saved choices reopen at their selected row. OpenRouter uses the
existing speech model preset and starts in original-language captions. Local server
accepts an HTTPS OpenAI-compatible speech API base URL and model ID, with an optional
key; it also starts in captions. Additional translation routes, source hints,
providers and voice/OCR setup remain in full Settings. Cloud setup is a saved
configuration, not a claim that a key, quota or remote inference was tested.

Keys are password-masked, never placed in saved instance state, and use the existing
key store. Switching providers clears the draft. A saved key is reused only for the
same provider and exact normalized API base URL; it is never carried to a new server.
Only explicitly selected Custom + Batch speech can omit a key. Empty credentials
are omitted from the request header. Public providers and all streaming modes still
require keys. No cloud call or audio capture starts just by opening or saving setup.

FOSS keeps the Cloud card disabled, has no external model links/download actions,
and offers the existing file import path for Nemotron and Hy-MT2. Its network gate
and permission manifest remain intact.

`EasySetupPreset` owns pure choice/endpoint/key-reuse policies. `EasySetupActions`
uses existing stores/downloads. Separate Compose files own the chooser, local and
cloud pages. Six new host tests cover preset routing, preserved appearance/audio,
invalid choices, endpoint validation, credential separation and the actual speech
HTTP client's empty-key/FOSS behavior. Navigation tests cover the new page (15).

## Navigation and Home visibility (0.21.3)

Header Back and Android Back share `EasySetupStep`: confirmation → its local/cloud
form → chooser → caller. Downloads remains a child route and returns to the current
setup step. Home explicitly exits the wizard; reopening Easy setup starts at the
chooser. A normal Activity recreation restores the active step. Choosing full
Settings explicitly switches to advanced settings; it is not another wizard step.
No API key is added to saved navigation state.

On Home, the left chevron collapses the entry to a 48 dp left-edge tab. Tap that tab
to expand it. `hearth-home` SharedPreferences stores `easy-setup-collapsed` (default
false), independently of the remembered service card. Both directions persist.
Settings and quick Settings retain their setup links while Home's entry is collapsed.
Controls have named accessibility actions and minimum 48 dp touch targets.

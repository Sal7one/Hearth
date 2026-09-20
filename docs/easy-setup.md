# Easy setup

Home has a direct **Easy setup** button. The same entry is at the
top of quick Settings and in full Settings. The wizard is optional and does not
replace the feature carousel or advanced setup.

1. Choose **On this phone** or **Cloud**.
2. Confirm the local preset, or enter a cloud connection and save.

Local automatically selects Nemotron 3.5 ASR 0.6B and Hy-MT2 1.8B Q4, enables
translation and uses automatic source recognition. The destination remains visible
and editable before applying. Installed models are reused. One download action
queues only missing files through the existing verified downloader; originals stay
in Downloads/Hearth/models or the selected folder. The two original downloads total
about 1.9 GB; installation needs additional space. Existing imports, hash checks,
model manifests and native ownership remain unchanged.

The page waits for both verified installations before selecting the preset. Downloads
can continue in the background; returning to the requested setup finishes selection.
Finish later cancels that automatic selection, not the downloads; Downloads provides
cancellation and retry controls. Errors remain visible. Active caption, reading or
local inference sessions must stop before a preset is applied. Applying the local
preset also chooses its translator for the shared conversation/text/camera setup.

Cloud contains only OpenAI, OpenRouter and Local server. OpenAI uses Hearth's existing
live translation adapter and its supported destination choices. OpenRouter uses the
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
and offers the existing file import path for the two local models. Its network gate
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

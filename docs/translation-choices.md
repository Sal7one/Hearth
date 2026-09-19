# Translation choices — Hearth 0.15.0

The same **Translator** chooser is available in live captions (including its
bubble), typed translation, conversation/face-to-face settings, Camera settings,
and the reading-overlay start screen. Settings → Translation provides the shared
model/connection entry point.

## What is available

- On device: all pinned HY-MT1.5, Hy-MT2 and TranslateGemma artifacts in the existing
  catalog. Installed models are listed first. Missing models open the Translation
  model page with that artifact selected for download/import.
- Play build: ML Kit language packs, Google Cloud Translation, Microsoft Azure
  Translator, DeepL and LibreTranslate (including a user's own HTTPS server).
- FOSS: local model choices only; no cloud connection UI or ML Kit. Existing network
  guards and the permission-free network manifest contract remain intact.

The chooser displays installation/connection state, download size, language
coverage and the selected source → target pair where known. Connection discovery
is required before a cloud option is selectable. Discovery is not proof of billing,
quota or credential validity for translation. Actual request errors remain visible.

Connections/keys are configured once. Keys stay in the existing Android Keystore
storage. Opening a chooser or saving a connection does not switch another feature.
Provider choices are deliberately preserved on upgrade:

- Conversation, Face to face and typed text share their existing selection.
- Camera and screen reading share their existing selection.
- Captions keep the existing speech-provider/legacy route until explicitly changed.
- The selected local model remains shared, as before.

**Use this translator across Hearth** explicitly applies the selected provider and
local model to all these features. It does not start capture, turn on camera
translation, or change a CC-only caption session to translation mode. An active
caption session may rebuild its translation stage; active camera/reading sessions
keep their frozen translator until restarted. Per-feature selection remains
available after applying a common choice.

## Captions

An explicit text-translator selection sends finalized source text through the
existing bounded `CaptionTranslationBridge`. Cloud and local adapters implement
the same cancellable translator interface; line identity, stale generation checks,
queue limits, Clear and Stop behavior are reused.

Explicit text translation precedes integrated speech translation. OpenAI/Soniox
then run recognition, rather than producing translated text and translating it
again. Choosing **Speech provider / existing route** restores the previous route.
The existing OpenAI Live Arabic/English shortcuts explicitly select integrated
translation. Original-caption mode never activates a translation stage.

Both streaming finals and legacy Whisper/batch caption promotions enter the text
bridge. Choose a spoken language for adapters which do not report a detected
language. Unknown/mixed language produces a notice; the app does not guess a
source language. Vosk imports currently lack usable source-language metadata, so
the explicit text stage stays CC-only with a notice. Use another speech engine for
this path. Legacy Marian/English-pivot behavior remains available through
the existing route and advanced model setup.

A cloud bridge snapshots credentials and discovered capabilities before opening.
Changing a saved key does not mutate an in-flight request; restart the session to
use updated credentials. Cloud target pickers use discovered directional coverage,
including LibreTranslate's source-specific targets. No model/provider, runtime or
JNI library was added by this UI/routing change.

## Verification

Host coverage includes explicit provider routing across all speech engines,
unchanged integrated defaults, CC-only behavior, Whisper avoiding an English
pivot for explicit translation, directed cloud language choices, missing capability
handling, local-pair rejection and preferences migration/round-trip. A production
caption queue plus the real DeepL adapter is exercised using an intercepted HTTP
response: a Russian source keeps its original segment ID and receives Arabic text.
This test does not make a paid API call or measure translation quality.

Final build and phone results: see validation-v29.md.

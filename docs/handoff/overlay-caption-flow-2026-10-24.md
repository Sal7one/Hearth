# Android overlay caption flow and exact model selection

Prepared 24 October 2026. Android candidate `v0.30.0-rc.15`, version
`0.30.0` / code **111**, preserves the existing QA package and signing identity.
The owner has published RC15; its APKs are bound to
`cfb3d3c218b13a11fd3166c3c80efce2a180cd5d`. Model packages, native runtimes
and desktop production source were unchanged by this candidate.

## What changed

The live speech overlay previously put each short recognition window on its own
row, leaving unused horizontal space and scrolling too often. `CaptionLiveText`
now joins adjacent captions of the same role into a full-width paragraph with
natural word wrapping. Original/translation pairs retain chronological order;
an incoming source caption stays below older translations while translation is
pending. This changes presentation, not engine output or utterance finalization.

Arabic translations align to the right and use content-aware RTL direction.
Original text resolves its own direction independently of the target language.
Both normal and minimal-header layouts use this rendering. Older retained chunks
remain faded and the newest chunk remains emphasized.

Read history still uses separate entries and a held snapshot of at most 120
segments. Incoming captions do not move that held view. The existing history
setting still limits **previous segments**, not visual wrapped lines; zero
retains the current final caption. Existing pending-original, failed-translation,
display-mode and translation-provenance rules remain authoritative.

Home's advanced speech controls and overlay options → CC & translation now show
the exact installed local speech checkpoint and allow switching within its
engine family. Phonon 2 and multilingual Phonon 2.5 therefore have distinct
choices under **Phonon 2 / 2.5 (local)**. Selecting one preserves the independent
translator, voice, target language and overlay appearance.

Inventory checks read manifests without opening native runtimes or hashing model
weights. Missing explicit selections show an error rather than borrowing another
checkpoint's name or language coverage. Inventory/language metadata refresh on
resume and installation binding changes. TV local-model restrictions remain.

Phonon 2.5 Auto recognizes speech across its 25 supported European languages,
but the current TDT adapter does not return a detected language ID. Choose the
known spoken language when using translation. That declaration supplies downstream
translation context; it does not force the speech decoder. Arabic and Chinese
speech remain unsupported by this checkpoint. See `docs/models/phonon-2.5.md`.

## Validation and delivery

The required `./gradlew test :app:compilePlayQaKotlin
:app:compileFossQaKotlin` passed before committing: all six Android variants ran
1,146 cases each with zero failures/errors (25 FOSS and four Play optional skips
per variant). The patch adds 21 regression cases:

- Nine pure presentation cases cover wrapping inputs, bounds, fading,
  chronological bilingual output, pending/failure rules and provenance.
- Five actual-overlay Compose cases use Robolectric SDK 35 native font shaping,
  verify nonzero glyph dimensions, strict multi-line wrapping, Arabic alignment,
  latest-caption visibility and held-history scroll position.
- Seven picker Compose cases cover Home and floating settings, exact selection,
  persistence, malformed/missing manifests, resume refresh, disabled controls,
  TV policy and independent translation/appearance settings.

Build both QA flavors after committing; verify all ten ABI APKs, embedded source
revision, FOSS permissions, signatures, native packaging and in-place upgrade
identity against RC14. Attach individual APKs, checksums and bounded validation
receipts to the RC15 draft. No combined release ZIP. No GitHub Actions runs.

These are host/Compose tests. Actual Android device behavior remains for owner
testing; this patch makes no new CPU/GPU inference or native performance claim.

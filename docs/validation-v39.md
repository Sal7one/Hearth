# Hearth 0.21.2 / code 39 — manga scrolling and translation reuse

20 September 2026. v79 QA; no native/model artifact changes.

## Findings and changes

The controller retained only 32 exact-string translations although a positioned
page allows up to 64 boxes. A dense page could evict its own early boxes, then
retranslate the entire page on the next capture. Whitespace differences also
missed the cache. Positioned results were published in sequence, so an uncached
first box held up every cached box after it.

A session-local LRU now retains up to 256 boxes / 96,000 source+result characters.
Keys normalize canonical Unicode composition and layout whitespace, including
gaps between Japanese/CJK characters. They preserve words, numbers, case and
punctuation. Cache lifetime follows the controller's fixed language/provider/model
snapshot; a new session has no inherited translations. Blank/error results are
not cached. The cache is memory-only, not persisted or exported.

Positioned translation first publishes every cached result using current OCR
coordinates, then fills in new boxes in source order. Obsolete page revisions
never publish. Scrolling still clears obsolete placements: this change does not
pretend old screen coordinates remain valid.

Motion detection previously reset its baseline after every changed frame, which
skipped the next scroll sample. It now keeps the observed baseline and waits for
continuous motion to settle. A superseded OCR frame no longer clears the newer
capture's processing flag; already-queued obsolete frames skip recognition.

## Automated evidence

Six new cases cover cache/queue behavior and motion settling. The dense-page
fixture makes 64 translator calls for the initial page and **zero additional
calls** when those same 64 boxes move and their whitespace changes. Cached boxes
publish before a slow new box. Changed numbers, punctuation and words do not
reuse another text's translation. This measures avoided translator calls, not
phone inference latency or translation quality.

OCR still runs after scrolling to locate text. Genuine OCR character changes and
new text can need fresh translation; the patch does not claim to repair model
recognition quality or translate an entire new page instantly.

Required gates passed: `./gradlew test :app:compilePlayQaKotlin
:app:compileFossQaKotlin :app:assemblePlayQa :app:assembleFossQa`. All six app
suites contain 275 tests, zero failures/errors; existing skips are one per Play
variant and two per FOSS variant. Common JNI debug/release: 74 cases each.
Both final APKs pass `python3 scripts/verify-release.py`.

## Phone check

Samsung SM-S908E / Android 16, installed Meiki Japanese OCR and Hy-MT2 Q4,
Japanese → English, Page changes enabled. A locally served, original fixture
contains two Japanese sentences in separate speech bubbles. Both were translated
on the transparent overlay. A small scroll relocated both English results; their
wording stayed identical. Scrolling back restored the same results again. The
scroll captures were taken after a two-second wait; this is a functional check,
not a precise latency benchmark. No cloud translator was used.

The fixture does not establish accuracy on arbitrary scanned artwork, vertical
text, low contrast or partially visible bubbles. Genuine OCR text changes may
still produce a different translation. No Manga OCR or Paddle timing comparison
was performed in this patch.

## Delivery

Both APKs are ARM64, release-optimized, debug-signed QA builds using the same
package. Install over the existing app; source stays local until the owner pushes
the matching commit. GitHub delivery remains a private binary draft.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `hearth-v79-stable-reading-cloud.apk` | 36,039,121 | `39390efcad6e23f24d8d6dead6697e74645810b9293b124136499dd87abd2b14` |
| `hearth-v79-stable-reading-offline.apk` | 28,189,843 | `5c192f6a699f44988f903c93c91cba310ed152bcb3b98cb678c141b3bf964509` |

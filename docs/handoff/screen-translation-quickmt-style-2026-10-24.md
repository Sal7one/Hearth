# Screen translation: QuickMT capacity and box appearance

Android candidate RC17, version `0.30.0` / code **113**. Preserve the existing QA
package/signer. RC16 remains a draft; README downloads point to public RC15.

## Translation

An actual pinned QuickMT English→Arabic model reproduced the reported exception
on a synthetic 1,912-character coffee article. A raw native request exhausted its
384-token output budget. The bounded session translated the article to 1,613
Arabic characters in 4,117 ms including model opening on this Mac. This proves
host correctness, not Android device speed or GPU execution.

- `hearth-core/.../Ct2TextTranslation.kt` is shared by Android and desktop CT2
  sessions. Requests are at most 3,000 UTF-16 characters; initial pieces are at
  most 320 characters with sentence/word boundaries and surrogate-pair safety.
- Only the two exact native input/output capacity errors permit smaller retries.
  A failing piece of 64 characters or less ends with
  `TranslationContentLimitException`; at most 64 native calls are allowed.
- A 20-second request budget is checked between calls. The unchanged native
  decoder still has its own 20-second deadline. Kotlin cannot preempt a blocking
  call at the request-budget boundary; do not claim a hard 20-second total.
- Results stay local until every part succeeds. Empty output, deadline, runtime,
  model-integrity and cancellation errors are not silently retried or published.
- Android checks coroutine cancellation and native-handle identity between parts.
  Desktop also suppresses results after cancel/close. No JNI protocol, C++ source,
  native binary or model package was changed.
- `OcrPageTranslation.kt` treats the typed content-limit failure like an isolated
  empty-output failure: preserve that original box, finish healthy boxes, then
  report partial failure. Successful region caches and geometry remain intact;
  provider/runtime/integrity errors still fail fast.

## Appearance

Setup Options and live Appearance share `ReadingOverlayPreferences`, backed by
the existing `UserPreferences` DataStore. Defaults: replacement off, width/height
100%, light box, dark text, 95% box background opacity.

- `replace-text`: opt-in page colour matching. Manga regions reuse existing
  `MangaPaint` masks/patches and colours. General OCR samples the admitted clean
  capture; no second capture, OCR run or translation is needed for styling.
- `box-width-percent` 50–200 and `box-height-percent` 50–300 scale each source box.
  `box-background-argb`, `box-text-argb`, `box-opacity` 10–100 store its palette.
  Light, Dark, Warm and High contrast presets preserve replacement and geometry;
  custom RGB controls are collapsed initially. Colours remain saved while matching
  is enabled. Sampling is approximate on photographic backgrounds.
- `ReadingBoxLayout` clamps labels to the viewport and prefers nearby free space.
  Tiny labels have a glyph/padding readability floor; automatic height growth is
  bounded. Dense layouts can still have unavoidable overlap.
- Text wraps without ellipsis or a line-count cap. Long boxes scroll when their
  content cannot fit; turn off touch pass-through to scroll inside them. Opening
  full-text controls remains a tap, not a drag. Held scroll survives later
  boxes and cleanup refinements, and scrolling paragraphs keep the chosen text size.
- `ReadingColourSamples` keeps a maximum 512×512 grid and 128 sampled-region
  palettes. The active and previous completed page can retain at most two grids;
  full screenshot bitmaps are still recycled by their existing owners.
- Motion exclusions include actual adjusted labels, cleanup pixels and outlines.
  Paint identity includes palette, geometry and scroll position. Do not revert to
  source OCR bounds alone: doing so causes self-triggered page refresh loops.

## Verification

Required Android test/compile gate, six desktop targets, focused OCR/renderer/UI
checks and the real pinned QuickMT JNI test are recorded with the candidate.
The Android gate passes 1,196 cases per app variant (six variants; 37 new cases).
Shared CT2 adds 12 core cases and four JNI-wrapper cases. The six desktop targets
pass 367 cases with 38 optional skips; the three opt-in real CT2 cases pass.
The real-model fixture is synthetic text at
`desktop-runtime/src/test/resources/translation/quickmt-coffee-article.txt`.
Reproduce with `HEARTH_CT2_SMOKE=1` and `HEARTH_SMOKE_CT2_DIR` pointing to the
complete pinned QuickMT directory; run `DesktopCt2RealSmokeTest`.

Build both QA variants after committing, verify all ten APKs and their in-place
upgrade identity against RC16, and upload individual assets. Owner tests the phone;
no adb or GitHub Actions runs. No desktop installer is included in this candidate.

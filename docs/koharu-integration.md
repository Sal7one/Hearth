# Koharu vision migration

Android and desktop now have a real Koharu segmentation/cleanup path alongside the
small automatic-page reader. This is a native ONNX migration of the image pipeline;
it does not embed Koharu's Tauri frontend or its Rust/Torch process runtime.

## Available pipelines

| Choice | Required model bytes | Processing |
| --- | ---: | --- |
| Automatic manga page | 127,716,506 | Compact RT-DETR rectangles → Manga OCR → Hearth translation |
| Koharu manga segmentation | 276,158,509 | Quick compact outlines → full RF-DETR instance masks → panel/bubble/text scene order → Manga OCR → Hearth translation → measured flat-bubble cleanup and fitted text |
| Koharu manga + cleanup | 483,641,153 | The segmentation path plus real LaMa neural reconstruction for textured/non-uniform regions |

Models remain explicit downloads/imports. Existing manual and compact profile
preferences are retained. TVs still cannot load local models. FOSS has no network
client or delegated downloader. Every artifact is size/hash verified before native
initialization; repaired/downloaded models do not silently replace the active setup.

Android: Camera or Screen & manga → Japanese → **Compact**, **Koharu**, or
**Koharu + cleanup**. These controls and Models → Camera use the same saved
`OcrPreferences` selection. Downloading files does not activate another reader;
Choose does. Missing files are explained before starting. Restarting an existing
reading overlay stops its old projection and waits for native cleanup, then loads
the newly saved reader and translator.
Imported/captured still pages use per-region translation and display cleanup plus
fitted text over the retained original bitmap. Original hides all cleanup/typeset
layers; tapping a translated balloon opens its full source, translation, copy and
read-aloud controls. The cross-app reading overlay uses the same selected profile
and publishes quick outlines before the full quality pass. Desktop exposes the bundles in Models →
Reading, with verified folder imports supported by the same reader runtime.

## What was migrated

Source: `koharu-rs/koharu` revision
`73abcafab1507e1c228c36ed54ba84e5e1cd6ff8`, MIT option. We inspected the pipeline,
scene hierarchy, renderer and ML processors rather than treating its CLI as a
drop-in library. Adapted code retains the full upstream copyright/license notice.

- RF-DETR RGB/ImageNet preprocessing, 1152×1152 bilinear resize, exact ONNX output
  validation, global top-300 query/class selection, per-class thresholds and NMS.
- Real instance masks: each 288×288 mask is bilinearly projected onto the source
  page and thresholded at zero. Bounded ordered RLE retains masks without an
  N×page allocation. Panels, balloons, dialogue and SFX classes remain distinct.
- Mask-based dialogue membership and panel → bubble → text scene ordering.
  Adjacent balloons stay separate. SFX are detected but are not fed into the
  dialogue OCR model as if it were a specialized SFX recognizer.
- The existing pinned Manga OCR encoder/decoder read each dialogue crop; Hearth's
  selected translator remains authoritative. No Koharu model downloader, default
  Gemma translator or API-key configuration bypasses Hearth's policy.
- Flat bubbles use measured median background color with a deviation check and
  only fill text-mask pixels inside the balloon. Patterned artwork is not flat
  filled. When the cleanup bundle is selected, LaMa reconstructs complex regions
  with source context; sparse-alpha patches modify only the requested mask.
- The performance port follows Koharu's eight-connected mask components, nearby
  component coalescing, 512-pixel tile cores and 128-pixel context. OCR publishes
  every transcript before one page-wide cleanup request. Uniform fills prepare
  the private neural context first; the captured original is never modified.
  Each displayed patch is clipped to its own translated text mask, preserving
  holes and leaving untranslated balloons visible.
- Mask union sorts primitive packed spans instead of allocating a `Triple` and
  boxed integers for every run. Flat-background statistics use exact histograms.
  RF-DETR projection skips nonpositive support without changing mask pixels.
- Android TextView and desktop Compose fit translations with content-direction
  bidi shaping, so Arabic and English use the platform's shaping/font fallback.
  Original-image comparison and full source/translation text remain available.
- Preview, full segmentation, OCR and cleanup are visible stages. Native handles
  retain LeaseRegistry ownership and cancellation. Old page work cannot publish
  into a replacement capture; cleanup failure preserves already recognized text
  and surfaces the actual engine error.
- Android prepares a tight alpha mask or neural bitmap once per immutable paint.
  Redraw does one bitmap operation per translated balloon, instead of thousands
  of span draw calls or bitmap allocation inside `onDraw`. Detach recycles the
  cache; reattach rebuilds it. Slow translations receive the latest exact-box
  cleanup paint without another translation call. General OCR's grouped text
  boxes retain their separate behavior.

## Artifact provenance and terms

Segmentation: `ShiniShiho/koharu-layout-rfdetr-seg-2xl-1152-onnx`, revision
`bfbbd4e5ab34a50459865074fa044da496cebb57`, `rfdetr-seg-2xlarge.onnx`,
148,442,003 bytes, SHA-256
`7cc10d4316371946b8441da3512261a8e148b129abcdb0ea6235ed1d1d06d351`.
Source model: `mayocream/koharu-layout-rfdetr-seg-2xl-1152` revision
`aed55fdb8ca953c6bec33cf6ed6dd52a9b72bfa2`. The weights declare custom/other
terms and retain Manga109 academic-use data conditions. Permissive code licenses
must not be presented as permissive weight/data licensing.

Cleanup: `mayocream/lama-manga-onnx`, revision
`b55497aadbfcb9740e1ed16f008268d71b4f3f79`, `lama-manga.onnx`,
207,482,644 bytes, SHA-256
`4512adab295ee5a5e02ccd1bdf8d45dccbac88309d9cff1532ffd5de876f02a4`,
publisher model card Apache-2.0. Fixed input: RGB float32 image `[1,3,512,512]`
and binary float32 mask `[1,1,512,512]`; output `[1,3,512,512]`. Coalesced component
tiles get 128 source-pixel context, resizing and sparse compositing. The export is from the
same publisher as Koharu's LaMa family, but is an older ONNX model, not claimed
byte-equivalent to the newer Rust safetensors checkpoint.

Pins are shared in `MangaArtifacts.kt`. Packaged
`ocr/koharu-provenance.json` records graph signatures, source/export revisions,
hashes and license distinctions. No model weights or third-party pages are in Git.

## Validation and limits

Real model validation on this Mac (Apple M4 Pro, ONNX Runtime 1.20.1 CPU).
The first three timings below describe the earlier RC1 implementation:

- Optimized native RF-DETR with four intra-op threads: **4,910 ms** for a private
  770×1080 page, 31 layout instances spanning all four classes, real projected
  masks with valid geometry. Blank-page text rejection and cancellation passed.
- An earlier unoptimized smoke binary took 42,563 ms; this is not the Release
  performance number. Desktop/Android Release builds use optimized C++.
- Full Kotlin/JNI reader with all five pinned files: load **4,709 ms**, full
  mask publication **4,918 ms** after loading, **10/10 regions recognized in
  32,683 ms** including cleanup. The quick preview is published first. This is
  one integration page, not a broad quality benchmark.
- LaMa: **3,018 ms** for a synthetic gradient with three glyph-like strokes.
  1,080/1,080 masked pixels reconstructed; every unmasked pixel preserved;
  cancellation passed. This is runtime correctness evidence, not a broad image
  quality benchmark.
- Performance port: nine synthetic regions using the real pinned LaMa model took
  **26,585 ms** as separate calls and **6,298 ms** as a coalesced union request.
  All 5,400 requested pixels were reconstructed and unmasked pixels were retained.
  This is a cleanup-stage comparison, not an Android or end-to-end speed claim.
  The reproducible model-free-source harness is
  `common-jni/src/main/cpp/ocr/tests/lama_page_smoke.cpp`; pass the verified LaMa
  file and `both`, `separate`, or `union`. It generates its own synthetic page.
- Current full Kotlin/JNI page run: load **4,693 ms**, first outline **95 ms**,
  full detection **4,975 ms**, first transcript **5,068 ms**, all OCR completed
  before cleanup started at **5,923 ms**, full cleanup completed at **17,876 ms**.
  All 10 regions were recognized (one flat fill, nine neural patches). The
  previous recorded run took 32,703 ms. Times exclude translation and model load;
  these are single runs on one private page, not a broad quality/speed ranking.
- We tested a three-graph cached FP16 Manga OCR export before considering new
  model cards. Across the ten private-page crops it took 3,333 ms versus 883 ms
  for the current INT8 reader. An 88-token synthetic paragraph also ran slower
  and changed the text. That experiment was removed from production; the existing
  pinned INT8 reader and installed model files are retained.
- Shared host scene tests cover invalid/overlapping spans, mask intersection and
  clipping, panel order, mask containment, patterned-background rejection,
  crop transforms, preliminary outlines, neural cleanup ownership and preserved
  source text on cleanup failure. Existing compact-reader tests remain intact.

RF-DETR can use around 2 GiB of process memory during inference. Cleanup adds
another resident model. These are opt-in quality bundles; compact remains the
small default. A page is limited to 2048 pixels per side, 128 layout elements,
64 dialogue regions, bounded mask complexity, 16 MiB of cleanup patches and an
explicit 180-second cooperative budget. A native kernel can outlast the budget;
close waits for its owned lease. Recognition/translation limits remain intact.

This migration does not implement Koharu's diffusion inpainters, font detector,
specialized SFX OCR, scene editor, PDF/project/PSD/archive tooling, or all renderer
polygon/rotation/typography features. Text currently fits axis-aligned regions;
very long translations can be clipped in a small balloon, with full text retained
in the text panel. LaMa's fixed 512-pixel input can lose detail within a large tile
and its context. CPU is the only advertised backend; Android owner tests, Arabic/English
visual acceptance and Windows native inference remain required. These limits are
not hidden behind a claim of full upstream feature parity.

Sources: [Koharu](https://github.com/koharu-rs/koharu),
[RF-DETR ONNX export](https://huggingface.co/ShiniShiho/koharu-layout-rfdetr-seg-2xl-1152-onnx),
[source RF-DETR model](https://huggingface.co/mayocream/koharu-layout-rfdetr-seg-2xl-1152),
[LaMa manga ONNX](https://huggingface.co/mayocream/lama-manga-onnx),
[original LaMa](https://github.com/advimman/lama).

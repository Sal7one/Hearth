# Android manga performance and Koharu processing port

Candidate: **0.30.0 / code 100**, **v0.30.0-rc.3**, branch
`codex/hearth-desktop-kmp`. Existing QA application ID and signing identity are
retained. This extends RC2; its benchmark inputs, resume and language experiments
remain included. No GitHub Actions or adb were used.

## What was wrong

The earlier implementation already ran real RF-DETR and LaMa weights, but its
execution graph was wrong for responsive page reading. Every bubble waited for
its own LaMa call before its text could publish. Nearby bubbles repeated the
same model inference. Android then expanded every mask span into drawing calls
on each redraw. The standalone model library could download a reader but had no
selection action; restarting an active overlay could keep its previous setup.

## Implemented port and integration

- Adapted Koharu revision `73abcafab1507e1c228c36ed54ba84e5e1cd6ff8`'s
  eight-connected components, nearby component coalescing, 512-pixel tile cores,
  128-pixel context and deterministic masked tile ownership. Source attribution
  and the upstream MIT notice remain packaged.
- Publish all OCR transcripts progressively before one combined complex-mask
  cleanup. Translation can start while later bubbles are read. Flat backgrounds
  prepare a private neural context first. The retained original stays untouched.
- Clip neural output to each translated region's own mask, retaining holes and
  leaving untranslated lettering visible. Refresh paint on an exact source-box
  match when translation finishes; preserve general OCR's grouped text behavior.
- Use primitive mask union, histogram background statistics and exact positive
  support projection. Preserve page, region, mask, tile, patch and cooperative
  time budgets. Actual errors and completed text remain visible on failure.
- Prepare tight alpha/ARGB bitmaps outside Android `onDraw`; reuse them for
  redraws and release/rebuild across detach/attach. Preserve Original and taps.
- Camera and Screen & manga expose Compact / Koharu / Koharu + cleanup and use
  the same saved `OcrPreferences`. Models → Camera now selects that same reader.
  Missing files and processing stages have English, Arabic and Chinese labels.
  Restart stops the previous projection and awaits native lease cleanup before
  opening the newly saved setup.

Existing model IDs, weights, hashes, import locations, FOSS acquisition policy,
translator selection, speech engines and the single `:hearth-tts` boundary remain
intact. This update needs no new model downloads for already installed bundles.

## Measured evidence and rejected experiment

Apple M4 Pro, CPU, ONNX Runtime 1.20.1, pinned LaMa weights: the shipped synthetic
`lama_page_smoke.cpp` harness measured **26,585 ms** for nine separate requests
and **6,298 ms** for the combined mask. Both reconstructed all **5,400** requested
pixels with full alpha and no outside-mask publication. This is a cleanup-stage
measurement, not a phone benchmark or a broad image-quality score.

The RF-DETR optimization retained all 31 fixture detections and exact mask-span
counts. Its approximately 4.9-second inference dominates layout; support projection
does not justify a claimed whole-detector speedup.

The complete Kotlin/JNI reader on the private 770×1080 page measured load
**4,693 ms**, first outline **95 ms**, full detection **4,975 ms**, first text
**5,068 ms**, cleanup start **5,923 ms** and total **17,876 ms** after loading.
All ten regions were recognized, with one flat fill and nine neural patches.
The earlier recorded integration run was **32,703 ms**. Translation is excluded;
these are single integration runs, not Android performance or a quality ranking.

A publicly available three-graph cached FP16 Manga OCR export was implemented and
tested before considering new cards. The current INT8 reader took **883 ms** over
ten private-page crops; cached FP16 took **3,333 ms** with matching crop outputs.
An 88-token synthetic paragraph took **530 ms** versus **944 ms** and changed the
text. All experimental production code, profiles and cards were removed. The
existing INT8 reader remains authoritative.

## Validation boundary and owner acceptance

Required Android unit/compile gates and both QA assemblies passed after the final
source edits. Each of the six Android variants reports **843 cases**, zero
failures/errors (3 Play / 15 FOSS intentional skips). The shared manga scene suite
has **16 passing cases** on Android and desktop. Debug and RelWithDebInfo native
builds completed for all four ABIs; common/speech/OCR sanitizer host suites passed.
All six desktop suites passed (**279 cases**, 35 opt-in/platform skips), including
the real page fixture. The actual macOS packaged launcher/JRE/native-adapter
self-check passed; this did not request live capture permission or open a UI.
All ten QA APKs passed source/metadata, signing, ABI/alignment and permission
verification. Play/FOSS ARM64 update identities were compared with RC2, code
**99 → 100**, with the same QA certificate. Release evidence is
staged in `build/manga-revamp/evidence`; packaged files are checked against source,
native pins, signer identity, ABI/alignment and FOSS permissions before delivery.

This is the manga recognition/cleanup processing flow, not Koharu's Tauri editor,
Rust/Torch distribution, diffusion inpainters, SFX-specific OCR or complete
polygon typography. Full text remains available by tapping a balloon. RF-DETR
still has substantial CPU memory use. GPU inference and Android timing are not
claimed. No Windows installer or live Android device was exercised here.

Owner test: update the existing QA app with Play ARM64. Select Japanese → Koharu
or Koharu + cleanup in Camera or Screen & manga, confirm the same choice in
Models → Camera, then capture a settled multi-bubble page. Check quick outlines,
text arriving before cleanup, Arabic/English layout, Original, full-text taps,
scroll cancellation and restarting after changing the model. Use Compact when
comparing the lightweight path. Report phone timings separately from Mac results.

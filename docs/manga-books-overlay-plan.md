# Manga and books: screen translation plan

Updated 19 September 2026. Owner-directed refinement of [screen overlay research](screen-overlay-research-2026-09-19.md). Status: OCR adapters/model downloads and still-image drawing shipped in 0.12.0. The 0.13.0 implementation adds cross-app screen capture, a reading panel, region drawing, automatic triggers and optional scroll/volume shortcuts. **0.13.1 retires those Accessibility-based shortcuts after a Play Protect installation block; manual and page-change modes remain.** See [current behavior and limits](screen-reading.md). See [adapter details](manga-ocr-adapters.md).

## Scope and reading experience

Translate manga, webtoons and books displayed in another Android app. Reuse Hearth's local/cloud text translators, language capabilities, encrypted connections, public model downloads, history and shared TTS. First delivery is an overlay for existing readers, not a new EPUB/CBZ library or book downloader.

Provide **Manga**, **Scrolling comic**, and **Book** presets. Each exposes the same compact controls, with sensible reading direction and refresh defaults. Preserve the five tabs; entry lives under Camera → Screen. Presets are editable and can be remembered for a chosen reader app.

Translations appear near recognized text, with a readable panel for overflow, original/translated comparison, TalkBack, Copy and Read aloud. Manual results stay until dismissed. When content moves, hide untracked in-place translations immediately so old words do not cover new bubbles; keep the previous result in history. Show the source while translation is pending. Do not shrink Arabic text into illegibility to force it inside a narrow Japanese bubble.

## Translation triggers

| Mode | Behavior | Controls |
| --- | --- | --- |
| Button | Floating Translate button captures the current settled view. A manual request overrides the automatic distance threshold. | Whole page / selected area; Translate, Retry, Clear, Pause and Stop |
| After scrolling (deferred) | Accumulate movement in the active reader and translate after the configured amount, once scrolling stops. | Distance in visible-screen heights; settle delay; minimum interval between requests |
| After N scrolls (deferred) | Count distinct movement bursts, not individual Android scroll events. Translate the current view after the Nth burst settles. | 1–5 bursts; settle delay; only available when observation is reliable |
| After page turn | Observe a changed page following the user's normal swipe/tap, wait for its transition to finish, then translate. | Horizontal/vertical navigation; reading direction; settle delay |
| Volume shortcut (deferred) | Optional Volume Up press triggers translation while the selected reader session is active. | Off by default; named key choice; explain any conflict with reader page-turn keys |
| Share | Android Share → Hearth accepts a screenshot or selected text. Text bypasses OCR. | Reuses the same translator and result controls |

Proposed starting preset: after **0.75 screen heights**, wait **500 ms** without movement, with at least **1 second** between automatic requests. These are adjustable UX starting values, not measured performance guarantees. Manual mode remains available regardless of automatic-trigger support.

Use a stable scroll container/window identity, discard invalid deltas, and reset accumulation when changing reader, region, orientation or mode. Direction reversal must not add unlimited distance from small back-and-forth jitter. Failed translations do not mark text successfully translated: Retry must work without requiring another scroll.

Android accessibility provides scroll events and optional scroll deltas, but a reader may omit usable values or render pages in a canvas. Never label an image-change estimate as exact scroll distance. Without suitable events, offer **When the page changes** using sampled image changes and settling, or manual mode. Observing a page change does not require intercepting the user's swipe or injecting a replacement gesture. [Scroll records](https://developer.android.com/reference/android/view/accessibility/AccessibilityRecord#getScrollDeltaY()), [scroll events](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent#TYPE_VIEW_SCROLLED).

A normal background overlay cannot universally receive another app's volume keys. The deferred shortcut would need an explicitly enabled accessibility key-filter capability. Consume a complete down/up sequence only when that shortcut owns it; ignore key-repeat spam and leave keys alone outside the active reader. Keep a button alternative if another accessibility service or reader uses those keys. [Key handling](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onKeyEvent(android.view.KeyEvent)), [key-filter capability](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_REQUEST_FILTER_KEY_EVENTS).

## OCR choices and evidence

| Choice | Intended role | Current status / limitations |
| --- | --- | --- |
| Hearth PaddleOCR v5 mobile | General books, Chinese/Japanese text, Arabic, Cyrillic and Latin | Already integrated. Current native geometry is horizontal; needs vertical crops, better grouping and high-resolution region handling. |
| Manga OCR | Japanese manga bubble recognition, including vertical text and furigana | First specialist adapter candidate. Needs a detector/crop stage and its own encoder/decoder/tokenizer implementation; cannot use Paddle's CTC decoder. |
| MeikiOCR | Alternative Japanese detector/recognizer | ONNX artifacts exist; vertical support is described as beta. Requires its own output processing and artifact-license review. |
| ML Kit Text Recognition v2 | Optional comparison for clean printed Japanese/Korean/Latin book text | New OCR backend, play-only under Hearth policy. Not an Arabic/Cyrillic replacement. |
| Additional Paddle readers | Korean webtoons first; further scripts as verified | Official Korean ONNX export exists. Pin its dictionary, hash and preprocessing contract before exposing Download & install. |

[Manga OCR](https://github.com/kha-white/manga-ocr) targets Japanese manga and multi-line bubble crops. Its own documentation warns that it can generate text even on images without text. Require a valid detected/selected text region, bound decoding, and preserve the original crop for inspection. Do not describe it as a general full-page detector or a multilingual recognizer.

The [ONNX community export](https://huggingface.co/onnx-community/manga-ocr-base-ONNX/tree/f9023406bb2f6b17df67bc4a327c56ecd20611f0) has separate encoder and decoder models plus configuration files. The supported UINT8 encoder + INT8 decoder pair totals **116,595,741 bytes (about 111 MiB)**, excluding other assets and a detector. Smaller quantizations also exist; file size is not phone latency or RAM evidence. Validate operator support, tokenizer IDs, preprocessing, generation limits and Japanese output on our pinned ONNX Runtime before calling this supported. Code/export metadata lists Apache-2.0.

[MeikiOCR](https://github.com/rtr46/meikiocr) is trained for Japanese game text. Its code is Apache-2.0, while the linked [detector](https://huggingface.co/rtr46/meiki.text.detect.v0) and [recognizer](https://huggingface.co/rtr46/meiki.txt.recognition.v0) cards declare LGPL-3.0. Treat these separately. The publisher documents limits of 64 detected boxes and 48 characters per recognized line; this needs deliberate handling for dense book pages. It is a candidate, not a proven manga winner.

[ML Kit's language matrix](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages) and [Paddle's Korean export](https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec_onnx) provide the initial capability sources for the other options.

[Mokuro](https://github.com/kha-white/mokuro) is useful for selectable manga text and reading-layout ideas. [comic-text-detector](https://github.com/dmMaze/comic-text-detector) is relevant to manga-specific localization/grouping. Both root projects are GPL-3.0; they are references, not automatically Apache-compatible source additions. Do not make desktop preprocessing a requirement for Hearth users.

## Shared model and translation contracts

Separate **text detection**, **recognition**, **reading order**, and **translation**. The UI can offer simple presets while Advanced exposes compatible choices. A specialist recognizer must not appear selectable without its required detector/tokenizer/runtime assets.

Each OCR entry declares exact artifacts and revision, architecture/adapter ID, input preprocessing, tokenizer/dictionary, supported scripts/languages, vertical/horizontal capability, whether it returns lines or bubble text, quantization, byte count/hash, license, and installation/runtime status. Show Installed / Download & install / Experimental / Unavailable accurately. Use Downloads/Hearth/models or the user's chosen folder, automatic verified installation and import through the same installer.

For manga, group detected lines into coherent bubbles, preserve vertical Japanese column order and optional right-to-left panel reading, and avoid duplicating furigana as dialogue. For books, preserve paragraph/column order and handle line-break hyphenation cautiously. Do not feed the entire dense page into a tiny recognizer input.

Pass accepted text groups to the existing translator contracts. Retain group identity, page revision and source text. Cache with language pair, model/provider and context settings; discard results belonging to an old page. Keep a small optional glossary for names and terms. Context must stay within the provider's supported template and must not become text to translate again. Bound automatic work to the current view; do not queue every intermediate scroll position.

Keep OCR imagery local in this design. Cloud selection sends recognized text through the existing BYOK path; FOSS never delegates network downloads or translation. Native sessions follow existing lifetime/cancellation and LocalWorkGate rules.

## Ordered work with visible acceptance criteria

### MB-01 — Manual manga/book screen overlay

- [x] Add capture/session handoff, region selection and fresh-frame capture with Hearth windows hidden.
- [ ] Add image scaling/coordinate transforms, block IDs, original/translation display and Retry.
- [x] Reuse translator/model selection and TTS; keep source and translated playback separate.
- [x] Add touchable recovery handle, notification Stop, remembered geometry and labeled native controls. Full TalkBack usability remains an owner check.

Done when: a user opens an existing reader, translates a region without leaving it, reads/copies/hears the result and can always dismiss or stop the overlay.

### MB-02 — Reading triggers

- [ ] Implement settled-page observation, bounded latest-view work and duplicate-result reuse.
- [ ] Deferred: exact scroll distance/burst counts. The 0.13.0 Accessibility implementation was removed in 0.13.1; adjustable settling remains.
- [x] Add page-change mode and screenshot/text Share entry.
- [ ] Deferred: global Volume Up shortcut; removed with Accessibility access in 0.13.1.
- [ ] Persist per-reader presets; clearly identify unsupported exact-distance modes.

Done when: repeated scroll events produce one translation of the settled view; rapid page turning never paints an old translation on a new page; manual mode and normal reader navigation still work.

### MB-03 — Manga OCR and book geometry

- [ ] Improve existing Paddle vertical crop handling, grouping, reading order and dense-page tiling.
- [ ] Integrate and validate Manga OCR encoder/decoder/tokenizer on Android, then expose its verified package.
- [ ] Add Korean Paddle reader; evaluate Meiki and optional ML Kit OCR behind the same interface.
- [ ] Add model cards, actual language/orientation capabilities, download sizes, sources and licenses.

Done when: Japanese vertical/horizontal bubbles and furigana-heavy crops have inspected outputs; Arabic translation remains readable; unsupported combinations cannot be started as if ready. Candidate engines remain hidden from normal ready-to-use choices until they run successfully.

### MB-04 — Reading polish and release checks

- [ ] Add original peek, adjustable text size/background, stable history and term glossary.
- [ ] Verify two-column books, RTL device locale, long webtoons, zoom, rotation, page-animation delays and interrupted downloads.
- [ ] Verify capture revoke/lock, translator failure/retry, volume-key conflicts and disabled accessibility.
- [ ] Run both flavor gates, focused trigger/geometry/native contract tests, and actual phone checks before APK delivery.

Use public-domain or owner-provided sample pages. Record readable outputs and capture/OCR/translation timings in existing diagnostics; a new benchmark framework is not needed to start this feature.

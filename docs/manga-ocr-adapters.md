# Manga, Meiki and drawn regions — 0.12.0

This first reading implementation adds two native OCR adapters and drawing on a
captured/imported page. It reuses Hearth's local/cloud text translation, model
downloads and shared TTS. It does not yet capture another app's screen, observe
scrolls, intercept volume keys or reconstruct complete manga pages.

## Try it

1. Camera → Settings → OCR. Choose **Paddle**, **Manga**, or **Meiki** using the
   family chips, then choose the desired profile and Download & install.
2. Manga installs two ONNX files; Meiki installs a detector and both readers.
   Installation is complete only when every required file is present. Originals
   remain in Downloads/Hearth/models or the selected download folder. FOSS users
   import these same pinned files individually; filenames do not determine identity.
3. Choose Japanese and the desired translation target. Translation engines and
   connections are the same ones used by the existing camera flow.
4. Import a page or capture a photo. Manga opens the selection screen first:
   circle or drag around one bubble, inspect the highlighted bounding rectangle,
   and tap **Read area**. Meiki can detect multiple lines in an imported image.
5. **Draw text area** is also available on frozen images with Paddle and Meiki.
   Reset clears the selection; Whole image and Center region offer alternatives
   to drawing. The selection respects image letterboxing and clips to image bounds.
6. Read/copy original or translated text, use Android/custom TTS, or Retry after an
   error. A repeated manual capture can retry unchanged text. Live camera OCR
   remains a Paddle feature; specialists currently process still images.

Drawing selects the bounding rectangle of the stroke; it is not semantic object
segmentation or a copy of Google/Samsung services. Small accidental selections are
rejected. More precise accessible region adjustment remains part of overlay work.

## Exact packages

| Adapter | Files | Revision | Total model bytes |
| --- | --- | --- | --- |
| Manga OCR | `encoder_model_uint8.onnx`, `decoder_model_int8.onnx` | `f9023406bb2f6b17df67bc4a327c56ecd20611f0` | 116,595,741 |
| Meiki detector | `meiki.text.detect.v0.1.960x544.onnx` | `a9cffa4f60cbf72ddb87edf19c6f98a01cd042e6` | 14,503,825 |
| Meiki readers | `meiki.text.rec.v0.960x32.onnx`, `meiki.text.rec.v0.vertical.32x480.onnx` | `a28cf5874dc2438ebb1c86336be26bcec51e3375` | 31,466,215 |

Sources: [Manga ONNX export](https://huggingface.co/onnx-community/manga-ocr-base-ONNX),
[Meiki detector](https://huggingface.co/rtr46/meiki.text.detect.v0),
[Meiki readers](https://huggingface.co/rtr46/meiki.txt.recognition.v0).
Every file's full revision, SHA-256 and byte count are in `OcrCatalog`; imports verify
identity before atomic publication, and initialization rechecks installed hashes.

Manga's 6,144-token vocabulary comes from
[kha-white/manga-ocr-base](https://huggingface.co/kha-white/manga-ocr-base/tree/aa6573bd10b0d446cbf622e29c3e084914df9741),
revision `aa6573bd10b0d446cbf622e29c3e084914df9741`, original SHA-256
`344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d`.
The JSON adaptation and its hash are recorded in `assets/ocr/manga-provenance.json`.
Release verification checks its hash and token count. The export does not include
the vocabulary, so downloading just its ONNX files into an arbitrary app is insufficient.
Run `python3 scripts/ocr/verify-manga-vocabulary.py` to compare the packaged token
order and hashes with the pinned publisher file (network required).

The signed-INT8 encoder was rejected during host validation because ORT could not
initialize its ConvInteger node. The unsigned encoder above loaded and inferred
successfully; it is deliberately paired with the signed-INT8 decoder. Do not switch
the encoder back merely because an alternative file is smaller or similarly named.

## Native contracts and extension

`OcrEngine` exposes upright ARGB recognition and cancellation. `PaddleOcr` retains
the existing implementation. `JapaneseOcr` adds JNI entry points while preserving
the `com.sal7one.common_jni` namespace. Native handles use LeaseRegistry: cancel
signals a borrowed instance; destruction waits until active inference releases it.

Manga uses grayscale bilinear 224×224 preprocessing, normalized to [-1,1], a
197×768 encoder result, and bounded greedy decoding over 6,144 logits. Decoder
start is token 2 and EOS is 3. The model emits an additional CLS in the reference
fixture; it remains in decoder context and is omitted from displayed output.
Decoding is limited to the checkpoint's 300-token length. A missing EOS, invalid
shape, unknown token, non-finite values or ORT error surfaces as an error. The
current adapter does not apply the upstream optional full-width punctuation
postprocessing. Manga output has no calibrated confidence score.

Manga recognizes a selected bubble; it does not detect bubbles. Its publisher warns
that blank/non-text images can produce invented text. Select actual text and keep
the original image available. Full-page bubble detection and layout grouping are
follow-up work. [Upstream model and processing](https://github.com/kha-white/manga-ocr).

Meiki uses BGR [0,1] tensors. The detector gets 960×544 letterboxed input and original
target dimensions. Its readers consume 960×32 horizontal or 32×480 vertical crops,
returning int32 Unicode character codes, boxes and scores. Long vertical crops are
split with overlap; character interval suppression removes duplicated predictions.
Entirely vertical line sets are ordered by descending X; mixed layouts retain a
simple top-to-bottom order, not complete panel reading-order analysis. The native
adapter does not apply upstream language-specific word substitutions.
[Reference contracts](https://github.com/rtr46/meikiocr/blob/main/meikiocr/ocr.py).

Each adapter owns persistent sessions during use, two intra-op threads, non-spinning
idle workers, explicit tensor bounds and cancellation. There is a cooperative
45-second per-image budget checked between ORT calls. This does not guarantee that
an individual kernel returns within 45 seconds. LocalWorkGate still prevents
competing feature workloads; no concurrent OCR/ASR allocation policy was added.

Do not feed Meiki output through Paddle's CTC decoder, or Manga through either
Meiki/CTC decoding. New architectures must implement `OcrEngine`, define exact
artifacts/capabilities and validate their native tensor/token contract before adding
a selectable catalog entry.

## Licensing

Manga code/vocabulary/export metadata: Apache-2.0. Meiki reference code:
Apache-2.0; Meiki model cards: LGPL-3.0. Model weights are fetched unchanged from
their publishers and are not bundled in APKs. Attribution and source links are
included in `assets/licenses/ocr/NOTICE.txt` and `THIRD_PARTY_NOTICES.md`.

## Validation and limits

Host inference used the actual C++ adapter and downloaded pinned weights with
ONNX Runtime 1.20.1 on macOS ARM64. Android packages retain their pinned ORT 1.20.0;
NDK build success is not handset inference validation.

- Synthetic horizontal fixture: both adapters returned `今日はいい天気ですね。`.
- Synthetic vertical fixture: Manga returned `今日はいい天気ですね。`; Meiki returned
  `今日はいい天気ですね`, omitting the final punctuation.
- Meiki blank fixture returned no text. Manga blank recognition is not promised.
- Cancellation after inference was rejected on the next call in the native smoke harness.
- Native sanitizer checks cover OCR regions/CTC and character suppression/invalid geometry.
- Kotlin tests cover crop transforms, clipping, invalid selection, model-package
  completeness, vocabulary/code-point validation and explicit unchanged-text retry.

These are small integration fixtures, not representative manga-quality or speed
benchmarks. Specialist cards remain marked Experimental. Owner device checks should
cover real vertical manga bubbles, furigana, multi-line/crowded text, imports and
downloads, cancellation during inference, drawing with large fonts, and local/cloud
translation. Cross-app overlay acceptance is tracked separately in the
[manga/books plan](manga-books-overlay-plan.md).

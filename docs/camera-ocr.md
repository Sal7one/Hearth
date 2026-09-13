# Camera and photo translation

Hearth 0.9.0 adds **Camera translate** at the top of Home. OCR runs on the phone
using PaddleOCR v5 mobile ONNX exports. Text translation reuses the installed local
translator or an explicitly selected Google Cloud, Azure, DeepL or LibreTranslate
connection. Images are never sent to those providers.

## Use

1. Open Camera translate → Get OCR models. Choose a language group and tap
   **Download & install**. The shared detector and matching reader install together;
   progress, cancellation and retries are in Downloads. Originals remain in
   `Downloads/Hearth/models` or your selected folder. No computer or package manifest
   is required. In foss, import the matching detector and reader ONNX files instead.
2. Set the text language and translation target. The text-language picker lists
   the selected OCR group's declared languages; the target picker checks the
   translator's direction support. Selecting a text language informs translation,
   not a language-forcing input to the OCR network.
3. Tap Open camera to grant camera permission, then Start live or Capture & translate.
   Alternatively, Import photo uses Android's file picker without camera permission.
4. Hold the phone level. Live text must settle across two observations before it is
   translated. Capture uses a larger image and translates immediately. Originals and
   translations remain selectable and have separate Copy buttons. Retake returns to
   the preview; Stop releases the models. Leaving the app stops inference.

Camera's translator selection is separate from Conversation. Connections and local
model installation are shared. Local is the default; cloud selection clearly states
that recognized text will leave the device. OCR and local translation remain usable
without a connection after installation. ML Kit language packs are play-only and
must already be downloaded for offline operation.

## Model sources

All exports below are published by PaddlePaddle, with Apache-2.0 model-card terms.
The app pins each revision, byte count and SHA-256 in `OcrCatalog` and verifies both
files again before native initialization. The detector is shared across groups.

| Component | Source | Download size | Exposed text languages |
| --- | --- | --- | --- |
| Detector | [PP-OCRv5 mobile det](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_det_onnx) | 4.6 MiB | Shared text detection |
| Latin reader | [Latin PP-OCRv5 mobile rec](https://huggingface.co/PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx) | 7.7 MiB | English, French, German, Spanish, Italian, Portuguese, Dutch, Swedish |
| CJK reader | [PP-OCRv5 mobile rec](https://huggingface.co/PaddlePaddle/PP-OCRv5_mobile_rec_onnx) | 15.8 MiB | Chinese, English, Japanese |
| Arabic reader | [Arabic PP-OCRv5 mobile rec](https://huggingface.co/PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx) | 7.6 MiB | Arabic |
| East Slavic reader | [Eslav PP-OCRv5 mobile rec](https://huggingface.co/PaddlePaddle/eslav_PP-OCRv5_mobile_rec_onnx) | 7.5 MiB | Russian, Ukrainian, Belarusian |

Download **inference.onnx** from the revision recorded in the catalog. Imports
identify the exact bytes, so duplicate/default file names are harmless. Both the
detector and a reader are needed. Arbitrary ONNX networks and ZIP archives are not
accepted as OCR models. Dictionaries, including CTC blank and trailing space, come
from each pinned publisher `inference.yml`. Their hashes and revisions are packaged
in `common-jni/src/main/assets/ocr/provenance.json`; no model weights are bundled. `scripts/ocr/verify-dictionaries.py` reproduces these
assets from the pinned publisher configs (Python with PyYAML; network required).

## Implementation and extension

- CameraX 1.4.0 adapts the original Hearth camera's latest-frame analysis and
  unconditional ImageProxy closure, with explicit lifecycle unbinding and upright
  images. It does not import the media editor, FFmpeg, OpenCV or sign-recognition UI.
- `common-jni/ocr/PaddleOcr` takes upright ARGB pixels. The C++ implementation uses
  the existing ONNX Runtime 1.20.0 C API, two CPU threads, BGR preprocessing and
  bounded detection/recognition tensors. Session resources use RAII and the existing
  lease registry; Stop terminates inference before exclusive destruction.
- `ocr_geometry.h` implements bounded DB-map components, horizontal boxes and CTC
  decoding. Camera live detection uses a 640-pixel maximum side; captured/photo
  detection uses 960. Readers receive height 48, zero padding to at least width 320,
  and a maximum width of 1600. Arabic visual output is restored to logical order
  using Paddle's Arabic decoder convention, preserving Latin and number runs.
- `CameraOcrController` owns one replaceable frame and one coalesced translation
  request. OCR and translation run separately, with a bounded translation cache.
  Source revisions and capture epochs reject obsolete OCR/translation results.
  Translation errors preserve original text. LocalWorkGate prevents simultaneous
  caption/conversation/benchmark/camera native workloads.
- To add a model, declare an exact asset and language subset in `OcrCatalog`, extract
  its matching dictionary, verify its input/output contract, and extend the native
  adapter if needed. Updating an ONNX filename alone cannot add a new architecture.
  Test model import rejection, real multi-line crops and the target Android runtime.

The original media-suite `onnx_engine.cpp` explicitly refuses initialization and
contains an inference TODO. Its separate landmark classifier has working inference
but does not release its ORT objects. Neither is an OCR implementation. This feature
uses a dedicated image adapter and leaves the original repository untouched.

## Limits

This first version reads reasonably level, horizontal printed text. It does not
perform perspective rectification, vertical text layout, document reconstruction,
handwriting recognition or translated-text painting over the scene. Translation is
shown beneath the preview, with detected-line boxes on the image. Rotating the phone
is handled; heavily tilted signs still need a better crop/rectification path.

Synthetic fixture checks are integration evidence, not an OCR quality benchmark.
The Arabic fixture's words were recognized but a trailing number was omitted by the
model. Inspect original text for names, prices and numbers before trusting a
translation. See [v21 validation](validation-v21.md) for actual checks and limits.

References: [CameraX image analysis](https://developer.android.com/media/camera/camerax/analyze),
[PaddleOCR decoding](https://github.com/PaddlePaddle/PaddleOCR/blob/main/ppocr/postprocess/rec_postprocess.py).

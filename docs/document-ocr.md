# Documents and local OCR

Documents is an owner-approved expansion of the standalone Hearth app. Its SAF
picker accepts PDF, DOCX and images; imported bytes never enter a provider client.
There is no account requirement or automatic upload. Extracted results remain in
the current feature session until the user explicitly exports a UTF-8 TXT file.
Opening a page in typed translation is a separate user action using that feature's
visible provider choice. Listening uses the existing voice pipeline and its limits.

## Implemented path

`DocumentOcrViewModel` owns one IO job and, only when a scanned page needs it, one
`OcrEngine` with a `LocalWorkGate` lease. It reuses Hearth's verified installed
Paddle/Meiki models, or the experimental GLM reader through Hearth's existing
pinned llama runtime. It does not create a second llama/GGML runtime. Every PDF page
and DOCX embedded image is processed sequentially; bitmap pixels are released
before the next page. Cancel terminates native OCR, unwinds the job and closes the
engine before releasing the lease. A failing/cancelled import retains completed
pages and visibly reports its actual error; it is not reported as complete.

On Android 15/API 35 or newer, PDFs with a text layer use Android's native
`PdfRenderer.Page.getTextContents`; blank pages fall back to OCR. On Android 9–14,
all PDF pages use the installed OCR model. PDFs with mixed text and scanned images
are a known limitation: a nonblank page text layer currently wins over image OCR.
Password-protected PDFs report the framework's failure. Native PDF parsing/render
calls themselves are not interruptible; cancellation is checked between them.
Complex tables/layout are not reconstructed as spreadsheets or structured Markdown.

`DocxTextExtractor` is a reusable common-jni Kotlin utility with no Android context,
storage/network or OCR dependency. It preserves runs, paragraph boundaries, tabs,
line breaks and accepted inserted text, excluding deleted tracked text. Images are
delivered synchronously one at a time to a caller-owned OCR consumer. Headers,
footers, comments, floating-object reading order and legacy `.doc` are not part of
this first contract. Save legacy documents as DOCX/PDF.

Limits: 64 MiB input; 200 PDF pages; 1600-pixel rendered long side; source images
no larger than 100 megapixels; 262144 extracted characters. DOCX additionally limits
1024 archive entries, 128 MiB total expanded bytes, 8 MiB XML, 16 MiB per image and
32 embedded images. Duplicate, absolute, traversing and malformed archive paths,
DTDs/entities, malformed XML and non-UTF-8 OOXML are rejected. No ZIP paths are
written to disk. Private temporary imports are removed in `finally`.

## Experimental GLM-OCR reader

GLM-OCR is a functioning, explicitly experimental Documents reader. It is not a
Camera or screen-reading preset: page-sized autoregressive OCR has different
latency, memory and output semantics from the existing detector/crop/CTC path.
The document reader and read-aloud text language are stored separately from the
Camera reader/source language. The voice language picker accepts general ISO
languages because a PDF/DOCX text layer may contain Arabic even when the selected
image reader does not support it; choosing a voice language does not expand that
reader's OCR coverage.

The publisher declares English, Chinese, French, Spanish, Russian, German,
Japanese and Korean. **Arabic is not declared supported.** Model weights are MIT;
the full publisher SDK is Apache-2.0. Hearth does not import that Python SDK.
It uses `mtmd` from its existing pinned llama.cpp revision
`64e9bceb2c3a856efed96feda784a50947049feb`; GLM4 decoder and GLM-OCR projector
support already exist at that revision. The two files below are pinned in the
app catalog and checked independently before model creation. Play builds offer
the shared idempotent model downloader; FOSS imports those same two exact GGUF
files locally without a model-host network client.

### Reusable native boundary

`vision/VisionModel` accepts caller-owned RGB bytes, with no Activity, camera,
storage, network, document parser or provider dependency. `VisionOcrSession` is
the common-jni Kotlin adapter: verified file paths and ARGB pixels enter
a coarse JNI call; a generation-safe lease registry owns the native session.
ARGB is copied and composited over white before inference, without holding a
pinned JVM array. Requests serialize per model and return one page-sized text
result with unknown confidence (`-1`), not invented word boxes or layout scores.

Limits are explicit: 1–2048 pixels on either side, exact pixel count, 4096 context
tokens, 1024 image tokens, 1536 generated tokens, 64 KiB output and a 120-second
request deadline. Reaching a limit reports an actual error rather than silently
publishing truncated text. The prompt is the publisher's `Text Recognition:`
contract, not arbitrary chat text. Each request clears its KV cache.

Cancel is terminal for the session: it sets an atomic flag observed by llama's
abort callback, vision matrix-operation boundaries and stage checks. Destroy
retires the handle, cancels, and waits for outstanding inference leases. A
cancelled request publishes no partial success. Synchronous model loading and
file hashing must run off the main thread; model loading cannot be interrupted
until creation returns a handle. Android framework PDF calls are likewise only
checked between calls. A cancelled job still waits for its native consumer to
unwind before freeing model resources.

The shared runtime builds for all four packaged Android ABIs, but this GLM
package is **64-bit only at execution**. The SDK rejects a 32-bit process before
hashing/loading the model; the document chooser/download UI explains this. The
1.34 GiB download alone is not a RAM budget: tensor buffers, context and vision
processing add memory. Physical phone speed/RAM and real multilingual document
accuracy remain unverified; this is not a recommended live-caption companion.

## Other OCR candidates researched on 2026-10-11

The remaining candidates below are research, **not selectable functioning engines**.
Publisher benchmark numbers and server throughput do not establish Android speed,
Arabic accuracy or safe simultaneous operation with live captions.

| Candidate | Primary evidence | Native/mobile fit | Next acceptance gate |
|---|---|---|---|
| Nemotron OCR v2 | [Publisher model card/source bundle](https://huggingface.co/nvidia/nemotron-ocr-v2) | NVIDIA Open Model License. Detector, recognizer and reading-order stages; supplied native code includes CUDA/custom operators. Multilingual variant covers English, Chinese, Japanese, Korean and Russian, **not Arabic**. | CPU/ONNX export proof with each operator/shape; C++ preprocessing/decode parity; Android packaging and measured working set. Not a drop-in Paddle recognizer. |
| Surya 650M | [Publisher repository and model license](https://github.com/datalab-to/surya) | Apache code, conditional model-weight commercial terms; OCR/layout/tables across many languages. Python/Torch implementation and multi-model working set require a conversion study. | Review weight terms independently of code license, portable inference export, Arabic fixtures and phone RAM/timing. |
| EasyOCR | [Publisher repository](https://github.com/JaidedAI/EasyOCR) | Apache-2.0 project, Arabic/Latin/CJK recognition families. Useful non-Paddle baseline; Python detection/recognition pipeline needs conversion and exact alphabets/normalization. | Pinned ONNX recognizer+detection conversion, greedy/beam decode fixtures, no unverified third-party ONNX silently accepted. |

GLM's official publisher only supports particular recognition/schema prompts;
normal chat prompts are inappropriate. Start with `Text Recognition:`. Its full
document SDK also uses PP-DocLayoutV3, a distinct layout model; adding the decoder
does not recreate the SDK's table and reading-order pipeline. This slice intentionally
does not relabel Paddle results as GLM results or invent artifact compatibility.

The [official ggml conversion](https://huggingface.co/ggml-org/GLM-OCR-GGUF)
and [publisher card](https://huggingface.co/zai-org/GLM-OCR) identify the following
files. Both were downloaded, hashed, loaded and used by the production native
adapter; these are the app import/download pins. Model bytes are not peak RAM.

| Official GLM file | Bytes | SHA-256 |
|---|---:|---|
| `GLM-OCR-Q8_0.gguf` | 950433408 | `45bc244a6446aff850521dc41f18bc8d7105ad5f0c2c8c28af04e7cc4f4d50b1` |
| `mmproj-GLM-OCR-Q8_0.gguf` | 484403648 | `9c4b58e33e316ed142eb5dcb41abec3844d3e6e5dc361ffb782c3fa9d175141f` |

Both files are from `ggml-org/GLM-OCR-GGUF` revision
`65a42de1148dbed2297e922b5dbc7d9b70c36578`: **1,434,837,056 bytes together**,
so the projector makes even the official Q8 package larger than a 1 GiB budget.
A separate Q4 conversion/parity gate would be needed for a smaller package.

### Actual native proof

The production CPU `VisionModel` decoded an original 640×240 two-line English
fixture exactly: `HELLO HEARTH` / `Local OCR 2026`. The same probe checks terminal
cancel and cancellation during a second inference. The recorded probe was run
on macOS arm64 with four threads while other native/Gradle builds contended for
resources: load 59.6 seconds, inference 117.0 seconds, cancellation 1.62 seconds.
These are actual timings from that run, **not a phone benchmark or an uncontended
engine comparison**. Largest child RSS includes preceding compiler processes and
is not an exact model working-set measurement.

The source and file hashes, exact decoded text and cancellation result are in
[production GLM proof](evidence/glm-ocr-native-2026-10-11.json). Input bounds and
alpha composition are covered by the common host suite (`vision_input_test`,
12 checks). File pin tests reject wrong sizes and same-sized corruption. Model
weights, generated images and build outputs are excluded from Git.

The [four-ABI runtime record](evidence/glm-ocr-runtime-2026-10-11.json) verifies
all staged binaries against one final source-set hash, exact ELF ABI identities,
16 KiB load alignment, the eleven intended translation/vision JNI exports and
platform-only dependencies. mtmd's vendored stb, miniaudio and hash-library
notices are preserved with llama's license in the packaged translation assets.
This is build/packaging evidence; it does not prove Android model execution.

Reproduce the actual engine gate with Pillow, a C++ compiler/CMake and the existing
pinned llama checkout prepared by `scripts/translation/build-runtime.sh`:

```sh
python scripts/vision/prove-glm-ocr.py --directory /tmp/hearth-glm-ocr --download \
  --font /path/to/Arial-or-DejaVuSans.ttf --output /tmp/glm-ocr-native.json
```

This slice does not include PP-DocLayoutV3, table reconstruction, formula parsing
or publisher reading-order accuracy. Those need separate model and evidence gates.

## Verification and remaining evidence

`DocxTextExtractorTest`: multilingual order, XML escaping, tracked deletion, image
callback, path/foreign ZIP rejection, DTD rejection, text/XML limits, cancellation,
caller stream ownership, malformed XML/archive. `DocumentLimitsTest`: actual file
header routing, legacy `.doc` rejection, scaling and overflow bounds.

The required Android QA compile/test pass is run by the coordinating agent after
all parallel feature work settles. Physical document accuracy, long-session RAM,
PDF framework behavior on actual API 28/35/36 devices and SAF provider interactions
remain owner device checks; no device or paid provider requests were made here.

## Added working recognition choices

Seven new pinned recognizers reuse the existing C++ detector/crop/CTC backend,
installed-model hashing, idempotent download/import and all four packaged ABIs.
They are available in Camera, screen reading and Documents through the shared OCR
catalog. Their dictionaries were generated from the exact publisher `inference.yml`
and are reproduced by `scripts/ocr/verify-dictionaries.py`; provenance is bundled
with the library. Language declarations follow the
[publisher language/model table](https://www.paddleocr.ai/latest/en/version3.x/algorithm/PP-OCRv5/PP-OCRv5_multi_languages.html).
This adds actual supported reader choices; it is not a new vision-language engine.

| Reader | Recognition bytes, excluding shared detector | CTC classes |
|---|---:|---:|
| Optimized English | 7848423 | 438 |
| Korean + English | 13418787 | 11947 |
| Greek + English | 7808735 | 356 |
| Thai + English | 7891015 | 526 |
| Devanagari: Hindi/Marathi/Nepali + English | 7912311 | 570 |
| Tamil + English | 7885691 | 515 |
| Telugu + English | 7898759 | 542 |

Host evidence:

- All eleven packaged publisher dictionaries reproduced byte-for-byte.
- All seven real ONNX files matched their exact byte/SHA-256 pins and executed
  through CPU ONNX Runtime 1.20.1 at widths 320/640/960, native BGR/NCHW float32
  input height 48, with matching CTC class counts and finite probability output.
  Korean's large alphabet showed up to 1.18e-4 floating-point probability-sum
  error; the bounded validation tolerance is documented in the script.
- A production `paddle_ocr.cpp` ASan/UBSan integration probe runs the real pinned
  detector and recognizer, including crop geometry and CTC decoding. It checks a
  generated `HELLO HEARTH` fixture. This checks actual engine compatibility,
  **not multilingual document accuracy or phone performance**.

Reproduce with Python 3.12, numpy, PyYAML, Pillow and CPU ONNX Runtime:

```sh
python scripts/ocr/verify-dictionaries.py
python scripts/ocr/verify-recognizers.py --directory /tmp/hearth-ocr-cache --download
# Supply the pinned PP-OCRv5 detector as /tmp/hearth-ocr-cache/detector.onnx.
# Its size and digest are checked independently by the native probe.
python scripts/ocr/run-native-recognizer-smoke.py --directory /tmp/hearth-ocr-cache \
  --onnx-library /path/to/libonnxruntime.dylib
```

Actual graph reports: [ONNX contract](evidence/ocr-recognizers-2026-10-11.json)
and [production native fixture](evidence/ocr-native-recognizers-2026-10-11.json).
The native probe runs on macOS/Linux with a matching CPU shared library and C++
compiler. No model weights or generated fixture images are committed.

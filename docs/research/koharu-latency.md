# Koharu source notes and Hearth's 200 ms manga OCR target

## Production reader

Hearth 0.30.3 adds the **Fast lines** reader. The three files from the pinned FP32 release total 23,227,987 bytes. Android and desktop use the same C++ model path, crop orientation, contour geometry and Kotlin reading order. Desktop uses up to six crop workers and Android starts with up to four. Both keep a single resident recognizer. The app publishes geometry before recognition and restores reading order before publishing text.

On the quiet M4 Pro, the real desktop page controller published all 33 lines in 159 ms p95 for OCR and 165 ms p95 from the controller's Read request to completion, over 20 warm runs. The native JNI output matched all 33 reference transcripts. One of nine synthetic lines lost a full stop because the portable detector resize moved a crop edge by one pixel. After the same runtime startup checks the desktop app runs, the first cold OCR request took 721 ms. The OCR-only timing excludes screen capture and translation. The first full 33-line QuickMT Japanese→English pass took 2.62 s, including translator loading. An unchanged page reused translations and took 165 ms. Android device latency remains unmeasured.

In a separate M4 Pro native test on those 33 lines, one stock FP32 CTranslate2 batch matched all 33 sequential translations and reduced median translation time from 1,706 ms to 453 ms. INT8 took 276 ms and changed one translation. The current app uses its existing sequential translation path.

For a real host test, point `HEARTH_MANGA_LINE_MODELS` at the pinned model tree and `HEARTH_MANGA_LINE_PAGE` at a local image. Set `HEARTH_MANGA_QUICKMT_MODELS` to the verified QuickMT directory to also time the translation pipeline. Run `./gradlew -PhearthDesktopOnly=true :desktopApp:desktopTest --tests '*DesktopMangaLineRealTest' --info` after staging the desktop native OCR library. The test prints timing and line counts.

## Source checkout

The upstream source is checked out clean and detached in `build/research/koharu-8047c8b/` at `8047c8b37a28f112fbf09dcf19436350b40a93ad` (November 9, 2026 UTC). Hearth ignores `build/`. The older checkout in `build/research/koharu/` has a local commit and was preserved. This checkout contains source only. [Pinned Koharu source](https://github.com/koharu-rs/koharu/tree/8047c8b37a28f112fbf09dcf19436350b40a93ad)

```sh
git init build/research/koharu-8047c8b
git -C build/research/koharu-8047c8b remote add origin https://github.com/koharu-rs/koharu.git
git -C build/research/koharu-8047c8b fetch --depth=1 origin 8047c8b37a28f112fbf09dcf19436350b40a93ad
git -C build/research/koharu-8047c8b checkout --detach FETCH_HEAD
git -C build/research/koharu-8047c8b status --short --branch
```

Koharu's workspace offers MIT or Apache-2.0 for its code. Hearth's existing adaptations use the MIT option and retain the source notice. Check each model and training dataset separately. The RF-DETR weights declare custom terms and cite Manga109's academic-use terms. [Upstream workspace license](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/Cargo.toml#L8-L10) · [RF-DETR model card](https://huggingface.co/mayocream/koharu-layout-rfdetr-seg-2xl-1152)

## What is worth carrying over

| Source | Behavior | Hearth decision |
| --- | --- | --- |
| [Scheduler](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-pipeline/src/scheduler.rs#L58-L121) and [accelerator gate](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-pipeline/src/accelerator.rs#L14-L49) | Bounded pages in flight, one job per stage, dependencies per page. Accelerator inference has one lane because the authors observed interference when models ran together. | Reuse the ownership and admission ideas. For a live capture, keep one active page and at most one newest pending page. On a single page, run the detector before the recognizer. Benchmark before overlapping models or adding workers. |
| [Image cache](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-pipeline/src/images.rs#L9-L51), [detection skip](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-pipeline/src/stages/detection.rs#L102-L113) | Shared decoded source by content identity, existing text regions skip detection. | Share the original pixel buffer within a capture. Reuse detection across byte-identical still pages keyed by page, crop, rotation and model revision. For moving captures, only reuse regions with unchanged pixel evidence and matching geometry. Keep forced refresh available. |
| [Detector postprocess](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-ml/src/koharu_layout_rfdetr_seg_2xl/processor.rs#L83-L165), [scene order](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-pipeline/src/stages/detection.rs#L1626-L1755) | Threshold before mask resizing, same-label IoU suppression, panel→balloon→text hierarchy with deterministic spatial order. | Hearth already ports this for RF-DETR. Reuse the ordering and region identities when a small detector supplies groups. Keep horizontal, vertical and cross-panel fixtures to catch ordering regressions. |
| [OCR stage](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-pipeline/src/stages/ocr.rs#L92-L168) and [inference loop](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-pipeline/src/stages/ocr.rs#L226-L248) | Crops before inference, retains user-authored text, runs crops serially under one model lock. | Carry over the ownership and text preservation. Run exact-width crops in a bounded worker pool against one resident compact model. Keep the region IDs independent of completion order. |
| [Inpainting crop planner](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-pipeline/src/stages/inpainting.rs#L528-L584) | Fills flat colors first and limits the model work to remaining mask tiles. | Already adapted in Hearth. Prepare paint after text becomes available. Keep neural reconstruction on the still-page quality path. |

Koharu defaults to RF-DETR Seg 2XL, PaddleOCR-VL 1.6 and LaMa. Its source uses separate Torch, llama.cpp and diffusion packages for supported desktop backends. Using the full runtime would add a new deployment stack and its default OCR GGUF and projector alone total about 1.82 GB. Its Criterion Manga OCR benchmark times one already-loaded crop. It gives no full-page 200 ms result. [Defaults](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-pipeline/src/config.rs#L30-L43) · [Hardware](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/packages/docs/hardware.mdx#L7-L60) · [Single-crop benchmark](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-ml/benches/manga_ocr.rs#L7-L37)

## Known timing on M4 Pro

| Run | First outline | First transcript | All OCR | Full cleanup |
| --- | ---: | ---: | ---: | ---: |
| Hearth's earlier full Kotlin/JNI Koharu run on a 770×1080 private page | 95 ms | 5,068 ms | 5,923 ms | 17,876 ms |
| Current Hearth compact reader, resident Japanese model through JNI, 16 crops | 84–89 ms detector | 165 ms | 1,652 ms | Separate stage |
| PP-OCRv6 Manga FP32 ONNX host smoke on a private page, 33 line detections | Not measured | Not measured | 467 ms | Separate stage |
| Same PP-OCRv6 smoke with FP16 | Not measured | Not measured | 620 ms | Separate stage |

These are host measurements. The Koharu run uses RF-DETR and Manga OCR with ten recognized regions. Running four resident copies of Hearth's compact Japanese recognizer lowered the warm 16-crop time to 753 ms, with the same tokens, but increased peak RSS from about 0.53 to 1.33 GB. The PP-OCRv6 host smoke produces line text. Bubble grouping, page reading order and app publication remain separate integration work. Android has yet to run the prototype. [Hearth migration evidence](../koharu-integration.md) · [Compact OCR evidence](compact-ocr-manga.md)

The standalone benchmark used the same 33-line page and pinned PP-OCRv6 Manga FP32 weights. Every box and transcript matched the serial reference in all 20 warm runs per profile on the quiet Mac.

| Recognizer | Median | p95 | Maximum |
| --- | ---: | ---: | ---: |
| Serial, stock graph | 450.92 ms | 452.49 ms | 453.13 ms |
| Six workers, widest crops first, stock graph | 160.81 ms | 163.76 ms | 164.09 ms |
| Six workers, pre-Softmax ArgMax | 159.29 ms | 161.15 ms | 161.20 ms |

The stock graph met the target on this fixture without a model re-export. An earlier six-worker stock run under concurrent load reached 209.33 ms p95 and 214.57 ms maximum. An all-linear INT8 variant reached a 185.54 ms maximum under load but changed three transcripts. Timed work starts with decoded pixels and ends with decoded line text. It excludes capture, cold load, bubble grouping, translation and paint.

Switching the existing Hearth DB postprocessor to the candidate's thresholds restored the box count yet matched only 23 transcripts. Keeping contour scoring and expanding its bounding box by the contour's area/perimeter distance preserved all 33 private-page results. It also preserved all nine synthetic transcripts, with one box edge moving by one pixel. This is a candidate for porting without a polygon-offset dependency once it passes a larger fixture set.

Run the benchmark with the three pinned files from the model revision. The driver compares against stock serial results and prints aggregate timings without including the image paths or transcripts. [Benchmark script](../../scripts/ocr/benchmark-manga-latency.py) · [Pinned model files](https://huggingface.co/Kellenok/PP-OCRv6_manga/tree/d65838996a00ef6b1c73ca3a700d7a8c5fbbef8a)

```sh
python3.12 -m venv build/research/ocr-bench-env
build/research/ocr-bench-env/bin/pip install numpy==2.5.4 onnx==1.23.2 \
  onnxruntime==1.20.1 opencv-python==5.0.0.93 pyclipper==1.4.0
build/research/ocr-bench-env/bin/python scripts/ocr/benchmark-manga-latency.py \
  --model-dir /path/to/v0.3 --image /path/to/page.png --passes 20 --workers 6 --argmax
```

## Fast path to test

1. Keep a warm compact ONNX detector and a one-pass CTC recognizer. PP-OCRv6 Manga v0.3 FP32 is the first candidate because both graphs already run on the pinned ORT. Profile detector resize, inference and DB contour geometry separately. Try lower detector input or a tight region of interest and compare recall for small kana and vertical text before changing defaults. [Candidate model card](https://huggingface.co/Kellenok/PP-OCRv6_manga/tree/d65838996a00ef6b1c73ca3a700d7a8c5fbbef8a)
2. Use the tested contour score and polygon expansion, rotate vertical crops, and recognize exact-width crops in parallel on one session. Padded width buckets changed several lines and did not save time. Consume each ONNX output before releasing its native handle so we avoid a copy of the full score tensor. An export with top-class IDs can reduce the output further. Keep the confidence contract explicit and verify CTC blank and duplicate collapse before replacing the stock recognizer. [Koharu reference CTC preprocessing and collapse](https://github.com/koharu-rs/koharu/blob/8047c8b37a28f112fbf09dcf19436350b40a93ad/crates/koharu-ml/src/pp_ocr_v6/rec/processor.rs#L76-L175)
3. Publish stable ordered OCR output before translation and paint. For repeated frames, memoize recognized text only after exact crop-pixel and model matches. Record the source generation on every result. If an incomplete page reaches the deadline, preserve valid partials and mark the page incomplete. A deadline or stale result never counts as a completed 200 ms OCR pass.
4. Run the same warm page suite on M4 Pro and representative Android hardware. Use stable 770×1080 and larger fixtures, including empty, sparse, dense, vertical, furigana, two panels and adjacent balloons. Measure p50, p95 and max of 30 warm runs per fixture and report cold load separately. Check accepted-box recall, character error rate, per-balloon exact match, and exact reading order. Record translated first/full text and cleanup completion as separate user-facing timestamps.

Initial allocation for the complete warm OCR pass starts once source pixels are available. These are budgets to test, not measured targets reached.

| Stage | Budget |
| --- | ---: |
| Capture handoff and preprocessing | 10 ms |
| Detector inference and first outline publication | 60 ms |
| Geometry, rotation and grouping | 15 ms |
| Parallel recognition and CTC decode | 95 ms |
| Final ordered text publication | 20 ms |
| **Total** | **200 ms** |

Use a platform accelerator only when the exact packaged ORT exposes it and the graph placement and accuracy have been checked. Time first outline, first transcript and all accepted OCR lines separately. A successful detector benchmark or a cropped one-line test does not establish a 200 ms page pipeline.

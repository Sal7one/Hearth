# Koharu integration boundary and implementation status

Owner authorization: Android and desktop reading improvements, Android first;
using Koharu's full project is allowed where it improves Hearth. The compact
automatic-page implementation is a first working backend, not the full port.

## Evidence

Research checkout: `koharu-rs/koharu` revision
`73abcafab1507e1c228c36ed54ba84e5e1cd6ff8` (workspace 0.83.5), under ignored
`build/research/koharu`. Source is MIT OR Apache-2.0. Its source files and dependency
manifests, rather than UI screenshots, were inspected. No upstream AGENTS file
was treated as Hearth instructions.

- `crates/koharu-pipeline/src/stages/detection.rs`: current default RF-DETR instance
  segmentation, distinct text/bubble/panel/SFX output and scene mapping.
- `crates/koharu-pipeline/src/bin/run.rs`: real headless example runs the full
  detection/OCR/translation/inpainting workflow and renders an output image.
  It currently selects its own local translator; it is not a drop-in Hearth CLI.
- `crates/koharu-pipeline/README.md`: page-local stage commits, bounded scheduling,
  lazy model residency, immutable per-run configuration and cooperative stop.
- `crates/koharu-ml/src/lib.rs`: process-wide Torch, llama and diffusion runtimes.
  This is not an Android-ready ONNX library. Its runtime packages enumerate
  desktop hosts, with no supplied Android package/NDK integration.
- `crates/koharu-renderer`: scene-based layout, font shaping/fallback and rendering;
  `koharu-rasterizer` uses a separate native rendering backend.
- A locked upstream `cargo check -p koharu-pipeline --bin run` was attempted.
  Installed Rust 1.90 is below `sysinfo 0.39.6`'s Rust 1.95 minimum; no full-runtime
  inference or full-project build is claimed. Metadata resolution succeeded.

The current RF-DETR weight pin is
`mayocream/koharu-layout-rfdetr-seg-2xl-1152` at
`aed55fdb8ca953c6bec33cf6ed6dd52a9b72bfa2`. Its model card labels the weights
`other` and describes Manga109 academic-use training-data terms. The permissive
code license must not be presented as blanket Apache-2.0 weight licensing.
The model predicts masks and classes, not OCR or reading order.

## Delivered first backend

Shared KMP detection/region/progress contracts, exact artifact pins, small
RT-DETR-v2 detector, resident Manga OCR reader, progressive Android camera/reading
outlines, per-bubble translation, desktop image import/cropping/overlays, verified
model-library downloads/imports, and cancellation/error ownership. CPU only.
The old manual reader remains selectable. The speech/translation/TTS pipeline
still has its original three stages; OCR is an auxiliary image feature.

The compact detector is from a family supported by Koharu; this does not mean
Hearth uses its current default model or full Rust pipeline. Geometry and JNI code
are original Hearth implementations. GPL-derived legacy CTD postprocessing was
not copied. Weights and third-party manga test images are not committed.

## Full backend implementation work

1. **Pin and isolate the engine.** Build the reusable scene, pipeline, ML and
   renderer crates from the exact upstream revision with an explicit Rust >=1.95
   toolchain. Exclude Tauri/CEF frontend, project/archive/PDF/editor/PSD features
   outside Hearth's image OCR scope. Retain source and dependency notices. Keep
   LibTorch/llama/diffusion loading outside Hearth's existing speech JNI process
   on desktop to avoid symbol/runtime collisions.
2. **Add a bounded local worker protocol.** A desktop child process receives an
   isolated page job, selected local model paths, thresholds and cancellation ID;
   emits typed stage/region/mask/error events; persists only job-local scene data.
   Use framed messages with byte, image, region and output limits. No shell
   commands assembled from filenames. Stop/join workers and remove temporary
   artifacts on completion/shutdown. A protocol handshake must reject version
   mismatches. Do not advertise this provider until its real runtime is staged.
3. **Keep Hearth translation authoritative.** Execute detection/OCR and inpainting
   through Koharu, commit their scene outputs, return source-region text to
   Hearth's selected text pipeline, then apply returned translations to the
   scene before invoking Koharu's renderer. Do not use the example CLI's default
   Gemma translator or copy Hearth API keys into its config/model downloader.
   Freeze the chosen Hearth configuration for the job; retain manual correction
   and original-image comparison. Validate Arabic shaping, bidi and font fitting
   with real exported images.
4. **Model management.** Add independently selectable detection, OCR and cleanup
   families with pinned size/hash/revision/license cards. Keep model acquisition
   under Hearth's downloader/importer and explicit user selection. Upstream's
   lazy runtime/model downloading cannot bypass Play/FOSS policy or silently
   fetch multi-gigabyte translation/painting models. Support imported complete
   runtime/model bundles for offline builds. No arbitrary model scripts/pickle
   execution. Public download and private usable-copy status are distinct facts.
5. **Android full vision port.** Do not label the desktop LibTorch crate an NDK
   backend. Export and validate the larger detector/masks into a supported mobile
   runtime, or prove an Android native backend with its complete ABI/library
   matrix. Validate tensor and mask transforms on identical pages first. Adapt
   scene regions/masks into the shared contracts; keep desktop-only runtimes out
   of ARMv7/x86 APKs. Start with S25 Ultra CPU, then only advertise GPU after actual
   inference evidence. TVs still cannot use local models.
6. **Acceptance.** Compare compact versus full segmentation on multiple crowded,
   vertical, furigana and decorative/SFX pages. Measure cold/warm model load,
   first highlight, full OCR and full translated rendering separately. Verify
   cancellation mid-kernel, later-stage failure with earlier outputs retained,
   crop/page changes, missing/corrupt weights, duplicate jobs, provider errors,
   RTL typesetting and original restoration. Run both desktop OS package checks
   and owner Android tests before calling the full backend supported.

Unimplemented: full Rust-worker provider, default RF-DETR mask inference, text
removal/inpainting, Koharu scene renderer/typesetting, and Android export/port.
These must not be described as shipped by the compact backend.

Sources: [Koharu](https://github.com/koharu-rs/koharu),
[pinned pipeline example](https://github.com/koharu-rs/koharu/blob/73abcafab1507e1c228c36ed54ba84e5e1cd6ff8/crates/koharu-pipeline/src/bin/run.rs),
[RF-DETR model card](https://huggingface.co/mayocream/koharu-layout-rfdetr-seg-2xl-1152/blob/aed55fdb8ca953c6bec33cf6ed6dd52a9b72bfa2/README.md),
[compact detector](https://huggingface.co/ogkalu/comic-text-and-bubble-detector).

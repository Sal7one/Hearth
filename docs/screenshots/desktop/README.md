# Desktop UI previews

Rendered on macOS arm64 on 2026-10-24 using Hearth's production Compose screens
and the existing `DesignPreviewRenderTest` fixtures. These are app-rendered demo
previews, not live desktop captures or measurements of model inference.

- `home.png`: Home with an example saved English → Arabic setup.
- `translate.png`: Typed translation with a synthetic museum-opening example.

Only bundled artwork and synthetic text are shown. Model names, readiness,
translation output and status are fixture data. No microphone, model, network,
provider, personal history or external application was accessed by the renderer.
The Mac was locked, so a live window capture was unavailable. No Windows capture
is represented.

Reproduce from the repository root:

```sh
HEARTH_PREVIEW_DIR="$PWD/build/readme-desktop-previews" ./gradlew \
  -PhearthDesktopOnly=true :desktopApp:desktopTest \
  --tests 'com.sal7one.hearth.desktop.DesignPreviewRenderTest.everyDesktopScreenRendersHeadless'
```

The PNGs are copied without resizing or retouching. See `provenance.json` for
dimensions, hashes and rendering source.

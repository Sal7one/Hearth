# Screen translation and OCR download hotfix — 2026-10-24

Release subject: `v0.30.0-rc.8`, Android **0.30.0 / code 104**, macOS **1.0.3**.
Keep the existing QA application identity/signing. Publish each APK and installer
individually; no combined APK or validation ZIP. No GitHub Actions or Android
device automation is authorized or used.

## Reported failures and causes

The owner selected English → Arabic, but Screen translation reported an
unsupported pair across multiple translator choices. The catalog's advertised
English → Arabic directions are valid. The actual problems were configuration
and navigation: an empty/whitespace OCR override blocked the shared fallback;
Screen's model browser changed the caption translator instead of its OCR route;
Camera's translator setup link opened OCR readers instead of translators. The
generic pair warning also hid missing/unknown configuration and unchecked cloud
capabilities.

OCR downloads reported that the packaged executable was not HTTPS capable.
The process probe used `InputStream.available()` as an EOF indicator and could
drop all/trailing output, especially after process exit. It also rejected valid
version output preceded by Android linker warnings, then discarded the actual
diagnostic. The pinned executable already contains HTTPS/OpenSSL support; the
native recipe and pins were not changed.

## Implemented

- Blank OCR overrides follow the saved shared local model. Explicit nonblank
  model/provider choices remain independent; unknown choices stay actionable
  errors rather than silently selecting another engine.
- Camera and Screen have a direct **Translator: … · Change** action opening the
  actual chooser. Its model browser activates the Camera/Screen route and uses
  that route's input/output filters. OCR language, destination and caption setup
  remain unchanged. Navigation out of Models/Downloads clears this scope; the
  ordinary model library continues to configure captions. Unrelated recommended
  bundles are hidden while selecting specifically for OCR.
- Setup, Camera and the screen service use the same readiness explanation:
  absent/unknown model/provider, explicit image language, unchecked cloud
  capability, offline policy, same language or genuine unsupported direction.
- OCR browsing distinguishes original-text input from translation output. It
  displays the actual nine reader packs/60 input-language codes, recommends a
  compatible input reader, and offers **Show all readers** when saved filters
  hide compatible/selected packs. English → Arabic uses an English reader plus
  an Arabic-capable translator. No new model weights or unverified languages
  were added. English, Arabic and Chinese hints are bundled.
- aria2 version and transfer output drain through real EOF, preserve bounded
  UTF-8/final-line diagnostics, accept warning prefixes and require an actual
  aria2 version plus an exact HTTPS feature. The child receives a stable locale;
  cancellation/timeout terminates the owned process before joining pipe readers.
- Android Automatic mode can use the existing standard HTTPS downloader after
  a typed pre-transfer aria2 capability failure, with a persistent visible
  explanation. Explicit aria2 mode retains the actual error. Executable pins,
  certificate validation, payload size/hash verification, cancellation and
  transfer/TLS failures are never converted into fallback success. Sparse aria2
  pieces are discarded only after a replacement HTTP payload is verified.
- The shared process-output fix is also included in macOS 1.0.3. Desktop model
  startup/OBS behavior from RC7 is retained.
- Gradle worker/common-pool concurrency is capped at two for reproducible APK
  packaging. The uncapped packager exhausted its six-GiB heap while loading large
  native libraries for multiple splits; the capped full build passed. This is a
  build-process setting and does not change model inference threads.

## Validation and owner acceptance

Validation receipts are staged in ignored `build/screen-pair-hotfix/evidence`
and published as separate JSON/log assets with exact source provenance.

Focused process tests cover zero `available()` despite readable data, leading
linker warnings, exact capability tokens, diagnostic/UTF-8/output bounds, silent
probe cancellation, transfer cancellation and real errors. A real installed
aria2 performed an 8 MiB HTTPS transfer with four configured connections,
nonzero Range requests, pause/resume and a matching SHA-256. This is host
execution, not Android executable validation.

Routing coverage checks every catalog-advertised local English → Arabic model,
blank inherited and explicit unknown choices, unsupported directions,
image-source selection and cloud/offline configuration errors. Catalog tests
check source-based recommendations and explicit filter resets.

The required combined build/test gate passed (521 tasks). Each Play/FOSS
Debug/QA/Release app unit suite has 911 cases, zero failures/errors, and three
Play / fifteen FOSS intentional skips per variant. The six desktop suites have
321 cases, zero failures/errors and 36 intentional opt-in/fixture skips.

Both QA variants built all four ABIs plus Universal; all ten APKs pass the
package/native/signing/FOSS gate. Actual code 103 → 104 Play/FOSS ARM64
upgrade receipts preserve the existing QA signer. The six-suite DesktopOnly
gate also passes. The actual mounted 1.0.3 DMG passed checksum, deep/strict
ad hoc signature, launcher/JRE/native/audio/crypto/authenticated API checks.
Actual packaged Metal speech and Arabic translation passed again, including
25/25 translation layers offloaded. Windows/NVIDIA remains unverified here.

The owner tests Android. No actual phone capture, runtime execution, benchmark,
installation or thermal result is claimed. After updating, use the direct
translator action to select the intended model for the saved English → Arabic
screen pair, then Retry the failed OCR downloads. Existing downloaded models
and preferences are retained; uninstalling is unnecessary.

# Real time transiber — finish for publication

Updated 2026-09-12 after the owner's scope correction.

Ship visible improvements to the working app. Cloud BYOK already works well;
preserve it. No benchmark framework, new telemetry, broad JNI rewrite, architecture
project or research program. Reuse the existing translation interface, importer,
downloader and settings store. Do the normal build checks and a short functional
check of changed behavior; do not create extra tooling to justify a small change.

## Release list, in order

### 1. Easy downloads and installation — RT-01 / RT-07

Status: implementation started in the workspace; not yet delivered.

- [ ] Finish saving new model downloads under `Downloads/Real time transiber/models` on Android 10+; direct files use the adjacent `files` folder.
- [ ] Let existing downloaded translation models install directly inside the app. No export or file-picker round trip.
- [ ] Show Downloading / Verifying / Installed and one clear next action; keep manual import for externally obtained files.
- [ ] Keep hash checking and show actual failures; never mark a partial file Installed.
- [ ] Put raw URL/filename entry behind a separate Direct file download action.
- [ ] Keep older downloads usable and correct the storage/help text.

Done when: the owner can download, install and select a model without hunting for
files, and an old completed download works without downloading it again.

### 2. Simple bubble controls — RT-04 / RT-05

Status: next visible UI change.

- [ ] Split the panel into **Appearance** and **CC & translation**.
- [ ] Appearance opens with Height, Width, Text size, Background and Position; keep Reset position obvious.
- [ ] Make Height visibly resize the reading area, separate from moving the bubble higher/lower. Keep the current comfortable size available.
- [ ] Remove competing sizing behavior that makes the slider confusing; retain usable drag/close controls and screen-edge clamping.
- [ ] Keep mode, languages, translation on/off and selected model in CC & translation. Move keys/downloads/full model setup out of the bubble.
- [ ] Save the user's size/position and apply appearance changes without restarting captions.

Done when: the owner can make the bubble shorter or move it lower immediately,
without scrolling through engine settings or losing the current transcript.

### 3. Smaller local translator, easy to swap — RT-02 / RT-03

Status: candidate identified; not integrated.

- [ ] Try the official HY-MT1.5 2-bit mobile variant first if it works with the existing runtime. Its publisher advertises about 574 MB versus our current roughly 1.1 GB Q4 download.
- [ ] Keep the current working model available. Add the smaller choice through the existing translator interface, without an engine redesign.
- [ ] Verify the exact file/hash and do a few real Russian/Chinese → Arabic/English translations; check that switching/off still works.
- [ ] Use a brief side-by-side phone check to see whether it feels faster and remains useful. No benchmark harness, performance dashboard or numerical improvement gate.
- [ ] Consider the advertised 440 MB 1.25-bit variant only if its extra STQ kernel support is a small, safe integration. Otherwise leave it for later.
- [ ] Label download size and supported languages plainly. Do not call an untested option faster, or a new compressed variant a newer base model.

Done when: a smaller working model is selectable and can translate locally, with
honest feedback about its observed responsiveness. If integration becomes a large
runtime project, ship the UX improvements first; it is not a publication blocker.

Sources: [official 2-bit model](https://huggingface.co/tencent/Hy-MT1.5-1.8B-2bit-GGUF)
and [1.25-bit model / kernel requirement](https://huggingface.co/tencent/Hy-MT1.5-1.8B-1.25bit-GGUF).
Publisher sizes are approximate; these remain 1.8B-parameter models. No new speed
claim has been validated by this project.

### 4. Unclutter setup and models — RT-06 / RT-07 / RT-13

- [ ] Keep Start visible and show only audio source, CC/Translate, language pair and the chosen Local/Cloud setup in the primary flow.
- [ ] Collapse advanced engine options; remove repeated setup sections and permanent diagnostic clutter.
- [ ] Show one model card per family; move Q4/Q6/Q8 variants into Advanced rather than six competing choices.
- [ ] Show Installed / Download / Use clearly, plus language coverage when requested.
- [ ] Keep the successful cloud presets and key configuration behavior intact. Only simplify placement when necessary.
- [ ] Fix clipped controls, excessive spacing and unreadable Arabic/large-text layouts encountered in these screens; avoid a wholesale design-system rewrite.

Done when: a configured user can start without scrolling through a long technical
form, and can tell which model is active and how to change it.

### 5. Reading comfort — RT-09

- [ ] Keep previous text readable and make Previous lines easy to find.
- [ ] Keep history steady while reading, with a clear Back to live action.
- [ ] Make paused/loading/error states visible without sound; keep TTS optional.
- [ ] Preserve readable Arabic text and accessible Stop/Recover controls in the compact bubble.

Done when: captions remain readable long enough, older lines are accessible, and
the bubble does not grow over the video as text arrives.

### 6. Package the improvements — RT-14

- [ ] Run the repository's existing required gates; build and verify both APK flavors for delivery.
- [ ] Briefly exercise the changed flows and confirm saved keys/models survive upgrade. No exhaustive new test infrastructure.
- [ ] Update only affected screenshots, setup/storage instructions and release notes.
- [ ] Deliver the APK and concise device checks; resolve the GitHub destination when actually publishing.

Done when: the owner can install the update and immediately try the improvements.
Do not hold the release for every possible model, language or device combination.

## Existing work to preserve

Qwen/Nemotron startup fix; optional bounded local translation; foreground-service
controls; retained caption history; encrypted keys; speech-only cloud model
filtering; source/target language metadata; no-network foss flavor. These are
working features, not a fresh list of things to rebuild. Existing download edits
are unfinished; versionCode/versionName alone do not mean an APK was delivered.

## Deferred, not release requirements

- RT-08: broad settings/state refactor. Make only local fixes needed by the visible UI.
- RT-10: benchmark system, telemetry, 30-minute performance program, GPU/NPU backend work.
- RT-11: cloud/BYOK redesign or another provider/model-catalog audit without a reported bug.
- RT-12: new diagnostics/export infrastructure. Retain existing real errors and useful diagnostics.
- RT-13 remainder: full navigation/design-system rewrite and exhaustive localization project.
- RT-15: sign-language recognition research/prototype. Earlier request retained; separate from this speech-app release.

No extra tool or feature gets added just because it might be useful. Each code
change should fix a concrete user-visible issue or be necessary for one of the
six deliverables above. Retain existing integrity, cancellation and offline rules;
short functional checks do not mean hiding failures or skipping required gates.

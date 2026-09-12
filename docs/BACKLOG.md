# Real time transiber — product and engineering backlog

Updated: 2026-09-12. Owner: the primary engineer on this repository.
This is the working task list for the owner's requests, not a claim that the
redesign or a faster model has shipped. Task IDs are stable; mark subtasks complete
only with implementation and evidence. All proposed improvements below are within
the owner's approved planning scope. Work one logical unit at a time.

## Product understanding

The app helps people read speech while watching another app or listening nearby.
Its core journey is **choose audio → CC or translation → Start → read**. It must
work for deaf and hard-of-hearing users without depending on sound cues. A user
should not need to know model formats, JNI, quantization or provider API taxonomy
to get captions. Local and cloud processing remain explicit choices.

Russian and Chinese speech → Arabic or English text are priority translation
paths. Original-language CC must continue independently when translation is off,
unsupported, slow or failing. Do not present copied CC as successful translation.
A smaller download is useful, but the goal is lower measured latency and memory
while ASR and translation run together, with usable translation quality.

The owner's height feedback has two possible physical meanings: too tall and too
high on the screen. Address both with separate **Height** and **Position** controls.
Keep the currently available comfortable reading size as an option. Do not silently
reset existing users to a new default. Small captions must be possible without
shrinking system text or hiding Stop/Recover controls.

## Confirmed baseline and unfinished work

| Area | Observed status | Evidence / remaining gap |
| --- | --- | --- |
| Standalone app | Implemented | Separate repository and Android identity; no editor/FFmpeg scope. |
| Qwen/Nemotron native startup | Fixed in `62da141` | Vosk runtime symbol isolation; retain regression coverage. |
| Local translation bridge | Implemented in `8414ebd` | HY-MT1.5/Hy-MT2 Q4/Q6/Q8; optional bounded worker; language metadata. |
| Translation validation | Partial coverage | Both ASRs → HY-MT1.5 Q4 Arabic on connected S22; Hy-MT2 Q4 host checks. Not all weights, directions or long streams tested. See validation-v4.md. |
| Notification recovery | Implemented, regression check needed | Pause/resume, show/recenter and stop exist; do not recreate as a missing feature. |
| History | Implemented, usability work remains | Previous lines, reading snapshot, copy; retention and discoverability need dedicated checks. |
| Speech-only cloud picker | Implemented, audit needed | `SpeechModelCatalog` classifies speech roles, sorts provider-created metadata and reads price metadata. Do not describe provider creation time as a verified launch date. |
| Download location/direct install | Uncommitted implementation in workspace | Public app-named folder and direct translation install exist as edits; do not label delivered. Version fields say 0.2.1, which is not release evidence. |
| Bubble settings | Needs redesign | One large panel mixes display, engine, languages, keys, TTS and history. |
| Height | Exists but confusing | `maxHeightPercent` default 55, clamp 20–60; normal view additionally caps at 320dp; settings/history can expand to available height. |
| Setup | Needs redesign | Long engine-first page; model setup repeated; diagnostic actions always near the app header. |
| GitHub publication | Not completed here | No Git remote configured at audit. Release destination/account still needs resolution at publish time. |
| Screen signing → text | Earlier request retained as research only | No sign recognition in this app. ASL and Arabic sign languages are distinct. Do not silently introduce camera scope. |

## Intended screens and boundaries

- **Captions:** audio source, CC/Translate, spoken/output languages, Local/Cloud,
  one selected setup summary, readiness, and persistent Start/Stop action.
- **Models:** Installed / Discover, filtered by chosen language pair; simple model
  cards, integrated download/install status, storage controls, advanced variants.
- **Settings:** Appearance, language defaults, cloud providers/keys, privacy,
  accessibility and diagnostics. Downloads become an activity screen reachable
  from Models; direct-file download remains available there.
- **Bubble toolbar:** drag handle, pause/resume, history, settings and close with
  accessible names. Keep the toolbar usable at minimum dimensions.
- **Bubble Appearance quick settings:** Height, Width, Text size, Background,
  Position and Reset position. No model picker, provider key or downloads here.
- **Bubble CC & translation settings:** mode, language pair, translation toggle,
  selected model summaries and a link to full setup. Installation/key management
  opens the app without requiring a large settings surface over the video.
- **Advanced/debug:** exact technical details are available on demand. Actionable
  failures remain visible; moving diagnostics does not mean hiding errors.

## Priority and execution order

P0 = current usability/performance blocker. P1 = release readiness. P2 = follow-on.
Effort: S roughly one focused unit, M several units, L substantial work/research;
these are sizing estimates, not delivery promises. All tasks are assigned to the
primary engineer; audits and writes remain serial unless separately delegated.

| Order / ID | Priority | Task | State | Size | Depends on |
| --- | --- | --- | --- | --- | --- |
| 1 / RT-01 | P0 | Finish download location and direct install | In progress, unshipped | M | Existing edits |
| 2 / RT-02 | P0 | Measure and select compact mobile translation | Ready | M | Existing bridge |
| 3 / RT-03 | P0 | Integrate verified compact model/runtime | Planned | L | RT-02 |
| 4 / RT-04 | P0 | Split bubble Appearance from CC/translation | Ready | M | None |
| 5 / RT-05 | P0 | Predictable height, position and recovery | Ready | M | RT-04 |
| 6 / RT-06 | P0 | Simplify start/setup journey | Planned | L | RT-04 design |
| 7 / RT-07 | P1 | Friendly model library and guided downloads | Planned | L | RT-01, RT-02, RT-06 |
| 8 / RT-08 | P1 | Unified settings and safe migrations | Planned | M | RT-04, RT-06 |
| 9 / RT-09 | P1 | Reading history and accessibility | Planned | M | RT-04, RT-05 |
| 10 / RT-10 | P1 | Live pipeline performance and reliability | Planned | L | RT-02, RT-03 |
| 11 / RT-11 | P1 | Cloud model picker and key UX polish | Audit then implement | M | RT-06, RT-08 |
| 12 / RT-12 | P1 | Actionable errors and useful diagnostics | Planned | M | RT-06, RT-10 |
| 13 / RT-13 | P1 | Visual consistency and Arabic localization | Planned | M | RT-04, RT-06, RT-07 |
| 14 / RT-14 | P1 | GitHub release and upgrade readiness | Planned | M | RT-01 through RT-13 release checks |
| 15 / RT-15 | P2 | Screen sign-language recognition feasibility | Research only, separate scope | L | No release dependency |

### RT-01 — Finish download location and direct installation

Outcome: downloading a model inside the app never requires export → file hunt → import.

- [ ] Review and finish existing uncommitted changes rather than implementing a second downloader.
- [ ] New catalog model downloads use `Downloads/Real time transiber/models`; direct files use the adjacent `files` folder on Android 10+.
- [ ] Preserve collision safety with readable filenames; display the actual saved path and an Open folder action when supported.
- [ ] Install existing app-stored translation downloads directly through their DownloadManager URI, with no redownload.
- [ ] Keep pinned size/hash verification and atomic publication. Failed, cancelled or partial files never become selectable models.
- [ ] Offer Install/Use here and in Models; select only after verification. Never silently enable translation or change the active engine.
- [ ] Handle duplicate/already installed models, missing files, failed downloads and retry without misreporting success.
- [ ] Verify Android 9 storage behavior explicitly; maintain the local-only flavor's import path and no network delegation.
- [ ] Update README/privacy/help for public vs app-private storage and uninstall/delete behavior.
- [ ] Gate, build both flavors, verify APKs, run a real download/install check and commit a logical unit.

Acceptance: a newly downloaded model and an older app-stored model can each be
installed from the app with no export dialog; corrupt input is rejected; originals
are not unexpectedly deleted. Record which Android versions were exercised.

### RT-02 — Benchmark compact mobile translation candidates

Outcome: an evidence-backed choice, not another large model added to a crowded list.

- [ ] Evaluate the official HY-MT1.5 1.25-bit and 2-bit mobile releases first; research other recent compact translation specialists before selecting a default.
- [ ] Distinguish base-model release date, compressed variant release, download bytes, parameter count and measured runtime memory.
- [ ] Verify exact publisher artifact, license, language directions, tokenizer/prompt, tensor format and native kernel support.
- [ ] Check the 1.25-bit STQ kernel against the pinned runtime. The current local ggml header has TQ1/TQ2, but no STQ match; a GGUF extension does not establish compatibility.
- [ ] Measure cold load, warm p50/p95 translation latency, peak memory, queue delay, dropped/stale lines and thermal behavior with ASR running.
- [ ] Use the same recorded input/caption fixture and settings across current Q4 and candidates; test Nemotron and Qwen independently.
- [ ] Include Russian→Arabic/English, Chinese→Arabic/English and English→Arabic. Cover names, numbers, negation, punctuation and short/noisy fragments.
- [ ] Separate bilingual-reviewed results from automatic scores and unchecked examples; record sample counts and device/SoC/build.
- [ ] Prefer a candidate under 600 MB with at least 25% lower warm p95 than Q4 at comparable useful quality. These are proposed selection thresholds, not measured claims.
- [ ] If no candidate passes, retain the known working model and record why; do not label an untested option “Fastest.”

Acceptance: publish a reproducible comparison and a selected candidate or explicit
no-winner decision. A recent quantization of an older base must not be marketed
as a newer base model. Smaller does not automatically imply faster.

### RT-03 — Integrate the compact translation engine

- [ ] Add the winning format/kernel with a pinned reproducible build; preserve JNI-only exports and Vosk symbol isolation.
- [ ] Implement any required prompt/tokenizer adapter and strict compatibility validation.
- [ ] Extend the catalog with pinned SHA/size, exact source→target coverage, license and runtime requirements.
- [ ] Retain the current Q4 model as a usable comparison/rollback option; move Q6/Q8 into Advanced variants.
- [ ] Preserve handle ownership, cancellation during load/decode, model replacement, bounded context and verbatim native errors.
- [ ] Add meaningful native/host tests for the changed parser/kernel boundary and supported model load/inference.
- [ ] Test switch/off/clear/stop while translating; check combined ASR + translator memory and long-session stability.
- [ ] Gate, install and document device evidence before recommending it as the mobile default.

Acceptance: supported weights import and translate locally through the existing
bridge; unsupported weights fail safely; the measured RT-02 improvement survives
integration. No automatic cloud fallback or change to existing user model choice.

### RT-04 — Split bubble settings by purpose

- [ ] Replace the monolithic panel with clearly labeled **Appearance** and **CC & translation** destinations.
- [ ] Put the six appearance controls listed above within a short panel; show a live preview.
- [ ] Limit CC/translation quick settings to mode, languages, translation toggle and current model summaries.
- [ ] Move model installation, provider keys, TTS configuration and diagnostics to the appropriate app screen.
- [ ] UI-only changes must not restart recording, ASR, translator or the foreground service.
- [ ] Preserve the active session when opening full setup; require an explicit model apply action if a restart is needed.
- [ ] Check talkback labels, touch targets, scrolling and focus at large system fonts.

Acceptance: Height is reachable by opening Appearance, with no scrolling through
models or keys. Changing opacity leaves transcript and engine session intact.

### RT-05 — Make height and position predictable

- [ ] Replace the competing normal/settings/history height rules with one explicit geometry policy.
- [ ] Add a visible live Height slider and Compact / Comfortable / Large presets; keep current comfortable size available.
- [ ] Derive minimum height from reachable toolbar and readable caption content, rather than an arbitrary percentage alone.
- [ ] Separate vertical placement from height; support Top/Bottom, drag, reset position and notification recovery.
- [ ] Persist the chosen dimensions and position; consider portrait/landscape preferences without unexpected migration resets.
- [ ] Clamp against usable window bounds, system bars/cutouts and orientation changes; never strand the drag/close controls off-screen.
- [ ] Keep settings/history within safe bounds without permanently changing the chosen reading size.
- [ ] Host-test geometry boundaries; exercise rotation, font scaling, expand/collapse and recovery on a phone.

Acceptance: the user can make the bubble visibly shorter and place it lower,
restart the app and retain the choice, then recover it after rotation. Text growth
scrolls inside the selected viewport instead of increasing screen coverage.

### RT-06 — Simplify setup and navigation

- [ ] Build the Captions / Models / Settings structure above; preserve access to direct downloads under Models.
- [ ] Put audio source, CC/Translate, language pair and Local/Cloud ahead of engine names.
- [ ] Show one recommended compatible installed setup; put alternatives behind Change model.
- [ ] Keep Start visible in a sticky action area; replace long prerequisite explanations with focused actions only when needed.
- [ ] Request permissions when needed, with a clear retry path and the real Android capture limitation explained once.
- [ ] Keep advanced settings collapsed and move always-visible diagnostic buttons out of the main header.
- [ ] Preserve last-used setup across navigation/restart; represent missing model, missing key and ready state distinctly.
- [ ] Provide a short first-run path and an equally short repeat-use path; do not force a tutorial on upgrades.

Acceptance: an already configured user can start from the initial screen without
scrolling. A new user can follow one coherent path to captions without visiting
both separate engine and duplicated model setup sections.

### RT-07 — Model library and download lifecycle

- [ ] Use Installed / Discover, with Speech recognition and Translation roles clearly separated.
- [ ] Filter by chosen input/output languages; explain unsupported pairs instead of offering unusable combinations.
- [ ] Model cards show purpose, download size, required free space, verified language coverage and device-tested status.
- [ ] Hide quantization jargon behind Advanced; use measured speed/quality descriptions only when evidence exists.
- [ ] One **Download & install** action progresses through downloading → verifying → installed → Use, surviving navigation/process restart.
- [ ] Add readable progress, cancellation, retry and resume where the transport actually supports it; do not promise resumability on every URL.
- [ ] Keep manual Import for external files; recognize known downloaded models without asking users to match six cryptic filenames.
- [ ] Preflight staging space and expose separate Delete download / Uninstall model actions with clear effects.
- [ ] Avoid parallel duplicate installs and excessive temporary copies; retain a verified private inference copy or equally safe immutable representation.

Acceptance: normal users do not see a required URL/filename form or export picker
when acquiring a catalog model; interrupted installs recover without phantom
Installed entries. Direct URLs remain a separate advanced action.

### RT-08 — Shared settings and migrations

- [ ] Separate persisted appearance preferences, speech/translation setup and transient overlay panel state.
- [ ] Make app screens and bubble controls use the same source of truth; remove stale local copies/duplicated controls.
- [ ] Define which changes apply live, reload only translation, or restart ASR/capture.
- [ ] Migrate current size, history, selected models, language pair and keys without losing valid user preferences.
- [ ] Add distinct Reset appearance and Reset session setup actions; never wipe keys/models as a side effect.

Acceptance: settings edited in the app are reflected in the bubble and vice versa;
an upgrade retains installed models and working cloud configuration.

### RT-09 — Reading comfort and deaf/hard-of-hearing accessibility

- [ ] Keep finalized text visible until replaced or explicitly cleared; previous lines remain distinguishable and readable.
- [ ] Offer a simple Previous lines / Keep visible setting, readable contrast and Original / Translation / Both display modes.
- [ ] History reading must not jump when new captions arrive; show a new-lines indicator and Back to live.
- [ ] Make recording, paused, silence, translation loading and failure states visible without sound or color alone.
- [ ] Keep TTS opt-in/off by default; no automatic speaking when the translator is enabled.
- [ ] Check Arabic RTL, mixed-script numbers, punctuation, large fonts, focus order and accessible control names.
- [ ] Keep copy/export user-initiated; any persistent transcript history must be opt-in with a clear delete action.

Acceptance: a user can read earlier text while new captions arrive, stop capture
without sound cues, and use a compact bubble at large system text settings.

### RT-10 — Pipeline latency and reliability

- [ ] Instrument ASR finalization, queue wait, model load, translation decode and display separately using monotonic timings.
- [ ] Define caption latency from audio timestamp to display; do not call token speed or ASR compute/audio ratio end-to-end latency.
- [ ] Tune final-utterance segmentation with real Russian/Chinese streams; protect words/numbers at chunk boundaries.
- [ ] Measure CPU contention/thread counts and warm model behavior before adding optional GPU/NPU backends.
- [ ] Retain bounded queues and visible dropped/stale-line outcomes; never silently accumulate minutes of work.
- [ ] Check repeated pause/resume, language/model switches, projection loss, backgrounding, thermal pressure and memory exhaustion.
- [ ] Keep notification Pause/Resume, Show, Recenter and Stop reachable; repeated commands must be safe.

Acceptance: publish a 30-minute combined-pipeline run with p50/p95 latency, memory,
thermal observations and stale/drop counts. Errors keep usable CC when possible.

### RT-11 — Cloud models and keys

- [ ] Verify speech-only filtering by role, keeping transcription, live translation and TTS choices separate.
- [ ] Sort available provider-created timestamps newest first, label their meaning honestly and handle missing dates.
- [ ] Show price/unit only when supplied or maintained from a dated authoritative source; never infer price from a model name.
- [ ] Move key entry to Settings → Cloud providers; show Saved/Test/Replace/Remove without exposing the saved key.
- [ ] Preserve encryption; test invalid/expired keys, offline errors and process recreation without logging secrets.
- [ ] Keep model/file download hosts isolated from provider credentials; foss must neither call nor delegate network operations.

Acceptance: choosing STT never presents image/chat/TTS-only models; local caption
setup does not demand a key; cloud failures show the actual cause.

### RT-12 — Errors and diagnostics

- [ ] Show a concise actionable state plus expandable original error text, without changing the underlying cause.
- [ ] Identify failing stage: capture, speech load/inference, translation load/inference, download or integrity check.
- [ ] Add a deliberate diagnostics export containing app/build/device, model IDs and stage timings, excluding keys/audio/transcripts by default.
- [ ] Provide direct recovery actions: choose source language, install model, retry, switch to CC, recover bubble or stop.
- [ ] Keep diagnostics off the ordinary start screen unless a failure needs attention.

Acceptance: a reported failure can be traced to its layer, and a translation
failure never masquerades as translated success or prevents the user stopping.

### RT-13 — Consistent visuals and Arabic UX

- [ ] Establish shared spacing, typography, component states and iconography; replace text-as-icons in navigation.
- [ ] Reduce nested cards, repeated headings, horizontal chip walls and small instructional paragraphs.
- [ ] Localize primary journeys in Arabic and English; retain technical model identifiers where useful.
- [ ] Use responsive layouts for portrait, landscape, small windows and large fonts; avoid fixed-height clipping.
- [ ] Add real screenshots for empty/loading/ready/error states and both reading directions; resolve visual defects before release.

Acceptance: the same action has the same label and visual treatment across screens;
Arabic layouts and 200% text remain navigable, including Start and Stop.

### RT-14 — Publication and release readiness

- [ ] Complete the release-critical acceptance checks above; publish an honest tested-device/known-limitations matrix.
- [ ] Refresh README, onboarding screenshots, privacy/storage behavior, license notices and model setup instructions.
- [ ] Verify dependency/model redistribution terms; model weights remain separate downloads unless explicitly licensed and intentionally packaged.
- [ ] Run required gates, both QA builds and APK verification; publish monotonically named artifacts and record checksums.
- [ ] Verify an upgrade from 0.2.0 retains keys/models/settings and can install its older completed downloads.
- [ ] Resolve GitHub account/repository destination before publishing; prepare clean repo content and release notes first.
- [ ] Keep signing material outside Git; clearly distinguish debug-signed QA APKs from a stable production signing identity.
- [ ] Scan publication contents for secrets, generated builds/weights and unrelated Hearth code/history.

Acceptance: a reader of the GitHub project can install the correct APK, acquire a
compatible model and start captions from the published instructions. Nothing is
called shipped merely because a version field or a local commit exists.

### RT-15 — Screen sign language → text feasibility (retained earlier request)

The owner's corrected direction is **signing visible on a captured screen/video →
text**, not camera-only input and not text → avatar. Keep this separate from the
speech-caption release; AGENTS.md explicitly excludes camera/sign recognition from
this standalone app's present implementation scope.

- [ ] Research continuous sign-language recognition/translation from screen video, not isolated alphabet gestures.
- [ ] Treat ASL and the chosen Arabic sign language as separate capabilities; Arabic variety is still unspecified.
- [ ] Identify available trained models, dataset/weight licenses, runtime footprint and actual language coverage.
- [ ] Evaluate frame sampling, visible hands/face/body, motion context, signer variation and confidence/abstention.
- [ ] Write a feasibility result and propose an isolated prototype/repository only if evidence supports it.
- [ ] Do not present guessed sign text as an accessibility aid or claim general sign-language support from a small gesture classifier.

Acceptance: a documented go/no-go decision with credible evidence and explicit
limits; no extra permissions or unfinished sign UI in the speech app.

## Model research notes (checked 2026-09-12)

Tencent's April 29, 2026 release lists approximately 440 MB (1.25-bit) and 574 MB
(2-bit) HY-MT1.5 mobile variants. These are compressed versions of the 1.8B base,
not fewer-parameter models and not a newer base than the existing Hy-MT2 option.
The 1.25-bit card explicitly requires its STQ kernel and links a llama.cpp PR.
Those facts make them candidates for RT-02, not already-supported choices or
measured phone speed claims. Verify exact artifact bytes/hashes during integration.

- [Official 1.25-bit model and kernel requirement](https://huggingface.co/tencent/Hy-MT1.5-1.8B-1.25bit-GGUF)
- [Official 2-bit model](https://huggingface.co/tencent/Hy-MT1.5-1.8B-2bit-GGUF)
- [Publisher release history](https://github.com/Tencent-Hunyuan/Hy-MT)
- [STQ implementation proposal](https://github.com/ggml-org/llama.cpp/pull/22836)

## Working rules and completion evidence

For each task record: commit, changed behavior, tests/checks run, device and artifact
(if applicable), and remaining limitations. Preserve the existing native ownership,
verbatim error, model-integrity and offline-flavor rules. Every new utility needs a
consumer and meaningful host coverage. Do not rewrite common JNI indiscriminately;
change the measured failing or bottlenecked layer and preserve working engines.

Before code landing: `./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin`.
Native changes also require relevant speech/common/native host suites and
`:common-jni:externalNativeBuildDebug`. APK delivery requires both QA assemblies
and `python3 scripts/verify-release.py`. Never mark all language pairs tested from
a single sentence. No new runtime change is part of this backlog-writing task.

Planning baseline check: required Gradle test and both QA Kotlin compile gates
passed on 2026-09-12 (183 tasks up-to-date). This validates the current workspace
build baseline; it does not validate or deliver the planned redesign.

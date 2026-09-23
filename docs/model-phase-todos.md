# Speech and translation: next phase

Reviewed 2026-09-23 against the shipped model catalog, benchmark adapters,
`validation-v46.md`, and the Samsung phone smoke run. This is the active todo
table for the next model phase. The older `oss-extension-plan.md` records the
0.5.0 proposal; its "no benchmark framework" scope is historical.

The browser now groups model families, checkpoints and quantizations, and the
app can download, verify, install, select and compare supported models. The
phone smoke run verified one Whisper download and short speech/translation
comparisons. A follow-up [Marian phone run](marian-phone-smoke-2026-09-23.md)
verified three small ONNX translation pairs and paused/resumed one 184 MiB
transfer. It did **not** establish a fast, good-quality offline translator:
on the tested Samsung, Hy-MT2 Q4 took 53.01 s for a six-sentence warm pass
(about 8.8 s/sentence), while ML Kit took 0.39 s but needs bilingual quality
review. Those translation scores used one publisher reference, not a human
adequacy judgment. A later live device-audio run confirmed Russian and Chinese
Nemotron captions flow through the matching Marian pair to English overlay
text; phrase-end latency was not measured.

The [June–September model search](translation-models-jun-sep-2026.md) adds
MiLMMT-46 Q4 as an **experimental** 806 MB family. The corrected Samsung run
finished English→Arabic in 22.72 s and Russian→Arabic in 25.75 s per warm
six-sentence pass, with meaning errors; it is not a fast live default. LMT-60
was rejected after a fast but inaccurate host trial. The Hy-MT2 Q3 phone quick
set hit the 20-second native limit. The supplied broader shortlist was checked
against publisher files in the model-search document: TranslatePsy needs a new
Bergamot runtime, while Qwen3-ASR and Nemotron are already supported. New
speech candidates remain unverified on this device except for the experimental
Omnilingual CTC 300M v2 INT8 route: its shared C++ C ABI, host clips and S22
English, Russian, Arabic and Chinese quick sets now run in the app. It does not force a spoken language.
Two optional native Marian cascades also reach Russian/Chinese→Arabic through
English; six-sentence S22 warm passes were 4.20 s and 3.78 s respectively,
with limited automatic reference scores and no bilingual adequacy claim.

| ID | Priority | State | Task and subtodos | Done when |
| --- | --- | --- | --- | --- |
| M01 | P0 | Nemotron live Russian/Chinese and Qwen live Chinese verified; delay/Arabic/edge cases open | The bridge now reports preparation, queue and inference separately and restores its ready state after a failure. Forced Russian/Chinese Nemotron and forced Chinese Qwen playback showed English translation in the overlay. Clear, Stop and model switch worked in the visual smoke. Finish Qwen Russian, Arabic directions using a supported translator, CC-only and phrase-end latency. | Original CC remains readable; every supported final caption either gets the correct translation or a verbatim/actionable failure. No old-session translation appears. Record actual source and translated-caption delay on the phone. |
| M02 | P0 | Three direct pairs and two English-pivot routes integrated; quality review open | Marian English→Arabic, Russian→English and Chinese→English use pinned four-file ONNX packages, grouped UI, one-tap public-folder downloads and the shared translator/benchmark. Optional Russian/Chinese→Arabic uses two loaded native sessions via English, with explicit readiness and error propagation. The S22 six-sentence warm passes were 4.20 s and 3.78 s. Review bilingual meaning and concurrent memory before calling these general-purpose. | Each route is selectable only with the required installed packages, translates through Captions and the phone benchmark without cloud, and reports failure at the correct stage. Publish human-reviewed examples before promotion to a default. |
| M03 | P0 | Timing split and Easy setup choice added; Q3 failed | The wizard defaults to the phone-tested small English→Arabic pair in Play, offers Russian→English and Chinese→English, and labels broad Hy-MT2 Q4 as slow. It forces spoken language. Q3 did not finish the phone quick set within 20 seconds. Profile live queue/decode and evaluate MiLMMT on-device before recommending a new default. | Captions do not accumulate a growing translation backlog; the default is supported by phone evidence and clearly distinguishes fast/light from broad coverage. No quant is labeled faster merely because its file is smaller. |
| M04 | P0 | Normal install and pause/resume verified; fault cases partly covered | The UI installed three Marian packages under `Downloads/Hearth/models`; a 184 MiB Chinese decoder paused at 30 MiB and resumed from 66 MiB to verified install. Whisper Tiny Q5_1 also downloaded and selected through the UI. A completed pinned original is now rechecked before an install retry; if edited or removed, Retry starts a clean download. A host test covers changed, truncated and oversized bytes plus cancellation. Still exercise a corrupt partial, low storage and a chosen SAF folder on the phone. | A user can recover without export/re-import or a false Installed state; the original file remains in `Downloads/Hearth/models` or the chosen folder. |
| M05 | P1 | Omnilingual CTC integrated and four S22 quick sets verified; S25 review open | A new Meta Omnilingual CTC 300M v2 INT8 package has a pinned 279 MiB download, a C++ adapter behind the versioned speech ABI, Mac host transcript tests, and Samsung S22 quick sets in English, Russian, Arabic and Chinese. Warm six-clip passes took 6.01–6.73 s for 43.82–49.14 s of audio with 0/2 silence false positives for each language. It is experimental, utterance-windowed and has no language-forcing input. Later compare Moonshine Base and forced-language Qwen/Nemotron on the S25. | Exact package, runtime adapter, phone transcript, timing and model load are recorded. Do not call this a speed/quality winner from one small set or advertise unsupported forcing; peak concurrent memory still needs a sustained run. |
| M06 | P1 | Per-language custom input, local export and S22 EN/RU→AR trials done; broader quality review open | The benchmark lets the owner choose every language advertised by installed models and supply their own WAV or corrected source text, with optional reference. Results now retain 200 entries, filter by chosen language pair, and export through Android's document picker for S25 comparisons. Finish names/numbers/negation, bilingual review and a short live cascade run on the owner's S25. | A published table separates ASR error, translation adequacy, model load, stable source/translation delay, and memory. Six-sentence automatic scores are not presented as general quality rankings. |
| M07 | P1 | Not promoted as a live default | The existing S22 TranslateGemma Q4 run timed out at 20 seconds on most short pairs even before concurrent Nemotron loading. The pinned 2.49 GB artifact remains an optional advanced choice; it is not a fast-mobile recommendation. A future phone with more headroom may repeat joint inference and cleanup if a user asks for this model. | The working small choices remain the defaults; large experimental models are clearly labeled. |
| M08 | P1 | Marian grouping and benchmark selection fixed; full audit open | Polish the model chooser around real evidence: family → checkpoint → quant, show active versus browsed model, download/installed/storage state, supported directions and explicit Play-only/FOSS availability. State which choices are phone-tested. The benchmark now respects an empty selection instead of silently selecting default models. | A new user can choose and activate a working speech model and translator without a manual package recipe; screen-reader labels and back navigation stay usable. |
| M09 | P2 | Account-dependent | Check Soniox and Scribe with authorized BYOK accounts: source/translation finality, reconnect, Clear, failure text and billing-relevant session behavior. | Real-account findings are recorded separately from parser tests; no provider is called verified solely because fixtures pass. |
| M10 | P2 | Deferred | Consider Gemma 4/LiteRT-LM translation and sherpa Nemotron ONNX/QNN only after M01–M03 produce a usable baseline. Require exact artifact/runtime and a CPU/device comparison before a public choice. | A new runtime is promoted only with an actual on-phone benefit and maintained lifecycle; a model card or export alone is not enough. |

## Decisions already made

- The HY-MT1.5 special 2-bit SEQ artifact is incompatible with the pinned native
  runtime's kernels. Do not reopen it as a quick-size task. Hy-MT2 Q2/Q3 are
  catalogued GGUF alternatives, but their live speed and quality are unproven.
- The older OPUS-MT pair catalog was removed because it was not connected to a
  production installer/consumer. The three new Marian choices each have a
  verified four-file installer, shared native consumer and exact direction.
  Russian/Chinese→Arabic is now an optional two-model English-pivot route and
  depends on both installed packages; it is not a direct-pair model.
- The current benchmark is a useful short foreground comparison, not a
  microphone-to-translation latency or thermal-endurance test. Keep the next
  measurements bounded and tied to decisions users can feel.
- Preserve the working cloud BYOK flow, encrypted keys, local/foss network
  boundary, model integrity checks and native cleanup while adding options.

Evidence: [Omnilingual native/phone run](native-speech-omnilingual-2026-09-23.md),
[independent NDK review prompt](ndk-audio-review-prompt.md),
[phone smoke](benchmark-phone-smoke-2026-09-23.md),
[model sources](model-sources.md), [local benchmark](local-benchmark.md),
[validation](validation-v46.md), and [original expansion plan](oss-extension-plan.md).

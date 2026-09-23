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
speech candidates remain unverified on this device.

| ID | Priority | State | Task and subtodos | Done when |
| --- | --- | --- | --- | --- |
| M01 | P0 | Nemotron live Russian/Chinese and Qwen live Chinese verified; delay/Arabic/edge cases open | The bridge now reports preparation, queue and inference separately and restores its ready state after a failure. Forced Russian/Chinese Nemotron and forced Chinese Qwen playback showed English translation in the overlay. Clear, Stop and model switch worked in the visual smoke. Finish Qwen Russian, Arabic directions using a supported translator, CC-only and phrase-end latency. | Original CC remains readable; every supported final caption either gets the correct translation or a verbatim/actionable failure. No old-session translation appears. Record actual source and translated-caption delay on the phone. |
| M02 | P0 | Three direct pairs integrated and live path verified; quality review open | Marian English→Arabic, Russian→English and Chinese→English use pinned four-file ONNX packages, grouped UI, one-tap public-folder downloads and the shared translator/benchmark. The three packages ran in the Android app and native phone smoke; Russian/Chinese→English also worked after live ASR. Review bilingual meaning, memory over a long session and missing directions before calling them general-purpose. | At least one smaller candidate is selectable and translates a named source→target pair through Captions and the quick benchmark, on the phone and without cloud. Publish size, memory, delay and human-reviewed examples beside ML Kit and HY. Unsupported pairs remain unavailable. |
| M03 | P0 | Timing split and Easy setup choice added; Q3 failed | The wizard defaults to the phone-tested small English→Arabic pair in Play, offers Russian→English and Chinese→English, and labels broad Hy-MT2 Q4 as slow. It forces spoken language. Q3 did not finish the phone quick set within 20 seconds. Profile live queue/decode and evaluate MiLMMT on-device before recommending a new default. | Captions do not accumulate a growing translation backlog; the default is supported by phone evidence and clearly distinguishes fast/light from broad coverage. No quant is labeled faster merely because its file is smaller. |
| M04 | P0 | Normal install and pause/resume verified; fault cases partly covered | The UI installed three Marian packages under `Downloads/Hearth/models`; a 184 MiB Chinese decoder paused at 30 MiB and resumed from 66 MiB to verified install. Whisper Tiny Q5_1 also downloaded and selected through the UI. A completed pinned original is now rechecked before an install retry; if edited or removed, Retry starts a clean download. A host test covers changed, truncated and oversized bytes plus cancellation. Still exercise a corrupt partial, low storage and a chosen SAF folder on the phone. | A user can recover without export/re-import or a false Installed state; the original file remains in `Downloads/Hearth/models` or the chosen folder. |
| M05 | P1 | New speech family still open | Compare more **working** speech choices on the S25: Moonshine Base English, a Whisper Base/Small quant, and the forced-language Russian/Chinese Qwen/Nemotron routes. For a genuinely different compact family, first prototype Meta Omnilingual CTC 300M or Canary 180M through the pinned sherpa runtime; both are older, and Canary has no Arabic. Cohere Arabic (July 2026) is the fresh Arabic contender but its current Q4 exports exceed the 1 GB file target. Keep windowed, continuous and language-forcing capabilities separate. | A new speech download button appears only after an exact package, runtime adapter, phone transcript, timing and memory are verified. No unverified Qwen 1.7B, Vosk or Cohere button. |
| M06 | P1 | Per-language custom input, local export and S22 EN/RU→AR trials done; broader quality review open | The benchmark lets the owner choose every language advertised by installed models and supply their own WAV or corrected source text, with optional reference. Results now retain 200 entries, filter by chosen language pair, and export through Android's document picker for S25 comparisons. Finish names/numbers/negation, bilingual review and a short live cascade run on the owner's S25. | A published table separates ASR error, translation adequacy, model load, stable source/translation delay, and memory. Six-sentence automatic scores are not presented as general quality rankings. |
| M07 | P1 | Open | Finish optional TranslateGemma phone validation: check pinned artifact/license/size, load alongside Nemotron, translate short lines, then switch/stop/restart and inspect memory. | It is offered as a larger quality option only if joint inference and cleanup work on a phone; otherwise document the limitation and leave the working choices intact. |
| M08 | P1 | Marian grouping and benchmark selection fixed; full audit open | Polish the model chooser around real evidence: family → checkpoint → quant, show active versus browsed model, download/installed/storage state, supported directions and explicit Play-only/FOSS availability. State which choices are phone-tested. The benchmark now respects an empty selection instead of silently selecting default models. | A new user can choose and activate a working speech model and translator without a manual package recipe; screen-reader labels and back navigation stay usable. |
| M09 | P2 | Account-dependent | Check Soniox and Scribe with authorized BYOK accounts: source/translation finality, reconnect, Clear, failure text and billing-relevant session behavior. | Real-account findings are recorded separately from parser tests; no provider is called verified solely because fixtures pass. |
| M10 | P2 | Deferred | Consider Gemma 4/LiteRT-LM translation and sherpa Nemotron ONNX/QNN only after M01–M03 produce a usable baseline. Require exact artifact/runtime and a CPU/device comparison before a public choice. | A new runtime is promoted only with an actual on-phone benefit and maintained lifecycle; a model card or export alone is not enough. |

## Decisions already made

- The HY-MT1.5 special 2-bit SEQ artifact is incompatible with the pinned native
  runtime's kernels. Do not reopen it as a quick-size task. Hy-MT2 Q2/Q3 are
  catalogued GGUF alternatives, but their live speed and quality are unproven.
- The older OPUS-MT pair catalog was removed because it was not connected to a
  production installer/consumer. The three new Marian choices each have a
  verified four-file installer, shared native consumer and exact direction;
  Russian/Chinese→Arabic remains unavailable locally with these direct pairs.
- The current benchmark is a useful short foreground comparison, not a
  microphone-to-translation latency or thermal-endurance test. Keep the next
  measurements bounded and tied to decisions users can feel.
- Preserve the working cloud BYOK flow, encrypted keys, local/foss network
  boundary, model integrity checks and native cleanup while adding options.

Evidence: [phone smoke](benchmark-phone-smoke-2026-09-23.md),
[model sources](model-sources.md), [local benchmark](local-benchmark.md),
[validation](validation-v46.md), and [original expansion plan](oss-extension-plan.md).

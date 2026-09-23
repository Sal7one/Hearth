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

| ID | Priority | State | Task and subtodos | Done when |
| --- | --- | --- | --- | --- |
| M01 | P0 | Nemotron live Russian/Chinese and Qwen live Chinese verified; delay/Arabic/edge cases open | The bridge now reports preparation, queue and inference separately and restores its ready state after a failure. Forced Russian/Chinese Nemotron and forced Chinese Qwen playback showed English translation in the overlay. Clear, Stop and model switch worked in the visual smoke. Finish Qwen Russian, Arabic directions using a supported translator, CC-only and phrase-end latency. | Original CC remains readable; every supported final caption either gets the correct translation or a verbatim/actionable failure. No old-session translation appears. Record actual source and translated-caption delay on the phone. |
| M02 | P0 | Three direct pairs integrated and live path verified; quality review open | Marian English→Arabic, Russian→English and Chinese→English use pinned four-file ONNX packages, grouped UI, one-tap public-folder downloads and the shared translator/benchmark. The three packages ran in the Android app and native phone smoke; Russian/Chinese→English also worked after live ASR. Review bilingual meaning, memory over a long session and missing directions before calling them general-purpose. | At least one smaller candidate is selectable and translates a named source→target pair through Captions and the quick benchmark, on the phone and without cloud. Publish size, memory, delay and human-reviewed examples beside ML Kit and HY. Unsupported pairs remain unavailable. |
| M03 | P0 | Timing split and Easy setup choice added; quant trial open | The wizard now defaults to the phone-tested small English→Arabic pair in Play, offers Russian→English and Chinese→English, and labels the broad Hy-MT2 Q4 route as slow. It forces the selected spoken language. Still profile live queue/decode, investigate capped/empty HY output, and compare Q2/Q3 with Q4 before recommending a quant. | Captions do not accumulate a growing translation backlog; the default is supported by phone evidence and clearly distinguishes fast/light from broad coverage. No quant is labeled faster merely because its file is smaller. |
| M04 | P0 | Normal install, pause/resume and Whisper UI verified; fault cases open | The UI installed three Marian packages under `Downloads/Hearth/models`; a 184 MiB Chinese decoder paused at 30 MiB and resumed from 66 MiB to verified install. The earlier phone run downloaded, installed and selected Whisper Tiny Q5_1 through the UI. Still corrupt a partial file, retry verification, and check low-storage and chosen-folder behavior. | A user can recover without export/re-import or a false Installed state; the original file remains in `Downloads/Hearth/models` or the chosen folder. |
| M05 | P1 | Open | Compare more **working** speech choices on the same phone: Moonshine Base English, a Whisper Base/Small quant that fits, and the current forced-language Russian/Chinese Qwen/Nemotron routes. Keep utterance-windowed and continuous behavior labeled separately. | Models can be installed and selected through normal UI, and same-language timing/accuracy results state exact checkpoint, quant, RAM and runtime. No unverified Qwen 1.7B or Vosk download button. |
| M06 | P1 | Open | Extend the quick comparison only where it changes a decision: add representative Russian/Chinese/Arabic speech and translation directions, names/numbers/negation, two silence cases, and a small bilingual review of translation meaning. Compare the same clips on the same device and include a short live cascade run. | A published table separates ASR error, translation adequacy, model load, stable source/translation delay, and memory. Six-sentence automatic scores are not presented as general quality rankings. |
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

# VoiceStudio research for Hearth

The initial chunking review below is supplemented by the full
[engine/checkpoint and NDK pipeline comparison](voicestudio-engine-comparison.md).
That source-level comparison covers file transcription versus actual incremental
ASR, the Android client, STT/TTS/translation runtime boundaries and exact model
identities. Implemented pipeline changes and verification are recorded in
[pipeline ownership](pipeline-ownership.md#capability-driven-pcm-and-native-failures).

Reviewed 2026-10-18 at VoiceStudio commit
[`d819ff54d93017bf4a5b34b605833162163c9e3c`](https://github.com/debpalash/VoiceStudio/tree/d819ff54d93017bf4a5b34b605833162163c9e3c).
This is a source review, not a voice-quality, Android performance or model-accuracy
benchmark. VoiceStudio's desktop runtime and model catalog do not establish
Android compatibility. Its combined language headline is not a capability list
for an individual engine.

## Implemented: better bounded read-aloud text

VoiceStudio's [chunked synthesis](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/chunked_tts.py)
and [streaming sentence chunker](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/sentence_chunker.py)
show why sentence detection needs decimal, abbreviation, closing-punctuation and
multilingual guards. They also identify punctuation-only fragments as a source
of empty synthesis output. These are useful failure cases for Hearth's existing
bounded read-aloud path.

Previously `VoiceText.chunks` could split after `12.` in `12.50` or after `Dr.`
before a person's name, and could send a closing quote as a separate request.
Hearth now uses its own Kotlin boundary logic to:

- Prefer complete sentences while recognizing common Latin abbreviations,
  initials and dots inside numbers or domains.
- Include closing quotes/brackets and consecutive sentence punctuation in the
  same request where they fit; move speech to the next chunk when needed.
- Prefer clauses before arbitrary word cuts, including Arabic `،` / `؛` and
  Japanese/Chinese punctuation.
- Rebalance a short punctuation-only final fragment with preceding speech,
  preserving its punctuation and the strict per-request limit.

The production consumer remains `VoicePlayer`: Android and self-hosted voices
use a 300-character ceiling; Supertonic uses 180. The whole request remains
limited to 5,000 UTF-16 code units. Existing queue bounds, sequential playback,
Stop, native ownership, language validation and actual synthesis errors remain
in force. No external code, dependencies or model assets were copied.
The separate cloud caption TTS path keeps its provider contracts and request
count; this change does not introduce additional billable requests there.

This is a bounded heuristic, not a linguistic tokenizer. Abbreviations outside
the small recognized set may still be split. Long unbroken words must still be
cut, and pathological runs of punctuation can still reach the selected engine's
normal validation. No audible quality or latency improvement is claimed without
owner listening tests.

## Findings and next candidates

| Research material | What transfers to Hearth | Decision |
| --- | --- | --- |
| [Supertonic 3](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/docs/engines/supertonic3.md) | Small CPU-oriented ONNX voices, explicit language/style metadata and warm-session reuse | Hearth already has pinned native Supertonic, 31 languages and explicit styles. Keep that path. Measure cold-load versus repeated playback before changing its current model lifetime; retaining voice weights alongside ASR/translation could increase phone memory pressure. |
| [Sherpa live ASR](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/docs/engines/sherpa-onnx-asr.md) | Distinguish true streaming partials from periodic offline decoding; choose endpoint settings and thread counts per model | Hearth already integrates sherpa and several speech families. A small streaming Zipformer is a candidate for an English or Chinese latency comparison, subject to pinned artifacts, native runtime support, exact-once finals and owner measurements. VoiceStudio's desktop model sizes/timings are not phone results. |
| [PocketTTS](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/docs/engines/pockettts.md) | CPU-first TTS with a narrower language matrix | Evaluate only as a future self-hosted voice option after confirming its actual runtime and model terms. The reviewed catalog lists six languages and no Arabic; it is not a replacement for Hearth's Arabic-capable Supertonic. No Android model is added. |
| [OmniVoice GGUF](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/docs/engines/omnivoice-gguf.md) | Verify runtime binaries separately from quantized weights and expose real readiness failures | A GGUF extension alone does not make a model compatible with Hearth's speech/translation runtimes. A future experiment needs its own Android build, supported ABI matrix, tokenizer/audio contract, integrity pins and measured memory use. No desktop executable is imported. |
| [Downloads](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/docs/downloading-models.md) | Show real bytes and distinguish resolving, downloading and ready; isolate host credentials from inference credentials | Hearth already has verified imports and downloads. Keep the no-network FOSS gate, including Android download delegation. Do not add automatic mirror probes, arbitrary cache acceptance or provider keys on file hosts. Parallel ranges would need a separately justified Android download issue and integrity/cancellation coverage. |
| [Engine routing](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/backend/services/engine_routing.py) | Distinguish an intentionally CPU-only engine from an acceleration failure; report the backend actually used | Useful for future runtime work, but desktop GPU/VRAM heuristics do not transfer to Android's shared memory or NPU drivers. Keep current device benchmark evidence as the basis for model choices. |
| [Dictation lifecycle](https://github.com/debpalash/VoiceStudio/blob/d819ff54d93017bf4a5b34b605833162163c9e3c/docs/features/dictation.md) | Bind late results to their originating session; report completion only after the destination accepts it | Preserve Hearth's existing generation/cancellation and final-text ownership. Desktop keyboard injection, clipboard replacement, and automatic ASR fallback are not introduced into Hearth's overlays. |

The next useful experiments are measured voice-session reuse and a small
streaming-ASR comparison. They remain separate work, with evidence required
before changing defaults. Video dubbing, media editing, books/audiobooks and
desktop workflow automation stay outside this app's scope.

## Verification and owner checks

`VoiceTextTest` first reproduced failures for decimals/abbreviations/websites,
Arabic clause preference and punctuation-only tails. Regression coverage also
checks mixed Arabic, Latin, Japanese and emoji text at 16, 17, 40, 180 and 300
code units, with no lost non-whitespace characters, broken surrogate pairs or
over-limit chunks. Existing normalization, language and token validation remain
covered.

Host validation passed with Temurin JDK 17.0.20:

```sh
./gradlew test :app:compilePlayQaKotlin :app:compileFossQaKotlin
```

All seven `VoiceTextTest` cases passed in both common-jni build types. The full
repository gate completed successfully; `git diff --check` also passed. This
initial chunking change modifies Kotlin text preparation only. Subsequent native
and pipeline work is recorded in the linked ownership note.

Owner checks: play a longer Arabic passage with comma/question punctuation;
an English passage with `12.50`, `Dr. Smith` and a website; and Japanese through
a compatible installed Android voice. Compare phrasing and pauses, then Stop
during a multi-part utterance. Self-hosted playback requires the owner's server.
No device control, model download or paid provider request was used for this work.

# Model Lab: compare local speech, translation and voices

Open **Settings → Model Lab** in either Basic or Advanced mode. The quick
settings sheet also opens it directly. Search Settings for “benchmark”,
“models”, “GPU”, “بنشمارك” or “基准测试”. Model Lab has three sections:

1. **Setup** — choose Speech, Translation or Voices, languages and the input.
2. **Models** — search installed models, select any number, or browse the full
   pinned catalogue and queue a download batch. Filter by family, language,
   CPU/GPU path or size. Confirm the total download size before queueing.
3. **Results** — compare a sortable, searchable table, open exact outputs and
   runtime evidence, and export the filtered selection as CSV or JSON.

There is **no model-count cap**: selecting 100 or 500 candidates is the owner's
choice. A batch runs **one model/backend at a time**, releasing it before the
next. This avoids loading hundreds of weights together. Catalogue size is the
current verified catalogue, not a claim of hundreds of distinct model families.
Compatible custom imports are also benchmark candidates; quants remain variants
of the same model family. Download registration does not wait for every transfer
to finish, and uses the existing pinned downloader, resume and verification.
Downloads continue while browsing other settings. A downloaded model becomes
selected when its installation is ready; it never starts inference automatically.

Default setup, per-stage model selections, language pair, input text, filters,
sort, advanced choices and settings search persist through the existing
DataStore-backed `UserPreferences`. A selected custom WAV is copied into bounded,
hash-checked private storage with its label, so leaving the screen or restarting
the process does not discard the input. Saudi recording drafts use a separate
private copy; their transcript/prompt state is independent of custom input text.
The separate private Saudi recording pack can be saved deliberately.

The Run/Stop footer stays visible while scrolling every tab. It states the input
and explains a missing recording, unavailable sample language, model selection,
input preparation or active engine owner. An empty Saudi pack offers both
**Record / import** and an explicit **Use bundled Arabic** action. Refreshing
catalogue labels does not lock already discovered models: installation mapping
uses receipts and bounded metadata, while actual selected model weights are
still verified before runtime initialization.

The isolated worker initializes `CommonJni` with its own application context
before opening model engines. MainActivity initialization belongs to another
process and cannot supply Vosk's private model/staging roots to this worker.
Worker initialization does not start preference migration or model audits.

Each individual recording, translation or voice sample has a **20-second hard
maximum**, with **5 / 10 / 20 seconds** available on Setup and saved in preferences.
The limit starts again for each sample in each measured pass. It covers the
complete sample, including recognition finalization, rather than allowing another
20 seconds for every chunk or token. Model verification/loading is separately
bounded at **120 seconds**; worker startup and private-request reading have **30 seconds**,
and reset, cleanup and result transfer each have **10 seconds**. None of these
budgets is renewed by progress text. Loading time remains a reported measurement;
it does not consume a sample's inference allowance.

Some providers defer native initialization until the first actual request. In
particular, the shared TTS reader and ML Kit translation can include client/model
initialization in their first sample's inference budget. Their runtime details
say so; this is not an eager-load measurement. First and warmed passes remain
separate so that setup costs are visible rather than silently removed.

The screen shows the actual PCM duration range, set total and repeated workload.
Normal publisher recordings are roughly 4–9 seconds, with optional long
recordings up to 17.1 seconds. The full corpus and its repeated passes can take
more than 20 seconds while each sample finishes within the live-inference cap.
The earlier RC3 budget incorrectly applied one 20-second deadline to loading and
the entire repeated corpus; those results do not establish a model's live latency.

A timeout disqualifies that artifact/backend for the rest of the comparison,
including later languages, decoder hints and voice styles. Other backends remain
eligible. Results offer a Disqualified filter; interrupted rows receive no speed
or quality score. JSON/CSV record the termination reason, chosen cap and elapsed
attempt time. Existing history remains readable. Timing protocol **4** records
the per-sample deadline method separately from RC3's whole-attempt protocol **3**
and older foreground timing. Resume also requires the same deadline/configuration;
an old whole-attempt timeout is never reused as a completed per-sample result.

The ready-to-run default is the bundled publisher reference set. For a Saudi
speech comparison, select **Saudi speech**, record up to six private clips (or
import PCM16 WAV files), correct each transcript, and save. References and
SHA-256-checked audio remain in private storage until cleared. Suggested prompts
are original examples and should match exactly what was spoken. A personal Saudi
comparison requires at least one saved recording; it never silently substitutes
Egyptian speech. The separate bundled-language comparison and per-model sweep
use the publisher corpus, including Egyptian Arabic when explicitly selected.

The separate **Egyptian reference** choice remains available. Its built-in
quick set contains six aligned, publisher-provided sentences for each
supported language or direction. The reference audio pack covers Egyptian
Arabic (`ar_eg`), US English (`en_us`), Russian (`ru_ru`) and Mainland Mandarin
(`cmn_hans_cn`), with translation
pairs Arabic↔English, Russian→Arabic/English, and Chinese→Arabic/English.
Those publisher Arabic text references are not a Saudi dialect translation test;
use **My recording or text** and your own reviewed references for that.
Audio and references come from the pinned FLEURS test split; see
[`app/src/main/assets/benchmark/LICENSE.txt`](../app/src/main/assets/benchmark/LICENSE.txt)
for attribution and CC BY 4.0 terms. These general test sentences do not model
Gulf dialect accuracy, every accent, or specialized captions. The four pinned
publisher `test.tsv` files, all 28 bundled WAV/PCM digests and transcriptions,
and all 36 translation references can be checked offline with
`python3 scripts/benchmark/verify_fleurs_pack.py` after the pinned metadata
has been cached by the pack generator. This verifies provenance and byte
integrity; it does not turn six sentences into a representative accuracy study.
For speech, **Long clip · 7 clips** adds the already-bundled distinct 14–17
second publisher recording to the six short clips. This exposes longer
utterance behavior without another download. The per-model four-language
sweep uses the faster six-clip set; choose a single language and Long clip
for a closer follow-up.

**Bundled language comparison** offers English, Russian and Chinese together,
with Arabic as an additional explicit choice. It applies the same corpus and
references to each model advertising that language, grouping results by language
and translation direction. Unknown model languages do not imply multilingual
coverage, and unsupported combinations have skip reasons. For translation, the
chosen destination must have published aligned references; no missing direction
is filled with another model's output. **Preview audio, source text and
references** shows exact cases and publisher attribution and plays verified WAV
audio on request. Playback stops when the dialog or app leaves the foreground.

The recording's known language is separate from decoder language control.
Choose **Match sample language**, **Automatic**, or **Compare both**. Hint-capable
multilingual models receive the explicit sample language in the matched run and
`auto` in the automatic run. Fixed-language and automatic-only engines run once
and are labelled accurately. A hint-only engine is explicitly omitted from an
automatic-only experiment. No source language is silently changed to English.
Result rows, details and exports record **Language hint / Automatic / Fixed**
and the decoder code separately from the reference language. A hint is a decoder
setting, not a guarantee about the recognized language. Historical results show
Unrecorded where that evidence is absent. Experiment identities and resume hashes
distinguish language controls as well as CPU/GPU placement.

In Model Lab, choose Speech or Translation, select the source
language and (for translation) destination, then select the built-in set. It is
selected by default. The app applies the same six samples to each compatible
installed candidate that you check, in order. **Test supported languages**
on an installed model runs the quick set for every bundled language or
translation direction that its manifest explicitly advertises, then saves a
result for each route. The per-model progress and saved AR/EN/RU/ZH speed
summary stay visible on this page. Models with only `auto` or an unknown
language do not inherit an unproven four-language claim. **Deselect all** keeps the list
empty until you choose a model. For speech it also runs 1-second and 2.5-second
silence checks; these are shown separately and are not included in reference
error rates. Choose **My recording or text** to run a single private sample with
optional corrected reference text. The language pickers offer the languages
advertised by installed or downloadable catalog models; for a pair outside the
bundled quick set, the screen offers a direct switch to your own WAV/text.
This lets the S25 owner compare any **supported** language pair with their own
material, without claiming a public reference score for a language we did not
bundle. Saved results can be filtered to the current language pair or viewed
together, and JSON/CSV exports record exact model identity, phone, timing,
outputs and any reference scores. The suggested export name includes the
current date/time so repeated exports do not silently replace earlier runs.
Run the same sample on both phones and inspect the output as well as elapsed
time.

The **Speed first / Balanced / Quality first** cards suggest one pinned speech
artifact and one translator for the selected direction. These are trial plans,
not claims that their speed or quality has already won on the phone. The plans
use the current catalog and the limited [S22 phone smoke results](benchmark-phone-smoke-2026-09-23.md)
where available; a different phone or language may reverse the order. Each stage
shows whether its artifact is installed, a direct download action in the Play
build where supported, and a shortcut to its model browser. FOSS keeps model
import/browsing without a download action. Once both stages are installed and
the quick set covers the pair, **Test speech + translation** runs their normal
production adapters sequentially and saves both results. It does not measure
simultaneous ASR/MT contention or live caption delay. The saved-result summary
shows fastest warm pass, a speed/reference-score balance, and the result closest
to the reference only among successful outputs from the same phone/app build,
exact input, and language direction. It needs at least two comparable models; an accuracy
label requires reference scores. This prevents old results or a fast empty
transcript from winning.

The benchmark run itself never accesses the network or microphone. Recording
a Saudi clip asks for microphone permission; importing a WAV does not. The
optional preset download buttons use the existing pinned model
downloader and are absent in FOSS. ML Kit runs only with packs already installed by the user in
the Play build. The speech corpus adds about 7.6 MB to the APK. Phone benchmark
results and user-selected custom recordings stay on that phone unless the user
exports the results. Export includes recognized/translated text and the
public references, but never the custom audio bytes.

## What the measurements mean

- **Load + verify** includes integrity checking and native model creation. It
  uses a fresh model session but does not promise cold disk or OS caches.
- **First pass** is the first complete pass over all selected samples;
  **warm pass median** is the median of the remaining passes with weights retained.
  The default is three total passes; Advanced allows three to ten.
- **Compute time** is accelerated replay, not microphone-to-caption latency.
  Starting with benchmark timing protocol 2, `SpeechSession` profiles feed the
  same 50 ms frame size as the overlay capture path and exclude between-clip
  `reset()` from measured inference. Legacy Whisper/Vosk candidates use their
  full-clip batch API, as identified by their route. Their first-text time is
  batch completion, not a streaming partial measurement. A fast batch result
  cannot establish lower live overlay latency. Earlier saved results remain readable but never rank
  against the new timing protocol. Silence clips remain in the throughput
  workload but are excluded from the average time-to-first-text calculation.
  For speech the real-time factor divides the warm corpus compute time by total
  audio duration. Below 1 means the measured adapter processes that test set
  faster than its audio duration. It does not measure phrase-finality or live
  queue behavior. The table shows the reciprocal as **audio speed**: for
  example, `2.0×` means the model processed this corpus twice as fast as its
  duration. Translation shows milliseconds per sentence. **Best speed** and
  **Best quality** are relative labels among comparable runs, not universal
  model rankings.
- **WER and CER** compare normalized reference/output speech text. Chinese
  publisher references insert spaces between characters, while model output
  typically does not; the app therefore reports CER only for Chinese. Translation does not
  receive WER/CER because different valid wording would be counted as errors. For translation,
  **chrF++** is the mean sentence score over the six aligned samples. These are
  automatic text-similarity scores, not bilingual judgments of meaning,
  fluency, dialect, or safety.
- **Reference score / 100** provides a readable per-language final score:
  `100 × max(0, 1 − WER)` for speech, Chinese uses CER, and translation uses
  `100 × chrF++`. The original error/similarity metrics remain visible and in
  exports. No score is invented for failed/incomplete runs, absent references or
  voice listening quality. Speed is measured separately and is not blended into
  the reference score. The **All languages** table keeps the latest exact
  input/device/protocol cohort per direction and makes no global winner across
  unrelated languages or inputs.
- Expand a result to inspect each sample, its publisher reference, model output,
  timing, exact model/runtime identity and device. A fast empty or wrong answer
  is not a quality win. Errors remain failed results; they do not become zero
  scores or successful output.

The phone runs every selected compatible model sequentially under
`LocalWorkGate`; Overlay and Traveler cannot load another local engine during
inference. Speech models receive the same decoded 16 kHz mono PCM. Streaming
adapters use 50 ms capture frames and their production options; batch adapters
remain identified as batch. At most 32 input samples are decoded for a batch;
this bounds input memory and does not limit how many models can be selected.
Each model's weights remain loaded for its measured passes.

**Voices** benchmarks the installed local voice providers through the production
`ArticleReader`, consuming bounded PCM without speaker playback. Use the same
text/language and choose individual voices. Results include time to first PCM,
synthesis time and generated duration. Each warmed pass's generated duration is
paired with its own compute time for audio-throughput comparison. The first pass
includes deferred native initialization, so separate load time alone does not
represent TTS cold start. These adapters are CPU paths. No automatic
pronunciation, naturalness or male/female voice-quality score is invented.
Listen separately after identifying promising speed results.

The table offers warm time, audio throughput, load time, first output, text
reference quality, model disk size, name and recent sorts. Search covers model,
backend, device and errors; filters expose failed, empty and confirmed-GPU rows.
Pages contain 25 rows. Winners require comparable exact input, task, language,
phone/app build and scoring/timing protocol. All-history view warns about mixed
inputs/devices. Failed or empty outputs cannot win. A disk-size column is not a
RAM estimate. Details and exports include optional battery percentage, battery
temperature, thermal status, available system memory and app process PSS **before
and after** the model run. These are instantaneous samples outside inference
timing, not peaks, power measurements or per-model RAM. Charging, other apps
and operating-system scheduling affect them.

Comparisons belong to an application-owned foreground service, so leaving this
page or backgrounding the activity does not cancel them. Its visible progress
notification opens Model Lab and provides Stop. A bounded, renewed partial wake
lock keeps a user-started long comparison progressing with the screen off.
Each attempt owns a non-exported Android worker process. Parent and independent
worker watchdogs bound native inference even if coroutine cancellation cannot
interrupt a native call. Successful work closes native handles and uses an
authenticated release acknowledgement; it does not deliberately kill the process
after every model. An idle worker process may be cached or destroyed normally by
Android. Timed-out or unconfirmed native work instead requires confirmed process
death. A previous worker's native ownership must be released before another local
model starts. If that cannot be confirmed, comparison stops
and retains the local-work lease. The Run footer shows the actual error and a
**Retry cleanup** action; it never offers a force-unlock that could overlap native
work. Recovery only releases the lease after confirming worker cleanup/death.
Saved results and recordings are retained. The parent keeps the work lease for
the full queue, and worker startup avoids competing preference migration/audit
writers. Android process termination can still interrupt a run; completed rows are
already saved. There is no automatic boot/process-restart inference. Optional
**Keep completed results** skips only successful, fully measured results matching
the exact input, pinned artifact, device/app install, options, pass count and
confirmed backend. A saved custom WAV is restored to resume the same private
input; process interruption never triggers inference automatically.

Saved history uses one atomic record per result instead of rewriting the whole
array after every model. It retains up to **10,000 records / 64 MiB**, pruning
oldest records when either bound is reached. Previous 200-row JSON histories
migrate without dropping their model outputs or pretending they use the current
timing protocol. Export includes transcripts and references, never custom audio
bytes or provider credentials. CSV quotes multiline fields and escapes spreadsheet
formula prefixes.

## Run speech and translation samples on macOS

The host translation runner uses the same six sentence IDs, adapter prompt,
llama.cpp revision, context size, output cap and 20-second per-sample deadline
as the Android GGUF adapter. It writes the app's result-JSON shape so timings,
references, outputs, model hash and device metadata can be compared together.
It does not download weights or data. Point it at an already downloaded model:

```sh
bash scripts/translation/build-runtime.sh host
python3 scripts/benchmark/run_macos_translation.py \
  --model /path/to/Hy-MT2-1.8B.Q2_K.gguf --family hy-mt2 \
  --source ru --target ar --threads 2 --passes 3
```

The report is written to `build/benchmark-results/macos-translation.json` by
default. On macOS, the build script enables Metal when Xcode's `metal` shader
compiler is available; otherwise it clearly builds CPU mode. Pass
`--gpu-layers 0` to force CPU, or use the runner's default to select the
compiled backend.

Whisper can also run the same six FLEURS speech clips and silence checks using
the vendored whisper.cpp source and a compatible, already-downloaded
whisper.cpp `.bin` model:

```sh
bash scripts/benchmark/build_macos_whisper.sh
python3 scripts/benchmark/run_macos_whisper.py \
  --model /path/to/ggml-tiny-q5_1.bin --source ru --threads 8 --passes 3
```

Its report is written to `build/benchmark-results/macos-whisper.json`. The
runner uses forced language, batch greedy decoding, 16 kHz mono input, gain
normalization, and the app's batch Whisper thresholds. Silence clips stay in
the per-sample output and count as false positives if any text is emitted. It
is an equivalent host setup for comparison, not the Android NDK build: host
CPU, compiler and thermal behavior differ. The host benchmark tools currently cover
Whisper speech and GGUF translation; a separate sherpa C++ smoke tool exercises
the Omnilingual CTC backend on real clips. They do not run Nemotron, Qwen, Moonshine,
or ML Kit as benchmark candidates on macOS. Host results must name the Mac/backend and cannot predict
phone speed, battery or sustained thermal behavior. The phone remains the
authority for Android speed and memory.

Speech comparison audio uses deterministic per-clip peak normalization matching
the host harness: when a clip peaks below 0.50 full scale, it is boosted to a
0.90 peak; digital silence is unchanged. This avoids treating unusually quiet
publisher recordings as silence while keeping processed input equal across
installed speech models. The policy is recorded in each result's runtime details.

The built-in benchmark suite also has a deterministic pack generator at
[`scripts/benchmark/make_fleurs_pack.py`](../scripts/benchmark/make_fleurs_pack.py).
It downloads only when a developer explicitly runs it; weights and personal
recordings are never included.

The checked-in [Mac host smoke report](benchmark-host-smoke-2026-09-23.md)
records a Russian Whisper / Hy-MT2 baseline, including a translation timeout
and silence hallucinations. It is a diagnostic sample, not a general model
ranking.

The [phone smoke report](benchmark-phone-smoke-2026-09-23.md) records one
Samsung run of Moonshine Tiny, ML Kit and Hy-MT2 Q4. The substantial Q4
translation delay on that device is a result, not a universal model ranking.

## Limits

Six short public sentences are a quick comparison set, not a release-quality
language evaluation. The scores cannot reveal every dialect, name, number,
negation, code-switch, noise condition, long-session thermal effect, or
ASR-plus-translation interaction. Review the exact outputs and follow a
promising result with representative recordings and bilingual review. No
unmeasured model is called faster or more accurate based on model-card claims.

`BenchmarkAudio` bounds WAV parsing and decodes selected samples once for equal
inputs. **Stop comparison** terminates the disposable model process and waits for
confirmed death, including when C++ ignores cooperative cancellation. Native
crashes or Android memory-pressure termination become disqualified rows when the
parent survives. Host watchdog/IPC coverage does not replace owner device checks
of real process termination, GPU drivers, scheduling and thermal behavior.

### Comparing CPU and GPU backends

Enable **Compare CPU and GPU backends** on Setup before a benchmark.
Whisper compares CPU/Vulkan; TranscribePsy and GGUF translators compare
CPU/Vulkan/OpenCL; Cohere, FunASR MLT and Voxtral compare CPU/Vulkan.
Other engines and local voices run once on their CPU path. All variants reuse
the same model and normalized input; they are separate history rows. The runner
closes one session before opening the next and never changes saved acceleration
choices. A missing driver is a failed row, not a substituted CPU result. Compare
output quality alongside warm compute and first-load costs. These speech runs
use accelerated replay/batch input and do not measure capture-to-overlay latency.


Requested placement and **actual backend** are separate columns. The confirmed
GPU filter accepts resolved model/compute evidence, never compile flags or an
available-device probe. GGUF evidence requires positive assigned layers and GPU
model buffers; TranscribePsy must resolve both encoder and decoder; the
transcribe.cpp families report the loaded model's backend. Whisper's current
Vulkan runtime description is a pre-load device probe, so actual placement stays
**not confirmed**, with the limitation recorded in details. A GPU request alone
is never a GPU-inference claim. Unsupported paths stay visible as failed rows;
Hearth does not silently substitute a CPU benchmark.

Local model benchmarking and download registration are disabled on TVs. FOSS
keeps import and local comparison; its clients and system-download delegation
remain disabled. Model cards advertise supported paths, not a guarantee that
every phone driver can execute every model/quant.

## Library and validation notes (2026-10-23)

The Android UI now uses Compose BOM **2026.06.01** (UI/Foundation/Runtime
**1.11.4**, Material3 **1.4.0**), Activity **1.13.0** and Lifecycle **2.10.0**.
Lifecycle Compose dependencies use the shared version catalogue rather than
independent hardcoded versions. The retained settings sheet uses Material3's
current threshold-lambda `SheetState` API and keeps its existing back/scroll
restoration contract.

These are the newest stable versions verified compatible with this SDK 36 /
AGP 8.12 build. Google's Maven AAR metadata for Compose UI **1.12.x** and
Lifecycle **2.11.0** requires SDK 37 / AGP 9.1; Activity 1.13.0 requires SDK 36 /
AGP 8.9.1. Do not bypass AAR checks or force that unrelated build-system migration.
See the official [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping),
[Activity releases](https://developer.android.com/jetpack/androidx/releases/activity)
and [Lifecycle releases](https://developer.android.com/jetpack/androidx/releases/lifecycle).
The actual compatible BOM and AAR metadata were checked against Google's Maven
repository during integration.

[AndroidX Benchmark 1.5.0](https://developer.android.com/jetpack/androidx/releases/benchmark)
is an instrumentation/microbenchmark/macrobenchmark testing library. It does not
replace a production in-app model-download and inference-comparison workflow.
Model Lab deliberately uses production adapters, the existing downloader and a
cancellable foreground service, without adding unused benchmark modules or
changing the independent speech/translation/TTS pipeline architecture.

Host coverage includes 500-model bulk selection and restoration, 500-candidate
CPU/GPU expansion, 1,200-row filtering, 650-record concurrent history migration /
append / clear / export, failed/empty-output ranking, paired TTS throughput,
backend-evidence rejection, and foreground-service handoff/cancellation. These
are host regression tests, **not 500 real model runs on a phone**. The owner
validates CPU/GPU inference, voice listening and sustained thermal behavior on
the S25/S22; no adb/device automation is used.

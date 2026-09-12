# Compare local models on your phone

Open **Compare local models** and select Speech or Translation. This runs real inference through the installed production engines. No provider requests, automatic model downloads, analytics, or uploaded recordings are involved. ML Kit uses only explicitly installed packs and is absent from the offline build.

For Speech, choose a 0.5–30 second PCM16 WAV (mono/stereo, 8–48 kHz), choose its spoken language, and select compatible installed models. The app downmixes/resamples once and feeds identical 16 kHz mono samples to every candidate. Use the same file when comparing different settings or versions. The parser rejects malformed/truncated data, duplicate audio/format chunks, unsupported float/compressed formats, oversized files and overlong recordings. It limits input to 6 MiB before allocating an unbounded file.

For Translation, paste 1–500 characters of corrected source text and select the actual source and destination. Installed GGUF translators and ML Kit packs advertise their supported pairs. This isolates translation quality from recognition mistakes. It does not yet measure a simultaneous ASR-plus-MT cascade. The legacy Marian translator is not included in this first comparison screen.

Each model runs sequentially with one newly created session, a first inference, and two warmed passes. Sessions are released before the next model loads. Native speech sessions reset their audio timeline between passes; they retain loaded weights. Whisper/Vosk use their public batch method, with 8/2 threads respectively. Nemotron, Qwen and Moonshine use the same production SpeechRuntime/SpeechOptions defaults as the caption adapter, with 20 ms frames and an end-of-input flush. Nemotron is cache-aware; Qwen/Moonshine decode utterance windows. GGUF translators use their existing native limits/defaults. ML Kit initializes its language-pair client lazily, so its first inference includes that setup.

Read the results carefully:

- **Load + verify** includes hashing package files and native creation. It is a fresh session, not a guaranteed cold disk/OS cache measurement.
- **First inference** is the first pass after loading.
- **Warm median** is the median of two further passes with weights retained.
- **Real-time factor** divides warm compute time by recording duration. Below 1 means inference keeps ahead in accelerated replay. Audio is fed without waiting for real time; these numbers are not spoken-phrase-to-caption latency, and batch versus streaming adapter paths are explicitly labeled.
- **First text in replay**, under details, starts when processing begins. Batch adapters only return text at completion.
- **Transcripts/all passes** let you check whether a faster model omitted words or returned nothing. No model is declared a quality winner from timing alone.
- **Errors** remain the original exception text. A failed run remains a failed result even if an earlier pass completed.

The report records the input hash, model identity, runtime information, source/destination, timings, all pass text, run ID and device/OS. Up to 40 results are retained privately. **Export results** writes a JSON file to the location you select; it includes transcripts. **Clear history** deletes the saved results. Audio is held only in memory for the current screen and is not copied into history.

**Stop comparison**, leaving the page, or backgrounding the activity cancels the run. Completed models remain saved. The current non-preemptible native operation may need to finish before disposal; the runtime gate remains owned until all native resources have closed. The page does not promise a hard wall-clock interrupt during native model initialization. Native crashes/OOM cannot be caught as managed exceptions. Use short representative recordings and compare a few models at a time; a passing short run is not evidence about long-session battery/thermal behavior.

## Integration

`LocalBenchmarkScreen(onModels = { ... })` is the navigation entry point. `LocalBenchmarkRunner` holds `LocalWorkGate.acquire("Benchmark")` across loading, inference and disposal. Overlay and Traveler must use the same gate, preventing simultaneous local engines from contaminating timings or exhausting memory. No manifest permissions or service are required: imported audio avoids microphone/capture permissions and inference is deliberately foreground-only.

`BenchmarkAudio` and `BenchmarkMetrics` are production utilities consumed by the runner/screen. `BenchmarkAudioTest` covers overflow-safe stereo mixing, identical normalized-input hashes, resampling/duration, format/length/alignment rejection, metadata chunks, duplicate data, byte caps, and valid/invalid timing arithmetic. Host tests cannot establish native speed or phone quality; run the screen on the phone for those results.

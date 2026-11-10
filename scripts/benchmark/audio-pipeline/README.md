# Hearth media primitives benchmark

This standalone runner compares Hearth's audio and media primitives with prototypes
inspired by the [SoundCraft capture ring](https://github.com/storytold/soundcraft/blob/ef58a90f4a418a282a15fc71d2e03c3d8e8f40fa/crates/playback/src/record.rs#L44)
and [SoundCraft's bandlimited resampler](https://github.com/storytold/soundcraft/blob/ef58a90f4a418a282a15fc71d2e03c3d8e8f40fa/crates/dsp/src/offline.rs#L155).
It builds independently of Gradle and writes binaries and JSON reports under
`build/benchmarks/audio-pipeline/`.

## Run on macOS or Linux

Use Python 3, a C++17 compiler (Clang by default), and Rust 1.99.0 via rustup.
Run these commands from the repository root.

```sh
python3 scripts/benchmark/audio-pipeline/bench.py run --target host
python3 scripts/benchmark/audio-pipeline/speech_quality.py \
  --resampler build/benchmarks/audio-pipeline/host/hearth-audio-bench \
  --output build/benchmarks/audio-pipeline/host/speech-quality.json
```

## Run on an ARM64 Android phone

Use Hearth's NDK 27.0.12077973, platform tools, and the Rust
`aarch64-linux-android` target for 1.99.0. Connect an adb-authorized phone.
If several physical phones are connected, add `--serial` to both commands.
The runner uses a temporary directory in `/data/local/tmp` and cleans it up.

```sh
rustup target add --toolchain 1.99.0 aarch64-linux-android
python3 scripts/benchmark/audio-pipeline/bench.py run --target android-arm64
python3 scripts/benchmark/audio-pipeline/speech_quality.py --android \
  --resampler build/benchmarks/audio-pipeline/android-arm64/hearth-audio-bench \
  --output build/benchmarks/audio-pipeline/android-arm64/speech-quality.json
```

Use `--suite meter`, `timeline`, `mix`, `waveform`, `pixels`, or `probe` to
run one of the editor-oriented suites. Use `--output` to retain each run
alongside earlier reports.

## Build compatibility for other Android consumers

The runner also builds standalone executables for Hearth's other Android ABIs.
Each cross-build checks the ELF machine, load segment alignment, and dependence
on the system runtime. The ARM64 phone command is the hardware run.

```sh
rustup target add --toolchain 1.99.0 armv7-linux-androideabi i686-linux-android x86_64-linux-android
for target in android-armv7 android-x86 android-x86_64; do
  python3 scripts/benchmark/audio-pipeline/bench.py build --target "$target" || exit
done
```

## Read the results

- **Transport** measures submission and producer-to-consumer handoff on
  100 ms frames under bounded cross-thread load. Both PCM16 and Float32 use
  the same audio and correctness checks. The report compares the preallocated
  desktop capture queue with the frozen pre-change Hearth path. The fixed C++
  ring is the control for the Rust ring. Compare p99 from repeated runs and
  consider handoff separately from submission. Caption latency includes capture cadence,
  engine segmentation, and inference, which are outside this measurement.
- **Resampling** compares the existing linear path and a streaming FIR with
  fixed history. Check exact output lengths, chunk invariance, passband,
  aliasing, processing time, and reported filter lookahead. The 16 kHz
  passthrough is a control for the rate Hearth already requests from capture.
- **Speech quality** compares recorded English and Arabic clips against their
  pinned 16 kHz references. It tests controlled 48 kHz reconstruction both
  with and without added out-of-band interference. The report measures
  waveform fidelity, timing, and tail preservation. Word error rate needs a
  separate recognition experiment.
- **Metering and mixdown** compare equivalent work and separately check audio
  policy. A faster stereo downmix must preserve its signed rounding. A
  surround mix needs explicit channel order and its own headroom checks.
- **Timeline and waveform** check timing placement and min/max or RMS summaries
  against independent reference calculations. Keep one-time index construction
  and memory separate from repeated viewport queries.
- **Pixels and media probing** measure a frame conversion and a bounded header
  scan independently from platform decoding. Keep the input layout, bytes read,
  and rejected input cases with the timing results.

Run on an idle machine and keep the same device, input, frame size, and
compiler settings for comparisons. The JSON stores per-run measurements,
platform details, and the benchmark executable hash.

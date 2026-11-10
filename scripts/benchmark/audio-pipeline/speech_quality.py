#!/usr/bin/env python3
"""Measure real-speech waveform preservation through Hearth's PCM resamplers.

The native runner accepts resample METHOD INPUT OUTPUT 48000. Input and output
are signed 16-bit little-endian mono, with output at 16 kHz. Pass an executable
wrapper as --resampler to run the same input on a connected phone.
"""

import argparse
from array import array
from contextlib import nullcontext
import hashlib
import json
import math
from pathlib import Path
import platform
import random
import statistics
import subprocess
import sys
import tempfile
import time
import wave


ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "app/src/main/assets/benchmark"
SOURCE_RATE = 48_000
TARGET_RATE = 16_000
FREQUENCIES = (10_300, 11_700, 13_500, 14_900)
RADIUS = 12
TAIL_SAMPLES = 320  # Last 20 ms, including the endpoint filter transient.
METHODS = ("baseline", "candidate")
CONDITIONS = ("synthetic_clean_48k", "synthetic_interference_48k")


def pcm_samples(pcm):
    values = array("h")
    values.frombytes(pcm)
    if sys.byteorder != "little":
        values.byteswap()
    return values


def pcm_bytes(values):
    samples = array("h", (round(value) for value in values))
    if sys.byteorder != "little":
        samples.byteswap()
    return samples.tobytes()


def interpolation_phase(phase):
    """Windowed-sinc interpolation weights for fractional source positions."""
    weights = []
    for tap in range(-RADIUS + 1, RADIUS + 1):
        x = phase / 3 - tap
        sinc = math.sin(math.pi * x) / (math.pi * x)
        weights.append(sinc * (0.5 + 0.5 * math.cos(math.pi * x / RADIUS)))
    total = sum(weights)
    return [weight / total for weight in weights]


INTERPOLATION_PHASES = tuple(interpolation_phase(phase) for phase in (1, 2))


def synthetic_pcm(pcm, case_id, snr_db):
    """Make paired 48 kHz clean and contaminated inputs from a 16 kHz clip."""
    source = pcm_samples(pcm)
    padded = [0] * (RADIUS - 1) + list(source) + [0] * RADIUS
    clean = array("d")
    for i, value in enumerate(source):
        block = padded[i:i + 2 * RADIUS]
        clean.append(value)
        for weights in INTERPOLATION_PHASES:
            clean.append(sum(sample * weight for sample, weight in zip(block, weights)))

    rng = random.Random(case_id)
    angles = [(math.tau * frequency / SOURCE_RATE, rng.uniform(0, math.tau))
              for frequency in FREQUENCIES]
    noise = array("d", (sum(math.sin(step * i + phase) for step, phase in angles)
                        for i in range(len(clean))))
    speech_rms = math.sqrt(sum(sample * sample for sample in clean) / len(clean))
    noise_rms = math.sqrt(sum(sample * sample for sample in noise) / len(noise))
    noise_gain = speech_rms / noise_rms * 10 ** (-snr_db / 20)
    mixed = array("d", (sample + noise_gain * interference for sample, interference in zip(clean, noise)))
    # The same attenuation for both conditions isolates interference from gain/clipping.
    gain = min(1.0, 32_760 / max(max(map(abs, clean)), max(map(abs, mixed)), 1))
    return (
        pcm_bytes(value * gain for value in clean),
        pcm_bytes(value * gain for value in mixed),
        gain,
    )


def load_samples(language, count):
    suite_bytes = (DATA / "benchmark-suite.json").read_bytes()
    suite = json.loads(suite_bytes)
    quick = set(suite["quickSentenceIds"])
    cases = [case for case in suite["cases"]
             if case["source"] == language and not case["target"]
             and case["publisherSentenceId"] in quick]
    if len(cases) != 6:
        raise ValueError(f"Expected six pinned {language} clips; found {len(cases)}")
    for case in cases[:count]:
        path = DATA / case["audio"]
        if hashlib.sha256(path.read_bytes()).hexdigest() != case["sha256"]:
            raise ValueError(f"WAV checksum mismatch: {case['id']}")
        with wave.open(str(path), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, TARGET_RATE):
                raise ValueError(f"Unexpected WAV format: {case['id']}")
            pcm = wav.readframes(wav.getnframes())
        if hashlib.sha256(pcm).hexdigest() != case["pcmSha256"]:
            raise ValueError(f"PCM checksum mismatch: {case['id']}")
        yield case, pcm, {
            "id": suite["id"], "revision": suite["revision"], "license": suite["license"],
            "manifestSha256": hashlib.sha256(suite_bytes).hexdigest(),
        }


def run_command(command, timeout, operation):
    completed = subprocess.run(
        command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=False,
    )
    if completed.returncode:
        detail = completed.stderr.decode("utf-8", errors="replace")[-2_000:].strip()
        raise RuntimeError(f"{operation} exited {completed.returncode}: {detail}")
    return completed.stdout.decode("utf-8", errors="replace").strip()


def resample(executable, method, input_path, output_path, original_bytes, android):
    started = time.perf_counter()
    if android is None:
        run_command([str(executable), "resample", method, str(input_path), str(output_path), str(SOURCE_RATE)],
                    timeout=30, operation=f"{method} resampler")
    else:
        adb, directory = android
        source = f"{directory}/input.s16le"
        output = f"{directory}/output.s16le"
        run_command([*adb, "push", str(input_path), source], timeout=60, operation="adb push")
        run_command([*adb, "shell", f"{directory}/hearth-audio-bench", "resample", method,
                     source, output, str(SOURCE_RATE)], timeout=30, operation=f"Android {method} resampler")
        run_command([*adb, "pull", output, str(output_path)], timeout=60, operation="adb pull")
    elapsed_ms = (time.perf_counter() - started) * 1000
    if output_path.stat().st_size != original_bytes:
        raise ValueError(f"{method} resampler changed the decoded length of {input_path.name}")
    return output_path.read_bytes(), elapsed_ms


def snr_db(signal, error):
    return 10 * math.log10(signal / error) if signal and error else None


def error_metrics(reference, output):
    differences = [int(actual) - int(expected) for expected, actual in zip(reference, output)]
    signal = sum(sample * sample for sample in reference)
    error = sum(difference * difference for difference in differences)
    tail = differences[-TAIL_SAMPLES:]
    tail_error = sum(difference * difference for difference in tail)
    return {
        "samples": len(output), "bitExact": error == 0, "signalEnergy": signal,
        "errorEnergy": error, "snrDb": snr_db(signal, error),
        "rmsErrorPcm16": math.sqrt(error / len(differences)),
        "peakErrorPcm16": max(map(abs, differences)),
        "tailSamples": len(tail), "tailErrorEnergy": tail_error,
        "tailRmsErrorPcm16": math.sqrt(tail_error / len(tail)),
        "saturatedOutputSamples": sum(sample in (-32_768, 32_767) for sample in output),
    }


def measure_clip(executable, case, pcm, snr, directory, index, android):
    clean, mixed, gain = synthetic_pcm(pcm, case["id"], snr)
    reference = pcm_samples(pcm_bytes(value * gain for value in pcm_samples(pcm)))
    rows = []
    for condition, input_pcm in zip(CONDITIONS, (clean, mixed)):
        source = directory / f"{case['id']}-{condition}.s16le"
        source.write_bytes(input_pcm)
        source_sha256 = hashlib.sha256(input_pcm).hexdigest()
        # Alternate which method runs first across clips.
        order = reversed(METHODS) if index % 2 else METHODS
        for method in order:
            path = directory / f"{case['id']}-{condition}-{method}.s16le"
            converted, cli_ms = resample(executable, method, source, path, len(pcm), android)
            rows.append({
                "id": case["id"], "condition": condition, "method": method,
                "inputGain": gain, "cliWallMs": cli_ms, "originalPcmSha256": case["pcmSha256"],
                "sourcePcmSha256": source_sha256,
                "outputPcmSha256": hashlib.sha256(converted).hexdigest(),
                **error_metrics(reference, pcm_samples(converted)),
            })
    return rows


def summarize(rows):
    summary = {}
    for condition in CONDITIONS:
        group = [row for row in rows if row["condition"] == condition]
        methods = {method: {row["id"]: row for row in group if row["method"] == method}
                   for method in METHODS}
        if methods["baseline"].keys() != methods["candidate"].keys():
            raise ValueError(f"Unpaired results for {condition}")
        summary[condition] = result = {}
        for method, cases in methods.items():
            samples = sum(row["samples"] for row in cases.values())
            signal = sum(row["signalEnergy"] for row in cases.values())
            error = sum(row["errorEnergy"] for row in cases.values())
            tail_samples = sum(row["tailSamples"] for row in cases.values())
            tail_error = sum(row["tailErrorEnergy"] for row in cases.values())
            result[method] = {
                "clips": len(cases), "samples": samples, "bitExactClips": sum(row["bitExact"] for row in cases.values()),
                "pooledSnrDb": snr_db(signal, error), "pooledRmsErrorPcm16": math.sqrt(error / samples),
                "pooledTailRmsErrorPcm16": math.sqrt(tail_error / tail_samples),
                "medianCliWallMs": statistics.median(row["cliWallMs"] for row in cases.values()),
            }
        differences = [methods["candidate"][clip]["errorEnergy"] - row["errorEnergy"]
                       for clip, row in methods["baseline"].items()]
        result["candidatePairs"] = {
            "improved": sum(value < 0 for value in differences),
            "tied": sum(value == 0 for value in differences),
            "regressed": sum(value > 0 for value in differences),
        }
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resampler", type=Path, required=True,
                        help="Local benchmark executable, or ARM64 executable to stage with --android")
    parser.add_argument("--android", action="store_true", help="Run the resampler on a physical Android ARM64 device")
    parser.add_argument("--serial", help="ADB device serial for --android")
    parser.add_argument("--runtime-label", help="Override the execution target label recorded in results")
    parser.add_argument("--languages", nargs="+", default=["en", "ar"], choices=("en", "ar"))
    parser.add_argument("--clips-per-language", type=int, default=6, choices=range(1, 7))
    parser.add_argument("--interference-snr-db", type=float, default=-6.0,
                        help="Speech-to-interference RMS ratio in dB (default -6)")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "build/benchmark-results/audio-pipeline/speech-quality.json")
    args = parser.parse_args()
    executable = args.resampler.resolve()
    if not executable.is_file():
        parser.error("Provide an existing benchmark executable or wrapper")
    if args.serial and not args.android:
        parser.error("--serial requires --android")
    if not -30 <= args.interference_snr_db <= 30:
        parser.error("Use an interference SNR from -30 to 30 dB")
    if len(set(args.languages)) != len(args.languages):
        parser.error("Choose each language once")
    result = {
        "schemaVersion": 1,
        "binarySha256": hashlib.sha256(executable.read_bytes()).hexdigest(),
        "audio": {
            "sourceRateHz": SOURCE_RATE, "targetRateHz": TARGET_RATE,
            "syntheticUpsampling": "24-tap windowed sinc interpolation with exact original sample positions",
            "interferenceFrequenciesHz": FREQUENCIES, "speechToInterferenceRmsDb": args.interference_snr_db,
            "reference": "Original pinned 16 kHz speech with the same attenuation applied to both test inputs",
            "comparison": "Same sample indices, exact expected length, all samples including final 20 ms",
            "snrNull": "The reference or error energy is zero; inspect bitExactClips",
            "timing": "CLI wall time includes startup, file I/O and wrapper overhead; use native benchmark for DSP speed",
        },
        "languages": {},
    }
    try:
        adb = None
        staged = nullcontext()
        label = f"{platform.system()} {platform.machine()}"
        if args.android:
            from bench import android_device, stage_android

            adb = android_device(args.serial)
            model = run_command([*adb, "shell", "getprop", "ro.product.model"], timeout=30, operation="Android model")
            label = f"Android ARM64 / {model}"
            staged = stage_android(executable, adb)
        result["runtime"] = args.runtime_label or label
        with staged as remote, tempfile.TemporaryDirectory(prefix="hearth-speech-quality-") as work:
            android = (adb, remote) if adb is not None else None
            for language in args.languages:
                rows = []
                for index, (case, pcm, dataset) in enumerate(load_samples(language, args.clips_per_language)):
                    result["dataset"] = dataset
                    rows.extend(measure_clip(executable, case, pcm, args.interference_snr_db, Path(work), index, android))
                result["languages"][language] = {"summary": summarize(rows), "samples": rows}
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Speech quality benchmark failed: {error}\n")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "summary": {
        language: report["summary"] for language, report in result["languages"].items()
    }}, indent=2))


if __name__ == "__main__":
    main()

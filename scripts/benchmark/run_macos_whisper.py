"""Run the same pinned multilingual Whisper artifact and FLEURS samples on macOS."""
import argparse
import datetime as dt
import hashlib
import json
import platform
import resource
import statistics
import struct
import subprocess
import sys
import time
import uuid
import wave
from pathlib import Path

from run_macos_translation import error_rate, sha256_file

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "app/src/main/assets/benchmark"
MANIFEST = DATA / "benchmark-suite.json"
RUNTIME = ROOT / "build/benchmark-runtime/whisper/hearth_whisper_host"


def mac_name():
    try:
        return subprocess.check_output(["sysctl", "-n", "machdep.cpu.brand_string"], text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return platform.machine()


def median(values):
    return statistics.median(values)


def load_samples(source, case_count):
    suite_bytes = MANIFEST.read_bytes()
    suite = json.loads(suite_bytes)
    quick = set(suite["quickSentenceIds"])
    cases = [case for case in suite["cases"] if case["source"] == source
             and not case.get("target") and case.get("publisherSentenceId") in quick]
    if len(cases) != case_count:
        raise ValueError(f"Pinned benchmark should contain six {source} speech clips; found {len(cases)}.")
    records = []
    for case in cases:
        audio_path = DATA / case["audio"]
        wav_bytes = audio_path.read_bytes()
        if hashlib.sha256(wav_bytes).hexdigest() != case["sha256"]:
            raise ValueError(f"WAV SHA-256 mismatch: {case['id']}")
        with wave.open(str(audio_path), "rb") as wav:
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
                raise ValueError(f"Unexpected benchmark WAV format: {case['id']}")
            pcm = wav.readframes(wav.getnframes())
        if hashlib.sha256(pcm).hexdigest() != case["pcmSha256"]:
            raise ValueError(f"Normalized PCM SHA-256 mismatch: {case['id']}")
        records.append({"id": case["id"], "reference": case["reference"], "pcm": pcm,
                        "audioMs": len(pcm) // 32, "silence": False})
    for ms in (1000, 2500):
        records.append({"id": f"silence-{ms}", "reference": None, "pcm": bytes(16000 * 2 * ms // 1000),
                        "audioMs": ms, "silence": True})
    return suite_bytes, suite, records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path, help="already-downloaded compatible whisper.cpp .bin; no weights are fetched")
    parser.add_argument("--source", required=True, choices=("ar", "en", "ru", "zh", "auto"))
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--passes", type=int, default=3)
    parser.add_argument("--runtime", type=Path, default=RUNTIME)
    parser.add_argument("--output", type=Path, default=ROOT / "build/benchmark-results/macos-whisper.json")
    args = parser.parse_args()
    if platform.system() != "Darwin": parser.error("This runner targets macOS; use the Android app for phone measurements.")
    if not 1 <= args.threads <= 16 or not 1 <= args.passes <= 5: parser.error("Threads or passes are outside supported bounds.")
    if not args.model.is_file() or not args.runtime.is_file():
        parser.error("Model or host runtime missing. Build with bash scripts/benchmark/build_macos_whisper.sh; model weights are not downloaded.")

    suite_bytes, suite, records = load_samples(args.source, 6)
    verify_start = time.perf_counter()
    model_hash = sha256_file(args.model)
    verify_ms = (time.perf_counter() - verify_start) * 1000
    payload = bytearray()
    for _ in range(args.passes):
        for sample in records:
            count = len(sample["pcm"]) // 2
            payload += struct.pack("<I", count)
            payload += sample["pcm"]
    command = [str(args.runtime), str(args.model), args.source, str(args.threads)]
    completed = subprocess.run(command, input=bytes(payload), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               timeout=max(900, args.passes * 8 * 90), check=False)
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr.decode("utf-8", errors="replace")[-12000:])
        raise SystemExit(f"Host Whisper runtime exited with status {completed.returncode}.")
    output = [json.loads(line) for line in completed.stdout.decode("utf-8").splitlines() if line.strip()]
    expected = len(records) * args.passes
    if len(output) != expected + 1 or output[0].get("event") != "ready":
        raise SystemExit(f"Unexpected host runner output: expected {expected} sample results, received {max(0, len(output)-1)}.")
    by_pass = [output[1 + i * len(records):1 + (i + 1) * len(records)] for i in range(args.passes)]
    final = by_pass[-1]
    result_samples = []
    for sample, answer in zip(records, final):
        result_samples.append({"id": sample["id"], "text": answer.get("text", ""),
            "reference": sample["reference"], "computeMs": answer.get("elapsedMs", 0.0),
            "firstTextMs": answer.get("elapsedMs", 0.0), "audioMs": sample["audioMs"],
            "silence": sample["silence"], "error": answer.get("error")})
    errors = [answer.get("error") for answers in by_pass for answer in answers if answer.get("error")]
    final_silence = [answer for sample, answer in zip(records, final) if sample["silence"]]
    silence_false_positives = sum(bool(answer.get("text", "").strip()) for answer in final_silence)
    outputs = [" ".join(answer.get("text", "") for sample, answer in zip(records, answers) if not sample["silence"])
               for answers in by_pass]
    references = " ".join(sample["reference"] for sample in records if sample["reference"])
    elapsed_by_pass = [sum(answer.get("elapsedMs", 0.0) for answer in answers) for answers in by_pass]
    first_by_pass = [statistics.mean(answer.get("elapsedMs", 0.0) for answer in answers)
                     for answers in by_pass]
    input_hash = hashlib.sha256(suite_bytes + b"".join(sample["pcm"] for sample in records)).hexdigest()
    result = {
        "runId": str(uuid.uuid4()), "timestamp": int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000),
        "model": args.model.name, "identity": model_hash, "route": "Speech / Whisper batch / CPU host",
        "inputHash": input_hash, "source": args.source, "target": "",
        "audioMs": sum(sample["audioMs"] for sample in records), "loadMs": output[0]["loadMs"] + verify_ms,
        "computeMs": elapsed_by_pass, "texts": outputs,
        "runtime": "whisper.cpp vendored source; CPU; GREEDY; 16 kHz mono; batch; gain normalization; noSpeech=0.8; threads=" + str(args.threads),
        "firstTextMs": first_by_pass,
        "device": f"{mac_name()}; macOS {platform.mac_ver()[0]}; Python host harness",
        "error": errors[0] if errors else None,
        "referenceText": references if not errors else None,
        "wordErrorRate": error_rate(references, outputs[-1]) if not errors else None,
        "characterErrorRate": error_rate(references, outputs[-1], characters=True) if not errors else None,
        "scoringNormalization": "nfc-lower-punctuation-space-v1",
        "translationChrf": None, "samples": result_samples,
        "silenceFalsePositives": silence_false_positives, "silenceChecks": len(final_silence),
        "benchmarkSuite": {"id": suite["id"], "revision": suite["revision"], "license": suite["license"], "quickSamples": 6},
        "hostPeakRssBytes": resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps([result], ensure_ascii=False, indent=2) + "\n")
    warm = elapsed_by_pass[1:]
    print(json.dumps({"output": str(args.output), "model": args.model.name, "loadMs": result["loadMs"],
        "warmMedianMs": median(warm) if warm else None, "WER": result["wordErrorRate"], "CER": result["characterErrorRate"],
        "silenceFalsePositives": f"{silence_false_positives}/{len(final_silence)}",
        "error": result["error"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

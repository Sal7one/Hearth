#!/usr/bin/env python3
"""Run the pinned Hearth translation adapter on the same six FLEURS text pairs as Android."""
import argparse
import collections
import datetime as dt
import hashlib
import json
import os
import platform
import re
import resource
import statistics
import subprocess
import sys
import time
import unicodedata
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "app/src/main/assets/benchmark/benchmark-suite.json"
RUNTIME = ROOT / "build/translation-runtime/host/translation_smoke"
LLAMA = ROOT / "build/translation-runtime/llama.cpp"
NAMES = {"ar": "Arabic", "en": "English", "ru": "Russian", "zh": "Chinese"}
PUNCTUATION = set("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def make_prompt(text, family, source, target):
    to = NAMES.get(target, target)
    if family == "hy-mt2":
        return f"Translate the following text into {to}. Note that you should only output the translated result without any additional explanation:\n\n{text}"
    if family == "translategemma":
        source_name = NAMES.get(source, source)
        return (f"You are a professional {source_name} ({source}) to {to} ({target}) translator. "
                f"Your goal is to accurately convey the meaning and nuances of the original {source_name} text "
                f"while adhering to {to} grammar, vocabulary, and cultural sensitivities.\n"
                f"Produce only the {to} translation, without any additional explanations or commentary. "
                f"Please translate the following {source_name} text into {to}:\n\n\n{text.strip()}")
    if source == "zh" or target == "zh":
        return f"将以下文本翻译为{to}，注意只需要输出翻译后的结果，不要额外解释：\n\n{text}"
    return f"Translate the following segment into {to}, without additional explanation.\n\n{text}"


def normalize(text):
    value = unicodedata.normalize("NFC", text).lower()
    value = "".join(" " if unicodedata.category(ch)[0] in "PS" else ch for ch in value)
    return " ".join(value.split())


def error_rate(reference, hypothesis, characters=False):
    ref = normalize(reference)
    hyp = normalize(hypothesis)
    a = list(ref.replace(" ", "")) if characters else (ref.split() if ref else [])
    b = list(hyp.replace(" ", "")) if characters else (hyp.split() if hyp else [])
    previous = list(range(len(b) + 1))
    for i, expected in enumerate(a, 1):
        current = [i]
        for j, actual in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (expected != actual)))
        previous = current
    if not a:
        return 0.0 if not b else None
    return previous[-1] / len(a)


def chrf(reference, hypothesis):
    def word_tokens(text):
        parts = text.strip().split()
        result = []
        for word in parts:
            if len(word) == 1:
                result.append(word)
            elif word[0] in PUNCTUATION:
                result.extend((word[0], word[1:]))
            elif word[-1] in PUNCTUATION:
                result.extend((word[:-1], word[-1]))
            else:
                result.append(word)
        return result

    ref_chars = [c for c in reference if not c.isspace()]
    hyp_chars = [c for c in hypothesis if not c.isspace()]
    token_pairs = [(ref_chars, hyp_chars)]
    token_pairs.extend((word_tokens(reference), word_tokens(hypothesis)) for _ in range(2))
    precision = recall = 0.0
    effective = 0
    for order in range(1, 9):
        n = order if order <= 6 else order - 6
        ref_tokens, hyp_tokens = token_pairs[0] if order <= 6 else token_pairs[order - 6]
        ref_grams = collections.Counter(tuple(ref_tokens[i:i + n]) for i in range(max(0, len(ref_tokens) - n + 1)))
        hyp_grams = collections.Counter(tuple(hyp_tokens[i:i + n]) for i in range(max(0, len(hyp_tokens) - n + 1)))
        ref_count = sum(ref_grams.values())
        hyp_count = sum(hyp_grams.values())
        if ref_count and hyp_count:
            matched = sum(min(count, ref_grams[gram]) for gram, count in hyp_grams.items())
            precision += matched / hyp_count
            recall += matched / ref_count
            effective += 1
    if not effective:
        return 100.0 if not reference and not hypothesis else 0.0
    precision /= effective
    recall /= effective
    return 0.0 if precision + recall == 0 else 500 * precision * recall / (4 * precision + recall)


def median(values):
    return statistics.median(values)


def machine_name():
    try:
        return subprocess.check_output(["sysctl", "-n", "machdep.cpu.brand_string"], text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return platform.machine()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path, help="already-downloaded GGUF file; weights are never fetched")
    parser.add_argument("--family", choices=("hy-mt1.5", "hy-mt2", "translategemma"), required=True)
    parser.add_argument("--source", choices=tuple(NAMES), required=True)
    parser.add_argument("--target", choices=tuple(NAMES), required=True)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--gpu-layers", type=int, default=None, help="Defaults to Metal when the host runtime was built with it; otherwise CPU. Use 0 to force CPU.")
    parser.add_argument("--passes", type=int, default=3)
    parser.add_argument("--output", type=Path, default=ROOT / "build/benchmark-results/macos-translation.json")
    parser.add_argument("--runtime", type=Path, default=RUNTIME)
    args = parser.parse_args()
    if platform.system() != "Darwin":
        parser.error("This runner is for macOS; use the Android app for phone measurements.")
    if args.source == args.target:
        parser.error("Source and destination must differ.")
    if (not 1 <= args.threads <= 32 or not 1 <= args.passes <= 5
            or (args.gpu_layers is not None and not 0 <= args.gpu_layers <= 999)):
        parser.error("Threads, passes or GPU layers are outside safe benchmark limits.")
    if not args.model.is_file() or not args.runtime.is_file():
        parser.error("Model or runtime missing. Build with scripts/translation/build-runtime.sh host; model weights are not downloaded here.")
    cache_file = ROOT / "build/translation-runtime/host/CMakeCache.txt"
    metal_built = cache_file.is_file() and "GGML_METAL:BOOL=ON" in cache_file.read_text(errors="replace")
    gpu_layers = 99 if args.gpu_layers is None and metal_built else (args.gpu_layers or 0)
    if gpu_layers and not metal_built:
        parser.error("This host runtime has no Metal backend. Install the full Xcode toolchain and rebuild, or pass --gpu-layers 0.")

    manifest_bytes = MANIFEST.read_bytes()
    suite = json.loads(manifest_bytes)
    quick = set(suite["quickSentenceIds"])
    cases = [case for case in suite["cases"] if case.get("target") == args.target
             and case["source"] == args.source and case.get("publisherSentenceId") in quick]
    if len(cases) != 6:
        parser.error(f"Pinned benchmark pack should provide six {args.source} → {args.target} cases; found {len(cases)}.")
    prompts = [make_prompt(case["text"], args.family, args.source, args.target) for case in cases]
    ordered_prompts = [(pass_number, sample_index, prompts[sample_index])
                       for pass_number in range(args.passes) for sample_index in range(len(cases))]
    input_bytes = b"".join(prompt.encode("utf-8") + b"\0" for _, _, prompt in ordered_prompts)
    command = [str(args.runtime), str(args.model), "--stream", str(args.threads), str(gpu_layers),
               "NUL-DELIMITED-STDIN", "STOP-ON-ERROR"]
    hash_start = time.perf_counter()
    model_hash = sha256_file(args.model)
    verify_ms = (time.perf_counter() - hash_start) * 1000
    completed = subprocess.run(command, input=input_bytes, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               timeout=max(900, args.passes * 6 * 90), check=False)
    if completed.returncode != 0:
        sys.stderr.write(completed.stderr.decode("utf-8", errors="replace")[-12000:])
        raise SystemExit(f"Host runtime exited with status {completed.returncode}.")
    records = [json.loads(line) for line in completed.stdout.decode("utf-8").splitlines() if line.strip()]
    if not records or records[0].get("event") != "ready":
        raise SystemExit(f"Unexpected host runner output: expected {len(ordered_prompts)} results, received {max(0, len(records)-1)}.")
    if len(records) != len(ordered_prompts) + 1 and not any(record.get("error") for record in records[1:]):
        raise SystemExit(f"Unexpected host runner output: expected {len(ordered_prompts)} results, received {len(records)-1}.")
    pass_samples = [[] for _ in range(args.passes)]
    for (pass_number, sample_index, _), record in zip(ordered_prompts, records[1:]):
        case = cases[sample_index]
        pass_samples[pass_number].append({
            "id": case["id"], "text": record.get("text", ""), "reference": case["reference"],
            "computeMs": record.get("elapsedMs", 0.0), "firstTextMs": record.get("elapsedMs", 0.0),
            "audioMs": 0, "silence": False, "sourceText": case["text"], "error": record.get("error"),
        })
    error = next((sample["error"] for samples in pass_samples for sample in samples if sample["error"]), None)
    last_attempted_pass = max((pass_number for pass_number, _, _ in ordered_prompts[:len(records) - 1]), default=0)
    measured_passes = pass_samples[:last_attempted_pass + 1]
    last = measured_passes[-1]
    outputs = [" ".join(sample["text"] for sample in samples) for samples in measured_passes]
    references = " ".join(case["reference"] for case in cases)
    hypothesis = outputs[-1]
    sample_chrf = [chrf(sample["reference"], sample["text"]) for sample in last if not sample["error"]]
    llama_revision = subprocess.check_output(["git", "-C", str(LLAMA), "rev-parse", "HEAD"], text=True).strip()
    result_samples = last
    result = {
        "runId": str(uuid.uuid4()), "timestamp": int(dt.datetime.now(dt.timezone.utc).timestamp() * 1000),
        "model": args.model.name, "identity": model_hash, "route": "Translation / llama.cpp host",
        "inputHash": hashlib.sha256(manifest_bytes + input_bytes).hexdigest(), "source": args.source, "target": args.target,
        "audioMs": 0, "loadMs": records[0]["loadMs"] + verify_ms,
        "computeMs": [sum(sample["computeMs"] for sample in samples) for samples in measured_passes],
        "texts": outputs, "runtime": f"llama.cpp {llama_revision}; {'Metal' if gpu_layers else 'CPU'}; threads={args.threads}; gpuLayers={gpu_layers}; context=2048; outputCap=384; deadline=20s",
        "firstTextMs": [statistics.mean(sample["firstTextMs"] for sample in samples) if samples else 0.0 for samples in measured_passes],
        "device": f"{machine_name()}; macOS {platform.mac_ver()[0]}; Python host harness",
        "error": error, "referenceText": references,
        "wordErrorRate": error_rate(references, hypothesis) if error is None else None,
        "characterErrorRate": error_rate(references, hypothesis, characters=True) if error is None else None,
        "scoringNormalization": "nfc-lower-punctuation-space-v1; sentence-macro chrF++ beta=2, char-n=1..6, word-n=1..2",
        "translationChrf": statistics.mean(sample_chrf) / 100 if sample_chrf and error is None else None,
        "samples": result_samples,
        "benchmarkSuite": {"id": suite["id"], "revision": suite["revision"], "license": suite["license"], "quickSamples": len(cases)},
        "hostPeakRssBytes": resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps([result], ensure_ascii=False, indent=2) + "\n")
    warm_times = result["computeMs"][1:]
    print(json.dumps({"output": str(args.output), "model": args.model.name, "loadMs": result["loadMs"],
                      "warmMedianMs": median(warm_times) if warm_times else None, "chrF++": result["translationChrf"],
                      "WER": result["wordErrorRate"], "CER": result["characterErrorRate"], "error": error}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

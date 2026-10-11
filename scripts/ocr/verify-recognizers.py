#!/usr/bin/env python3
"""Check real publisher ONNX recognizers against Hearth's native CTC image contract.

Requires numpy + onnxruntime (CPU). Weights stay in the supplied local directory.
Optional downloads use immutable publisher revisions and bounded exact byte/digest
checks. Synthetic shape/probability checks do not establish OCR accuracy or phone speed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--download", action="store_true")
    parser.add_argument("--ids", default="english,korean,greek,thai,devanagari,tamil,telugu")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    import numpy as np
    import onnxruntime as ort
    root = Path(__file__).resolve().parents[2] / "common-jni/src/main/assets/ocr"
    entries = {entry["id"]: entry for entry in json.loads((root / "provenance.json").read_text())}
    args.directory.mkdir(parents=True, exist_ok=True)
    report = {"onnxruntime": ort.__version__, "provider": "CPUExecutionProvider", "recognizers": []}
    for model_id in args.ids.split(","):
        entry = entries[model_id]
        expected_size = entry["model_bytes"]
        expected_hash = entry["model_sha256"]
        destination = args.directory / f"{model_id}.onnx"
        if not destination.exists():
            if not args.download:
                raise RuntimeError(f"Missing {destination}; use --download or provide the verified model")
            url = f"https://huggingface.co/{entry['source']}/resolve/{entry['revision']}/inference.onnx"
            partial = destination.with_suffix(".part")
            try:
                with urllib.request.urlopen(url, timeout=60) as incoming, partial.open("wb") as outgoing:
                    total = 0
                    while chunk := incoming.read(65536):
                        total += len(chunk)
                        if total > expected_size:
                            raise RuntimeError(f"Publisher file exceeds pin: {model_id}")
                        outgoing.write(chunk)
                if partial.stat().st_size != expected_size or hashlib.sha256(partial.read_bytes()).hexdigest() != expected_hash:
                    raise RuntimeError(f"Publisher file size/digest mismatch: {model_id}")
                partial.replace(destination)
            finally:
                partial.unlink(missing_ok=True)
        if destination.stat().st_size != expected_size or hashlib.sha256(destination.read_bytes()).hexdigest() != expected_hash:
            raise RuntimeError(f"Local model size/digest mismatch: {model_id}")
        dictionary = root / f"{model_id}.json"
        if hashlib.sha256(dictionary.read_bytes()).hexdigest() != entry["dictionary_sha256"]:
            raise RuntimeError(f"Dictionary integrity mismatch: {model_id}")
        classes = len(json.loads(dictionary.read_text()))
        if classes != entry["classes"]:
            raise RuntimeError(f"Dictionary class count mismatch: {model_id}")
        options = ort.SessionOptions()
        options.log_severity_level = 3
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.add_session_config_entry("session.intra_op.allow_spinning", "0")
        session = ort.InferenceSession(str(destination), sess_options=options, providers=["CPUExecutionProvider"])
        if len(session.get_inputs()) != 1 or len(session.get_outputs()) != 1:
            raise RuntimeError(f"Recognizer must have exactly one input/output: {model_id}")
        image = session.get_inputs()[0]
        if image.type != "tensor(float)" or len(image.shape) != 4:
            raise RuntimeError(f"Recognizer input is not float32 NCHW: {model_id}")
        shapes = []
        normalization_error = 0.0
        rng = np.random.default_rng(5070)
        for width in (320, 640, 960):
            # Native Paddle preprocessing feeds BGR channels in [-1,1], height48.
            pixels = rng.uniform(-1, 1, size=(1, 3, 48, width)).astype(np.float32)
            scores = session.run(None, {image.name: pixels})[0]
            if scores.dtype != np.float32 or scores.ndim != 3 or scores.shape[0] != 1 or scores.shape[2] != classes:
                raise RuntimeError(f"Unsupported CTC output: {model_id}: {scores.shape}/{scores.dtype}, classes={classes}")
            if not np.isfinite(scores).all() or scores.min() < 0 or scores.max() > 1.00001:
                raise RuntimeError(f"Output is not finite probability data: {model_id}")
            # Large CTC alphabets (Korean11947) show ~1.2e-4 float32 softmax sum error.
            # This bounded tolerance still rejects logits and malformed/unscaled scores.
            if not np.allclose(scores.sum(axis=-1), 1, atol=5e-4):
                raise RuntimeError(f"CTC output is not normalized: {model_id}")
            normalization_error = max(normalization_error, float(np.max(np.abs(scores.sum(axis=-1) - 1))))
            shapes.append(list(scores.shape))
        result = {"id": model_id, "revision": entry["revision"], "bytes": expected_size,
                  "sha256": expected_hash, "classes": classes, "input": image.shape, "output_shapes": shapes, "max_probability_sum_error": normalization_error}
        report["recognizers"].append(result)
        print(f"{model_id}: verified pin + NCHW BGR48 + CTC probabilities; {shapes}")
    if args.report:
        args.report.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()

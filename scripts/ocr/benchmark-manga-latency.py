#!/usr/bin/env python3
"""Compare pinned PP-OCRv6 Manga FP32 CPU paths on caller-supplied pages.

Install onnx==1.23.2, onnxruntime==1.20.1, numpy, opencv-python and pyclipper.
Use model files from Kellenok/PP-OCRv6_manga at d65838996a00ef6b1c73ca3a700d7a8c5fbbef8a.
Output contains timings and parity counts. It excludes source images and transcripts.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import random
from time import perf_counter

import cv2
import numpy as np
import onnx
import onnxruntime as ort
import pyclipper


PINS = {
    "det/manga_det.onnx": "97c434ae8deaad83513fb132b605d5085a3af8ffac2cb15e2aa096815c206172",
    "rec/manga_rec.onnx": "5288bd221a9a047c6b7d1ff56bb98de8c875fb613df98ebe8754946448bda540",
    "ppocrv6_dict.txt": "4f0f8971ecb95e3a4d278985d6eaac154413e81fda0b4c941db17389580e9e9b",
}
MEAN = np.array([0.485, 0.456, 0.406], np.float32)
STD = np.array([0.229, 0.224, 0.225], np.float32)


def verify_model_dir(directory):
    for name, expected in PINS.items():
        with (directory / name).open("rb") as artifact:
            actual = hashlib.file_digest(artifact, "sha256").hexdigest()
        if actual != expected:
            raise ValueError(f"{name} SHA-256 mismatch. Expected {expected}, got {actual}.")


def session(model, threads):
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.add_session_config_entry("session.intra_op.allow_spinning", "0")
    return ort.InferenceSession(model, sess_options=options, providers=["CPUExecutionProvider"])


def argmax_export(path):
    model = onnx.load(path)
    softmax = model.graph.node.pop()
    if softmax.op_type != "Softmax" or softmax.output[0] != model.graph.output[0].name:
        raise ValueError("Unexpected recognizer output graph")
    model.graph.node.append(onnx.helper.make_node(
        "ArgMax", [softmax.input[0]], ["greedy_ids"], axis=2, keepdims=0,
    ))
    del model.graph.output[:]
    model.graph.output.append(onnx.helper.make_tensor_value_info(
        "greedy_ids", onnx.TensorProto.INT64, ["batch", "steps"],
    ))
    onnx.checker.check_model(model)
    return model.SerializeToString()


def infer(runtime, image):
    tensor = np.ascontiguousarray(image.transpose(2, 0, 1)[None])
    return runtime.run(None, {runtime.get_inputs()[0].name: tensor})[0]


def detect_boxes(runtime, image):
    height, width = image.shape[:2]
    if max(height, width) > 2 * min(height, width):
        scale = min(1.0, (0.75 * 960 * 960 / (height * width)) ** 0.5)
    else:
        scale = 960 / max(height, width)
    resized_height, resized_width = (max(32, round(size * scale / 32) * 32) for size in (height, width))
    resized = cv2.resize(image, (resized_width, resized_height)).astype(np.float32) / 255
    scores = infer(runtime, (resized - MEAN) / STD)[0, 0]
    # Match the thresholds and contour expansion in the publisher's ONNX example.
    contours, _ = cv2.findContours((scores > 0.15).astype(np.uint8), cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    for contour in contours:
        points = contour[:, 0]
        if len(points) < 4:
            continue
        x, y, box_width, box_height = cv2.boundingRect(points)
        mask = np.zeros((box_height, box_width), np.uint8)
        cv2.fillPoly(mask, [points - [x, y]], 1)
        if cv2.mean(scores[y:y + box_height, x:x + box_width], mask)[0] < 0.25:
            continue
        area = cv2.contourArea(points)
        perimeter = cv2.arcLength(points, True)
        if area <= 0 or perimeter <= 0:
            continue
        offset = pyclipper.PyclipperOffset()
        offset.AddPath(points.tolist(), pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
        expanded = offset.Execute(area * 1.4 / perimeter)
        if len(expanded) != 1:
            continue
        polygon = np.asarray(expanded[0], np.float32)
        if min(cv2.minAreaRect(polygon)[1]) < 3:
            continue
        ratio = [width / scores.shape[1], height / scores.shape[0]]
        pixels = np.rint(polygon * ratio).astype(int)
        left, top = np.maximum(pixels.min(0), 0)
        right, bottom = np.minimum(pixels.max(0), [width, height])
        if right > left and bottom > top:
            yield int(left), int(top), int(right), int(bottom)


def recognition_image(image, box):
    left, top, right, bottom = box
    crop = image[top:bottom, left:right]
    if crop.shape[0] > crop.shape[1]:
        crop = cv2.rotate(crop, cv2.ROTATE_90_COUNTERCLOCKWISE)
    height, width = crop.shape[:2]
    target_width = min(1600, max(16, round(48 * width / height)))
    return cv2.resize(crop, (target_width, 48)).astype(np.float32) / 127.5 - 1


def read_crop(runtime, image, vocabulary):
    output = infer(runtime, image)[0]
    ids = output.argmax(-1) if output.ndim == 2 else output
    return "".join(vocabulary[token] for step, token in enumerate(ids)
                   if token and (step == 0 or token != ids[step - 1]))


@dataclass
class Profile:
    label: str
    detector: ort.InferenceSession
    reader: ort.InferenceSession
    pool: ThreadPoolExecutor | None = None

    def page(self, image, vocabulary):
        start = perf_counter()
        boxes = list(detect_boxes(self.detector, image))
        crops = [recognition_image(image, box) for box in boxes]
        prepared = perf_counter()

        def recognize(index):
            return index, read_crop(self.reader, crops[index], vocabulary)

        if self.pool is None:
            transcripts = [recognize(index)[1] for index in range(len(crops))]
        else:
            order = sorted(range(len(crops)), key=lambda index: crops[index].shape[1], reverse=True)
            transcripts = [""] * len(crops)
            for index, text in self.pool.map(recognize, order):
                transcripts[index] = text

        end = perf_counter()
        return (prepared - start) * 1000, (end - start) * 1000, list(zip(boxes, transcripts))


def benchmark(image, profiles, vocabulary, passes):
    reference = profiles[0].page(image, vocabulary)[2]
    samples = {profile.label: [] for profile in profiles}
    order = profiles.copy()
    rng = random.Random(2301)
    for repeat in range(passes + 3):
        rng.shuffle(order)
        for profile in order:
            prepare_ms, total_ms, actual = profile.page(image, vocabulary)
            if repeat >= 3:
                samples[profile.label].append((prepare_ms, total_ms, actual))

    for profile in profiles:
        measurements = samples[profile.label]
        elapsed = [total for _, total, _ in measurements]
        prepared = [duration for duration, _, _ in measurements]
        yield dict(
            profile=profile.label, passes=passes, reference_rows=len(reference),
            exact_pages=sum(rows == reference for _, _, rows in measurements),
            matched_rows_min=min(sum(a == b for a, b in zip(rows, reference)) for _, _, rows in measurements),
            p50_ms=round(float(np.median(elapsed)), 2), p95_ms=round(float(np.percentile(elapsed, 95)), 2),
            max_ms=round(max(elapsed), 2), prepare_p50_ms=round(float(np.median(prepared)), 2),
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", required=True, type=Path)
    parser.add_argument("--image", required=True, action="append", type=Path, help="Repeat for multiple local pages")
    parser.add_argument("--passes", default=20, type=int)
    parser.add_argument("--workers", default=6, type=int)
    parser.add_argument("--argmax", action="store_true", help="Also test a local pre-Softmax ArgMax graph")
    args = parser.parse_args()
    if not 1 <= args.passes <= 100 or not 1 <= args.workers <= 12:
        parser.error("passes must be 1–100 and workers 1–12")

    verify_model_dir(args.model_dir)
    vocabulary = [""] + (args.model_dir / "ppocrv6_dict.txt").read_text(encoding="utf-8").splitlines() + [" "]
    detector_path = args.model_dir / "det/manga_det.onnx"
    recognizer_path = args.model_dir / "rec/manga_rec.onnx"
    cv2.setNumThreads(1)
    with ThreadPoolExecutor(args.workers) as pool:
        detector = session(detector_path, args.workers)
        profiles = [
            Profile("stock-serial", session(detector_path, 2), session(recognizer_path, 2)),
            Profile("stock-parallel", detector, session(recognizer_path, 1), pool),
        ]
        if args.argmax:
            profiles.append(Profile("argmax-parallel", detector, session(argmax_export(recognizer_path), 1), pool))
        for index, path in enumerate(args.image, start=1):
            image = cv2.imread(str(path))
            if image is None or max(image.shape[:2]) > 2048:
                parser.error(f"image {index} must be readable and at most 2048 pixels on each side")
            for result in benchmark(image, profiles, vocabulary, args.passes):
                print(json.dumps({"image_index": index, "workers": args.workers, **result}), flush=True)


if __name__ == "__main__":
    main()

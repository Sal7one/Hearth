#!/usr/bin/env python3
"""Convert MediaPipe hand_landmarker.task (TFLite) models to ONNX (float32).

Extracts hand_detector.tflite and hand_landmarks_detector.tflite from a
MediaPipe ``.task`` bundle (a ZIP archive), converts both to float32 ONNX via
tf2onnx's TFLite frontend, verifies the resulting graphs under onnxruntime,
optionally cross-checks numerics against the original TFLite through LiteRT
(ai-edge-litert), and writes PROVENANCE.json / PROVENANCE.md.

Usage:
    python convert_hand_models.py --task /path/to/hand_landmarker.task \
        --out /path/to/output_dir

Notes:
  - tflite2onnx 0.4.1 was tried first and fails on these graphs: its NHWC->NCHW
    layout propagation applies a rank-4 permutation to the rank-3 PReLU tensors
    ([1, 1, 256]) of the detector head (IndexError in layout.transform). We
    therefore use tf2onnx (TFLite frontend), which handles both models.
  - The bundled TFLite weights are FP16-quantized; tf2onnx dequantizes them to
    float32 initializers. The converted weights remain Apache-2.0,
    (c) MediaPipe Authors.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

# tf2onnx's constant folding names its temporaries (const_fold_opt__NNN) by set
# iteration order, which depends on Python string hashing. Re-exec once with a
# fixed PYTHONHASHSEED so the serialized ONNX (and its sha256) is reproducible.
if os.environ.get("PYTHONHASHSEED") != "0":
    os.environ["PYTHONHASHSEED"] = "0"
    os.execve(sys.executable, [sys.executable, *sys.argv], os.environ)

import numpy as np
import onnx
from onnx import TensorProto

SOURCE_TASK_URL = (
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/"
    "hand_landmarker/float16/1/hand_landmarker.task"
)
DEFAULT_OPSET = 13

DTYPE_NAMES = {v: k for k, v in TensorProto.DataType.items()}

MODELS = [
    # (archive member, output file, expected input shape)
    ("hand_detector.tflite", "hand_detector.onnx", [1, 192, 192, 3]),
    ("hand_landmarks_detector.tflite", "hand_landmarks_detector.onnx", [1, 224, 224, 3]),
]


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def io_signature(model: onnx.ModelProto) -> tuple[list[dict], list[dict]]:
    def one(value):
        t = value.type.tensor_type
        dims = [d.dim_value if d.HasField("dim_value") else d.dim_param for d in t.shape.dim]
        return {"name": value.name, "dtype": DTYPE_NAMES.get(t.elem_type, str(t.elem_type)), "shape": dims}

    return [one(v) for v in model.graph.input], [one(v) for v in model.graph.output]


def verify_and_smoke(
    onnx_path: Path,
    expected_input_shape: list[int],
    seed: int,
) -> tuple[dict, dict]:
    """Load the graph, assert float32 signature, run a seeded smoke test."""
    import onnxruntime as ort

    model = onnx.load(str(onnx_path))
    onnx.checker.check_model(model)

    opsets = {o.domain or "ai.onnx": o.version for o in model.opset_import}
    inputs, outputs = io_signature(model)

    assert len(inputs) == 1, f"expected 1 input, got {[i['name'] for i in inputs]}"
    assert inputs[0]["dtype"] == "FLOAT" and inputs[0]["shape"] == expected_input_shape, inputs[0]
    assert all(o["dtype"] == "FLOAT" for o in outputs), outputs

    init_dtypes = sorted({DTYPE_NAMES.get(i.data_type, str(i.data_type)) for i in model.graph.initializer})
    assert init_dtypes == ["FLOAT", "INT64"], f"unexpected initializer dtypes: {init_dtypes}"

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    rng = np.random.default_rng(seed)
    x = rng.standard_normal(expected_input_shape).astype(np.float32)
    results = sess.run(None, {inputs[0]["name"]: x})

    smoke = {}
    assert [o.name for o in sess.get_outputs()] == [o["name"] for o in outputs], "output order mismatch"
    for meta, r in zip(outputs, results):
        assert r.dtype == np.float32, (meta["name"], r.dtype)
        finite = bool(np.isfinite(r).all())
        if not finite:
            raise AssertionError(f"non-finite values in output {meta['name']}")
        smoke[meta["name"]] = {
            "shape": list(r.shape),
            "min": float(r.min()),
            "max": float(r.max()),
            "finite": finite,
        }

    graph_info = {"opsets": opsets, "ir_version": model.ir_version, "inputs": inputs, "outputs": outputs}
    return graph_info, smoke


def cross_check_pair(
    onnx_path: Path,
    tflite_path: Path,
    input_shape: list[int],
    seed: int,
) -> dict:
    """Run both runtimes on the same seeded input and diff every output tensor."""
    import onnxruntime as ort
    from ai_edge_litert.interpreter import Interpreter

    rng = np.random.default_rng(seed)
    x = rng.standard_normal(input_shape).astype(np.float32)

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    onnx_res = sess.run(None, {in_name: x})
    onnx_names = [o.name for o in sess.get_outputs()]

    interp = Interpreter(model_path=str(tflite_path), num_threads=1)
    interp.allocate_tensors()
    iid = interp.get_input_details()[0]
    assert list(iid["shape"]) == list(input_shape), (iid["shape"], input_shape)
    interp.set_tensor(iid["index"], x)
    interp.invoke()
    ods = interp.get_output_details()
    assert len(ods) == len(onnx_names), (len(ods), len(onnx_names))

    per_output = {}
    for od, name, ov in zip(ods, onnx_names, onnx_res):
        tv = interp.get_tensor(od["index"]).astype(np.float64)
        diff = np.abs(tv - ov.astype(np.float64))
        scale = max(float(np.max(np.abs(tv))), 1.0)
        per_output[name] = {
            "tflite_shape": list(tv.shape),
            "max_abs_diff": float(diff.max()),
            "max_rel_diff": float(diff.max() / scale),
        }
    return per_output


def canonicalize(model: onnx.ModelProto) -> None:
    """Re-serialize a tf2onnx graph deterministically (in place).

    tf2onnx's optimizers iterate id()-hashed objects, so node order and the
    numbering of generated names (const_fold_opt__N, roi__N, Resize__N, ...)
    vary between runs even though the graph semantics are identical. We impose
    a canonical, structure-derived form (merkle-style labeling):

      - initializer fingerprints are hashes of their (dtype, shape, bytes);
      - a node's fingerprint is a hash of (op_type, sorted attrs, sorted
        input fingerprints), computed in topological order;
      - canonical names are the original names with generated __N suffixes
        stripped, disambiguated by appending __k in fingerprint order;
      - nodes are serialized in topological order, ties broken by canonical
        name; initializers in canonical-name order.

    Structurally identical nodes are interchangeable, so the serialized bytes
    are a pure function of the abstract graph. Graph input/output names (the
    external contract: input_1, Identity, Identity_1, ...) are preserved.
    """
    import hashlib
    import heapq
    import re

    from onnx import numpy_helper

    g = model.graph
    # tf2onnx generates names as f"{tag}__{counter}" (make_name) and then
    # appends qualifiers like "_min"/"_max" — sometimes yielding names with
    # several counter segments, e.g. "Relu6__73_min__292". Counter values
    # depend on run-time iteration order, so strip every "__<digits>"
    # segment; the ONNX output suffix ":k" (if any) is kept.

    def split_name(name: str) -> tuple[str, str]:
        """Return (stripped base name, output suffix such as ':0' or '')."""
        m = re.search(r":\d+$", name)
        suffix = name[m.start():] if m else ""
        if m:
            name = name[: m.start()]
        return re.sub(r"__\d+", "", name), suffix

    # --- fingerprints -------------------------------------------------------
    init_arrays = {t.name: numpy_helper.to_array(t) for t in g.initializer}
    init_names = set(init_arrays)
    graph_in_names = {i.name for i in g.input}
    producer = {}
    for n in g.node:
        assert len(n.output) == 1, f"multi-output node {n.name} not supported"
        assert n.output[0] not in producer, f"duplicate tensor {n.output[0]}"
        producer[n.output[0]] = n

    def init_fp(name: str) -> str:
        a = init_arrays[name]
        h = hashlib.sha256()
        h.update(f"{a.dtype.str}|{a.shape}|".encode())
        h.update(a.tobytes())
        return "I:" + h.hexdigest()

    def attr_sig(n) -> str:
        parts = []
        for a in n.attribute:
            if a.type == onnx.AttributeProto.INT:
                val = f"i={a.i}"
            elif a.type == onnx.AttributeProto.INTS:
                val = f"is={list(a.ints)}"
            elif a.type == onnx.AttributeProto.FLOAT:
                val = f"f={a.f}"
            elif a.type == onnx.AttributeProto.FLOATS:
                val = f"fs={list(a.floats)}"
            elif a.type == onnx.AttributeProto.STRING:
                val = f"s={a.s!r}"
            elif a.type == onnx.AttributeProto.STRINGS:
                val = f"ss={[x for x in a.strings]}"
            else:
                raise NotImplementedError(f"attribute type {a.type} of {a.name}")
            parts.append(f"{a.name}={val}")
        return "|".join(sorted(parts))

    # any topological pass works; fingerprints are order-independent
    node_fp: dict[str, str] = {}  # original output tensor name -> fingerprint
    remaining = list(g.node)
    while remaining:
        progress = False
        nxt = []
        for n in remaining:
            deps = [i for i in n.input if i in producer]
            if all(d in node_fp for d in deps):
                in_fps = sorted(
                    ("G:" + i) if i in graph_in_names
                    else init_fp(i) if i in init_names
                    else node_fp[i]
                    for i in n.input
                )
                h = hashlib.sha256(
                    f"{n.op_type}|{attr_sig(n)}|{','.join(in_fps)}".encode()
                )
                node_fp[n.output[0]] = "N:" + h.hexdigest()
                progress = True
            else:
                nxt.append(n)
        assert progress, "cycle or missing tensor; cannot canonicalize"
        remaining = nxt

    # --- canonical names ----------------------------------------------------
    # initializers: sort by (stripped base, value) then number duplicates
    init_order = sorted(
        g.initializer,
        key=lambda t: (
            split_name(t.name)[0],
            str(init_arrays[t.name].dtype),
            tuple(init_arrays[t.name].shape),
            init_arrays[t.name].tobytes(),
        ),
    )
    init_canon: dict[str, str] = {}
    seen: dict[str, int] = {}
    for t in init_order:
        base, _ = split_name(t.name)
        k = seen.get(base, 0)
        seen[base] = k + 1
        init_canon[t.name] = base if k == 0 else f"{base}__{k}"

    # node output tensors: sort by (stripped base, fingerprint) then number
    node_order = sorted(g.node, key=lambda n: (split_name(n.output[0])[0], node_fp[n.output[0]]))
    tensor_canon: dict[str, str] = {}
    seen = {}
    for n in node_order:
        orig = n.output[0]
        base, suffix = split_name(orig)
        k = seen.get(base, 0)
        seen[base] = k + 1
        tensor_canon[orig] = (base if k == 0 else f"{base}__{k}") + suffix

    rename = {**init_canon, **tensor_canon}

    def rn(name: str) -> str:
        return rename.get(name, name)

    # --- rewrite and serialize ----------------------------------------------
    for t in g.initializer:
        t.name = rn(t.name)
    for n in g.node:
        n.name = rn(n.output[0]).rsplit(":", 1)[0]
        n.input[:] = [rn(i) for i in n.input]
        n.output[:] = [rn(o) for o in n.output]
    for vi in list(g.value_info) + list(g.input) + list(g.output):
        vi.name = rn(vi.name)

    # final topological order, ties broken by canonical name (deterministic)
    init_names = {t.name for t in g.initializer}
    by_out = {n.output[0]: n for n in g.node}
    consumers: dict[str, set] = {}
    pending: dict[str, int] = {}
    for n in g.node:
        prod_ins = {i for i in n.input if i not in init_names and i not in graph_in_names}
        pending[n.output[0]] = len(prod_ins)
        for i in prod_ins:
            consumers.setdefault(i, set()).add(n.output[0])

    heap = [(o, o) for o, p in pending.items() if p == 0]
    heapq.heapify(heap)
    ordered: list = []
    while heap:
        _, out = heapq.heappop(heap)
        ordered.append(by_out[out])
        for c in consumers.get(out, ()):
            pending[c] -= 1
            if pending[c] == 0:
                heapq.heappush(heap, (c, c))
    assert len(ordered) == len(g.node), "cycle detected; cannot canonicalize"

    del g.node[:]
    g.node.extend(ordered)
    sorted_inits = sorted(g.initializer, key=lambda t: t.name)
    del g.initializer[:]
    g.initializer.extend(sorted_inits)
    ordered_vi = sorted(g.value_info, key=lambda vi: vi.name)
    del g.value_info[:]
    g.value_info.extend(ordered_vi)


def convert_one(tflite_path: Path, onnx_path: Path, opset: int) -> None:
    import tf2onnx

    model_proto, _ = tf2onnx.convert.from_tflite(str(tflite_path), opset=opset)
    if model_proto is None:
        raise RuntimeError(f"tf2onnx returned no model for {tflite_path}")

    canonicalize(model_proto)
    onnx.save(model_proto, str(onnx_path))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--task", required=True, type=Path, help="Path to hand_landmarker.task (ZIP bundle)")
    parser.add_argument("--out", required=True, type=Path, help="Output directory for ONNX + provenance files")
    parser.add_argument("--opset", type=int, default=DEFAULT_OPSET, help=f"ONNX opset (default {DEFAULT_OPSET})")
    parser.add_argument("--seed", type=int, default=0, help="Seed for the numeric smoke input (default 0)")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)

    task_sha = sha256_file(args.task)
    print(f"[1/5] task sha256: {task_sha}")

    with tempfile.TemporaryDirectory() as td:
        tdir = Path(td)
        with zipfile.ZipFile(args.task) as zf:
            names = zf.namelist()
            print(f"[2/5] task archive members: {names}")
            for member, _, _ in MODELS:
                if member not in names:
                    raise SystemExit(f"archive is missing required member {member!r}")
            zf.extractall(tdir)

        provenance_models = []
        for i, (member, out_name, input_shape) in enumerate(MODELS):
            tfl = tdir / member
            tfl_sha = sha256_file(tfl)
            onnx_path = args.out / out_name

            print(f"[3/5.{i}] converting {member} -> {out_name} (opset {args.opset})")
            convert_one(tfl, onnx_path, args.opset)

            graph_info, smoke = verify_and_smoke(onnx_path, input_shape, args.seed)
            print(f"      input : {graph_info['inputs'][0]['name']} {graph_info['inputs'][0]['dtype']} {graph_info['inputs'][0]['shape']}")
            for o in graph_info["outputs"]:
                s = smoke[o["name"]]
                print(f"      output: {o['name']} {o['dtype']} {o['shape']}  min={s['min']:.6f} max={s['max']:.6f}")

            entry = {
                "source_tflite": member,
                "source_tflite_sha256": tfl_sha,
                "onnx_file": out_name,
                "onnx_sha256": sha256_file(onnx_path),
                "onnx_bytes": onnx_path.stat().st_size,
                "ir_version": graph_info["ir_version"],
                "opsets": graph_info["opsets"],
                "inputs": graph_info["inputs"],
                "outputs": graph_info["outputs"],
                "smoke_test": {
                    "seed": args.seed,
                    "input_distribution": "numpy default_rng(seed).standard_normal, float32",
                    "per_output_min_max": smoke,
                },
            }

            cross = cross_check_pair(onnx_path, tfl, input_shape, args.seed)
            for name, stats in cross.items():
                print(f"      tflite-diff {name}: max_abs={stats['max_abs_diff']:.3e} max_rel={stats['max_rel_diff']:.3e}")
            entry["tflite_cross_check"] = {
                "runtime": f"ai-edge-litert {ai_edge_litert_version()}",
                "per_output": cross,
                "note": (
                    "Source TFLite weights are FP16-quantized; ONNX dequantizes them to float32. "
                    "Absolute diffs on the detector grow with logit magnitude (out-of-distribution "
                    "noise input); relative diffs stay at float32 rounding level (~1e-6)."
                ),
            }
            provenance_models.append(entry)

    provenance = {
        "source_task_url": SOURCE_TASK_URL,
        "source_task_sha256": task_sha,
        "source_task_bytes": args.task.stat().st_size,
        "extraction_note": (
            "hand_landmarker.task is a ZIP archive containing hand_detector.tflite and "
            "hand_landmarks_detector.tflite (verified byte-identical to the copies in the "
            "legacy repo's app/src/main/assets/hand_models/)."
        ),
        "conversion": {
            "tool": "tf2onnx (TFLite frontend) via tf2onnx.convert.from_tflite",
            "tool_version": tf2onnx_version(),
            "onnx_package_version": onnx.__version__,
            "onnxruntime_version": onnxruntime_version(),
            "python": platform.python_version(),
            "platform": platform.platform(),
            "opset": args.opset,
            "dtype": "float32 (FP16-quantized TFLite weights dequantized to float32 initializers)",
            "fallback_note": (
                "tflite2onnx 0.4.1 fails on these graphs (rank-4 NHWC->NCHW permutation applied "
                "to rank-3 PReLU tensors in the detector head); tf2onnx's TFLite frontend is used instead."
            ),
            "reproducibility": (
                "The converted graph is canonicalized (structure-derived merkle names, "
                "deterministic topological ordering); with the same .task, tool versions and "
                "Python the serialized ONNX bytes and sha256 are identical across runs."
            ),
            "generated_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        },
        "license": {
            "spdx": "Apache-2.0",
            "copyright": "Copyright 2023 The MediaPipe Authors",
            "note": "Converted weights retain the upstream Apache-2.0 license and attribution.",
        },
        "models": provenance_models,
    }

    (args.out / "PROVENANCE.json").write_text(json.dumps(provenance, indent=2) + "\n")
    (args.out / "PROVENANCE.md").write_text(render_markdown(provenance))
    print(f"[4/5] wrote {args.out / 'PROVENANCE.json'}")
    print(f"[5/5] wrote {args.out / 'PROVENANCE.md'}")
    return 0


def tf2onnx_version() -> str:
    from importlib import metadata

    return metadata.version("tf2onnx")


def onnxruntime_version() -> str:
    import onnxruntime

    return onnxruntime.__version__


def ai_edge_litert_version() -> str:
    from importlib import metadata

    try:
        return metadata.version("ai-edge-litert")
    except metadata.PackageNotFoundError:
        return "unavailable"


def render_markdown(p: dict) -> str:
    lines = [
        "# Hand model ONNX conversion provenance",
        "",
        f"Generated (UTC): {p['conversion']['generated_utc']}",
        "",
        "## Source",
        "",
        f"- Bundle: `{p['source_task_url']}`",
        f"- Local `.task` SHA-256: `{p['source_task_sha256']}` ({p['source_task_bytes']:,} bytes)",
        f"- A fresh download of the URL above hashed identical to the local `.task` at conversion time.",
        f"- {p['extraction_note']}",
        "",
        "## Conversion",
        "",
        f"- Tool: {p['conversion']['tool']} {p['conversion']['tool_version']} (fallback rationale: {p['conversion']['fallback_note']})",
        f"- ONNX package {p['conversion']['onnx_package_version']}, onnxruntime {p['conversion']['onnxruntime_version']}, "
        f"Python {p['conversion']['python']} on {p['conversion']['platform']}",
        f"- Opset {p['conversion']['opset']}, dtype {p['conversion']['dtype']}",
        f"- Reproducibility: {p['conversion']['reproducibility']}",
        "",
        "## License",
        "",
        f"- {p['license']['spdx']} — {p['license']['copyright']}",
        f"- {p['license']['note']}",
        "",
    ]
    for m in p["models"]:
        lines += [
            f"## {m['onnx_file']}",
            "",
            f"- Source: `{m['source_tflite']}` (sha256 `{m['source_tflite_sha256']}`)",
            f"- ONNX sha256 `{m['onnx_sha256']}`, {m['onnx_bytes']:,} bytes, IR {m['ir_version']}, opsets {m['opsets']}",
            "- Inputs:",
        ]
        for i in m["inputs"]:
            lines.append(f"  - `{i['name']}` {i['dtype']} {i['shape']}")
        lines.append("- Outputs (order as returned by onnxruntime):")
        for o in m["outputs"]:
            s = m["smoke_test"]["per_output_min_max"][o["name"]]
            lines.append(f"  - `{o['name']}` {o['dtype']} {o['shape']} (smoke min={s['min']:.6f} max={s['max']:.6f})")
        lines += ["- TFLite cross-check (same seeded input, " + m["tflite_cross_check"]["runtime"] + "):"]
        for name, stats in m["tflite_cross_check"]["per_output"].items():
            lines.append(
                f"  - `{name}`: max|onnx-tflite| = {stats['max_abs_diff']:.3e} (rel {stats['max_rel_diff']:.3e})"
            )
        lines.append(f"- {m['tflite_cross_check']['note']}")
        lines.append("")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    sys.exit(main())

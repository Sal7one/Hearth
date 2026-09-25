#!/usr/bin/env python3
"""Hand-pipeline fidelity harness (Agent E).

Proves that a Python mirror of the C++ hand pipeline
(common-jni/src/main/cpp/sign/hand_landmarks.cpp + hand_geometry.cpp) reproduces
the landmark distributions produced by mediapipe.solutions.hands
(static_image_mode=True, max_num_hands=2, model_complexity=1) using the ONNX
conversions of the MediaPipe task models (research/sign/handmodels/, Agent C).

Semantics below were VERIFIED against the official MediaPipe 0.10.14 runtime by
extracting the live task graph (TaskRunner.get_graph_config()), injecting the
model files, exposing internal streams (tensor floats, pre/post-NMS
detections, DetectionsToRects / RectTransformation outputs) and diffing against
this mirror. Key verified facts:

  Palm model (hand_detector.onnx):
    * input  : [1,192,192,3] NHWC float, RGB, [0,1] (official tensor == plain
               RGB/255 to 7e-8), letterbox (keep_aspect_ratio) in MediaPipe;
               this mirror defaults to stretch (the C++ behavior) and also
               measures a letterbox variant.
    * outputs: Identity=[1,2016,18] REGRESSIONS (output index 0),
               Identity_1=[1,2016,1] SCORES (output index 1) - the reverse of
               the current C++ assumption.
    * anchors: fixed_anchor_size -> w=h=1.0. Grid positions:
               rows 0..1151  : 24x24 grid (stride 8), y-major, 2 anchors/cell;
               rows 1152..2015: 12x12 grid (strides 16), y-major, SIX anchors
               per cell (the three stride-16 layers are cell-interleaved).
               (Verified exactly against official decoded detections.)
    * decode : cx=r0/192+ax, cy=r1/192+ay, w=r2/192, h=r3/192 (x-first),
               keypoints (r[4+2k]/192+ax, r[5+2k]/192+ay); scores
               sigmoid(clip(raw,+-100)) >= 0.5, NMS IoU 0.3.
  Hand rect (from DetectionsToRects + RectTransformation, verified numerically):
    * rect0: center=detection box center, w,h=box w,h (normalized).
    * rotation = 63.3798deg - atan2(-(dy_px), dx_px), dx,dy = kp2-kp0 in
      PIXELS. (The v0.10.14 GitHub source computes 90deg - atan2(-dy,dx); the
      deployed pip wheel behaves as if an extra constant -26.6202deg phase
      were applied - measured constant to 0.0004deg over 12 images.)
    * transform (pixel space): side = max(2.6*w0*W, 2.6*h0*H) (square_long is
      in pixels); center += (0.5*h0px*sin(rot), -0.5*h0px*cos(rot))
      (shift_y=-0.5 applied along the rotated rect -y axis).
  Landmark model (hand_landmarks_detector.onnx):
    * input : [1,224,224,3] NHWC float [0,1], RGB (swept empirically below).
    * outputs: Identity=[1,63] landmarks, Identity_1/Identity_2=[1,1] scalars
      (presence identified empirically), Identity_3=[1,63] world landmarks.
    * landmarks: x=raw/224, y=raw/224, z=raw/(224*0.4) (source-verified
      normalize_z=0.4, confirmed empirically), then inverse-projected through
      the crop rect.

Run with the old repo venv (mediapipe + onnxruntime + numpy + cv2):
  /Users/salehalanazi/ZCodeProject/ffmpegmakercustom/scripts/ml/.ml-venv/bin/python \
      scripts/sign/fidelity_check.py [--reference-only]
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
import time
from pathlib import Path

import cv2
import numpy as np

REPO = Path(__file__).resolve().parents[2]
HANDMODELS_DIR = REPO / "research" / "sign" / "handmodels"
PALM_ONNX = HANDMODELS_DIR / "hand_detector.onnx"
LM_ONNX = HANDMODELS_DIR / "hand_landmarks_detector.onnx"
PROVENANCE_JSON = HANDMODELS_DIR / "PROVENANCE.json"
OUT_DIR = REPO / "research" / "sign" / "fidelity"
REPORT_MD = OUT_DIR / "REPORT.md"
GOLDEN_JSON = OUT_DIR / "golden.json"
REFERENCE_JSON = OUT_DIR / "reference_landmarks.json"

IMAGES_ROOT = Path(
    "/Users/salehalanazi/ZCodeProject/ffmpegmakercustom/scripts/ml/datasets/"
    "arsl_images/aasl")

SEED = 1234
POOL_PER_LETTER = 4
N_IMAGES = 60
N_PROBE = 12
MIN_MATCH_DIST = 0.05
PALM_THRESHOLD = 0.5
NMS_IOU = 0.3
MAX_HANDS = 2
PALM_INPUT = 192
LM_INPUT = 224
NUM_ANCHORS = 2016
NUM_COORDS = 18
KP_WRIST, KP_MIDDLE_MCP = 0, 2
LM_WRIST, LM_MIDDLE_MCP = 0, 9
NUM_LANDMARKS = 21
Z_DIVISOR = 224.0 * 0.4
# Deployed-wheel rotation phase (see module docstring): 90 - 26.6202 deg.
ROT_BASE_DEG = 63.3798

# ---------------------------------------------------------------------------
# Anchor table (VERIFIED against the official graph; see module docstring)
# ---------------------------------------------------------------------------

def build_anchors_mp_style() -> np.ndarray:
    """True MediaPipe anchor table for the palm detector.

    fixed_anchor_size=true -> all anchors w=h=1.0. Grid: 24x24 (stride 8) with
    2 anchors per cell for rows 0..1151; 12x12 (stride 16) with 6 anchors per
    cell (three layers interleaved per cell) for rows 1152..2015.
    """
    a = np.zeros((NUM_ANCHORS, 4), dtype=np.float64)
    for r in range(1152):
        cell = r // 2
        x, y = cell % 24, cell // 24
        a[r] = [(x + 0.5) / 24, (y + 0.5) / 24, 1.0, 1.0]
    for r in range(1152, NUM_ANCHORS):
        cell = (r - 1152) // 6
        x, y = cell % 12, cell // 12
        a[r] = [(x + 0.5) / 12, (y + 0.5) / 12, 1.0, 1.0]
    return a


def build_anchors_cpp_style() -> np.ndarray:
    """The (incorrect) table the current C++ buildAnchors() produces."""
    strides = [8, 16, 16, 16]
    min_scale, max_scale = 0.1484375, 0.75
    scales = [min_scale + (max_scale - min_scale) * i / 3.0 for i in range(4)]
    scales.append(scales[3])
    anchors = np.empty((NUM_ANCHORS, 4), dtype=np.float64)
    i = 0
    for layer in range(4):
        grid = PALM_INPUT // strides[layer]
        pair = (scales[layer], math.sqrt(scales[layer] * scales[layer + 1]))
        for y in range(grid):
            for x in range(grid):
                for k in range(2):
                    anchors[i, 0] = (x + 0.5) / grid
                    anchors[i, 1] = (y + 0.5) / grid
                    anchors[i, 2] = pair[k]
                    anchors[i, 3] = pair[k]
                    i += 1
    return anchors


# ---------------------------------------------------------------------------
# Geometry - literal numpy ports of hand_geometry.cpp / hand_landmarks.cpp
# ---------------------------------------------------------------------------

def rotated_rect_points(cx, cy, width, height, angle_deg):
    """Port of rotatedRectPoints(): corners in cv order (bl, tl, tr, br)."""
    angle = math.radians(angle_deg)
    b = math.cos(angle) * 0.5
    a = math.sin(angle) * 0.5
    p0 = (cx - a * height - b * width, cy + b * height - a * width)
    p1 = (cx + a * height - b * width, cy - b * height - a * width)
    p2 = (2.0 * cx - p0[0], 2.0 * cy - p0[1])
    p3 = (2.0 * cx - p1[0], 2.0 * cy - p1[1])
    return [p0, p1, p2, p3]


def homography4(src, dst):
    """Port of homography4(): Gauss-Jordan on the 8x8 system; None if singular."""
    m = np.zeros((8, 9), dtype=np.float64)
    for i in range(4):
        x, y = src[i]
        xp, yp = dst[i]
        m[2 * i, 0] = x; m[2 * i, 1] = y; m[2 * i, 2] = 1.0
        m[2 * i, 6] = -x * xp; m[2 * i, 7] = -y * xp; m[2 * i, 8] = xp
        m[2 * i + 1, 3] = x; m[2 * i + 1, 4] = y; m[2 * i + 1, 5] = 1.0
        m[2 * i + 1, 6] = -x * yp; m[2 * i + 1, 7] = -y * yp
        m[2 * i + 1, 8] = yp
    for col in range(8):
        pivot = col
        for r in range(col + 1, 8):
            if abs(m[r, col]) > abs(m[pivot, col]):
                pivot = r
        if abs(m[pivot, col]) < 1e-12:
            return None
        if pivot != col:
            m[[pivot, col]] = m[[col, pivot]]
        for r in range(8):
            if r == col:
                continue
            factor = m[r, col] / m[col, col]
            m[r] -= factor * m[col]
    h = np.array([m[i, 8] / m[i, i] for i in range(8)] + [1.0])
    return h


def invert3x3(h):
    """Port of invert3x3(): adjugate / determinant."""
    a11 = h[4] * h[8] - h[5] * h[7]
    a12 = h[3] * h[8] - h[5] * h[6]
    a13 = h[3] * h[7] - h[4] * h[6]
    a21 = h[1] * h[8] - h[2] * h[7]
    a22 = h[0] * h[8] - h[2] * h[6]
    a23 = h[0] * h[7] - h[1] * h[6]
    a31 = h[1] * h[5] - h[2] * h[4]
    a32 = h[0] * h[5] - h[2] * h[3]
    a33 = h[0] * h[4] - h[1] * h[3]
    det = h[0] * a11 - h[1] * a12 + h[2] * a13
    if abs(det) < 1e-12:
        return None
    return np.array([
        a11 / det, -a21 / det, a31 / det,
        -a12 / det, a22 / det, -a32 / det,
        a13 / det, -a23 / det, a33 / det])


def apply_homography(h, x, y):
    d = h[6] * x + h[7] * y + h[8]
    return (h[0] * x + h[1] * y + h[2]) / d, (h[3] * x + h[4] * y + h[5]) / d


def bilinear_sample_bgr(img_f, sx, sy):
    """Vectorized port of the C++ bilinear sampler with BORDER_CONSTANT 0."""
    h, w = img_f.shape[:2]
    x0f = np.floor(sx); y0f = np.floor(sy)
    tx = sx - x0f; ty = sy - y0f
    x0 = x0f.astype(np.int64); y0 = y0f.astype(np.int64)
    out = np.zeros(sx.shape + (3,), dtype=np.float64)
    for dy in (0, 1):
        yy = y0 + dy
        yin = (yy >= 0) & (yy < h)
        wy = (1.0 - ty) if dy == 0 else ty
        for dx in (0, 1):
            xx = x0 + dx
            xin = (xx >= 0) & (xx < w)
            wx = (1.0 - tx) if dx == 0 else tx
            valid = (yin & xin)[:, :, None]
            yy_c = np.clip(yy, 0, h - 1)
            xx_c = np.clip(xx, 0, w - 1)
            out += img_f[yy_c, xx_c] * (wx * wy)[:, :, None] * valid
    return out


def resize_192_bgr_float(img_bgr):
    """Mirror of resizeRgbaToBgrFloat() -> 192x192 BGR floats (0..255)."""
    return cv2.resize(img_bgr, (PALM_INPUT, PALM_INPUT),
                      interpolation=cv2.INTER_LINEAR).astype(np.float64)


def letterbox_192_bgr_float(img_bgr):
    """MediaPipe keep_aspect_ratio preprocessing (BORDER_ZERO, centered)."""
    h, w = img_bgr.shape[:2]
    s = min(PALM_INPUT / h, PALM_INPUT / w)
    rw, rh = int(round(w * s)), int(round(h * s))
    r = cv2.resize(img_bgr, (rw, rh), interpolation=cv2.INTER_LINEAR)
    canvas = np.zeros((PALM_INPUT, PALM_INPUT, 3), np.float64)
    ox, oy = (PALM_INPUT - rw) // 2, (PALM_INPUT - rh) // 2
    canvas[oy:oy + rh, ox:ox + rw] = r.astype(np.float64)
    return canvas, (ox, oy, rw, rh)


def make_crop_warp(width, height, center_x, center_y, side_px, angle_deg):
    """Returns (inverse_h, size_px) for the C++ landmarksForCrop() geometry.

    side_px is the crop side in source pixels (the crop is a pixel square).
    """
    if not (side_px > 0 and math.isfinite(side_px)):
        return None, None
    src_pts = rotated_rect_points(center_x * width, center_y * height,
                                  side_px, side_px, angle_deg)
    dst_pts = [(0.0, side_px), (0.0, 0.0), (side_px, 0.0), (side_px, side_px)]
    h_mat = homography4(src_pts, dst_pts)
    if h_mat is None:
        return None, None
    inv = invert3x3(h_mat)
    if inv is None:
        return None, None
    return inv, side_px


def crop_224_bgr_float(img_f, inverse_h, size_px):
    """Mirror of the C++ single-pass 224x224 crop sampling."""
    scale = size_px / float(LM_INPUT)
    c = (np.arange(LM_INPUT, dtype=np.float64) + 0.5) * scale - 0.5
    rx, ry = np.meshgrid(c, c)
    sx, sy = apply_homography(inverse_h, rx, ry)
    return bilinear_sample_bgr(img_f, sx, sy)


# ---------------------------------------------------------------------------
# Palm decode
# ---------------------------------------------------------------------------

def decode_palms(scores_raw, regs_raw, anchors, variant):
    """SSD decode + greedy NMS (IoU 0.3), threshold 0.5, sigmoid clip +-100.

    variants:
      mp  : VERIFIED MediaPipe semantics (see module docstring): /192 absolute
            offsets, x-first box layout, keypoints x-first, w=h=1 anchors.
      cpp : the current C++ decodePalms() (anchor-size-relative offsets with
            scale-sized anchors) - kept to quantify the C++ deviation.
    """
    scores = 1.0 / (1.0 + np.exp(-np.clip(scores_raw, -100.0, 100.0)))
    keep = np.where(scores >= PALM_THRESHOLD)[0]

    cands = []
    for i in keep:
        a = anchors[i]
        r = regs_raw[i]
        if variant == "cpp":
            cx = r[0] * a[2] + a[0]
            cy = r[1] * a[3] + a[1]
            w = r[2] * a[2]
            h = r[3] * a[3]
            kps = [(r[4 + 2 * k] * a[2] + a[0], r[5 + 2 * k] * a[3] + a[1])
                   for k in range(7)]
        else:
            cx = r[0] / 192.0 * a[2] + a[0]
            cy = r[1] / 192.0 * a[3] + a[1]
            w = r[2] / 192.0 * a[2]
            h = r[3] / 192.0 * a[3]
            kps = [(r[4 + 2 * k] / 192.0 * a[2] + a[0],
                    r[5 + 2 * k] / 192.0 * a[3] + a[1]) for k in range(7)]
        cands.append({"box": (cx - w / 2.0, cy - h / 2.0, w, h),
                      "kps": kps, "score": float(scores[i])})

    cands.sort(key=lambda c: -c["score"])
    out = []
    suppressed = [False] * len(cands)
    for i, c in enumerate(cands):
        if suppressed[i]:
            continue
        bx, by, bw, bh = c["box"]
        for j in range(i + 1, len(cands)):
            if suppressed[j]:
                continue
            dx, dy, dw, dh = cands[j]["box"]
            x1 = max(bx, dx); y1 = max(by, dy)
            x2 = min(bx + bw, dx + dw); y2 = min(by + bh, dy + dh)
            inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
            union = bw * bh + dw * dh - inter
            if union > 0 and inter / union > NMS_IOU:
                suppressed[j] = True
        out.append(c)
    return out


def rect_from_palm_mp(box, kps, width, height):
    """VERIFIED MediaPipe hand rect (see module docstring).

    Returns (center_x_norm, center_y_norm, side_norm_x, rot_deg) where the
    crop is a pixel-space square: side_px = side_norm_x * width.
    """
    bx, by, bw, bh = box
    cx = bx + bw / 2.0
    cy = by + bh / 2.0
    dx = (kps[KP_MIDDLE_MCP][0] - kps[KP_WRIST][0]) * width
    dy = (kps[KP_MIDDLE_MCP][1] - kps[KP_WRIST][1]) * height
    rot_deg = ROT_BASE_DEG - math.degrees(math.atan2(-dy, dx))
    w0px, h0px = bw * width, bh * height
    side_px = max(2.6 * w0px, 2.6 * h0px)
    rot = math.radians(rot_deg)
    cx_px = cx * width + 0.5 * h0px * math.sin(rot)
    cy_px = cy * height - 0.5 * h0px * math.cos(rot)
    return cx_px / width, cy_px / height, side_px / width, rot_deg


def rect_from_palm_cpp(kps):
    """The rect the current C++ detect() builds (kp midpoint center)."""
    x0, y0 = kps[KP_WRIST]
    x2, y2 = kps[KP_MIDDLE_MCP]
    vx, vy = x2 - x0, y2 - y0
    rotation = math.atan2(vy, vx)
    length = math.sqrt(vx * vx + vy * vy)
    side = max(32.0, length * 2.6)
    cx = (x0 + x2) / 2.0
    cy = (y0 + y2) / 2.0
    angle_deg = (90.0 - math.degrees(rotation)) * -1.0
    return cx, cy, side, angle_deg


# ---------------------------------------------------------------------------
# Model wrappers
# ---------------------------------------------------------------------------

def prepare_input(tensor_hwc, order, scale_mode):
    """Apply channel order + value range to an HxWx3 float (0..255) BGR plane."""
    t = tensor_hwc
    if order == "RGB":
        t = t[:, :, ::-1]
    if scale_mode == "div255":
        t = t / 255.0
    elif scale_mode == "raw":
        pass
    elif scale_mode == "pm1":
        t = t / 127.5 - 1.0
    else:
        raise ValueError(scale_mode)
    return np.ascontiguousarray(t, dtype=np.float32)


def sigmoid(x):
    if x < 0:
        return 1.0 / (1.0 + math.exp(-x))
    return 1.0 - 1.0 / (1.0 + math.exp(x))


class OnnxModel:
    def __init__(self, path):
        import onnxruntime as ort
        so = ort.SessionOptions()
        self.sess = ort.InferenceSession(str(path), so,
                                         providers=["CPUExecutionProvider"])
        self.input_name = self.sess.get_inputs()[0].name
        shape = self.sess.get_inputs()[0].shape
        self.input_shape = [d if isinstance(d, int) else -1 for d in shape]
        self.output_names = [o.name for o in self.sess.get_outputs()]
        self.output_shapes = [list(o.shape) for o in self.sess.get_outputs()]

    def run(self, tensor_hwc):
        if len(self.input_shape) == 4 and self.input_shape[1] == 3:
            x = np.ascontiguousarray(tensor_hwc.transpose(2, 0, 1)[None])
        else:
            x = np.ascontiguousarray(tensor_hwc[None])
        outs = self.sess.run(self.output_names, {self.input_name: x})
        return [np.asarray(o, dtype=np.float64).ravel() for o in outs]


def palm_output_indices(outputs):
    """Scores are output[1] ([1,2016,1]), regressions output[0] ([1,2016,18]).

    Identified by shape (the two sizes are distinct) - matches the ONNX output
    order Identity, Identity_1 from PROVENANCE.json.
    """
    scores_idx = reg_idx = None
    for i, o in enumerate(outputs):
        if o.size == NUM_ANCHORS:
            scores_idx = i
        elif o.size == NUM_ANCHORS * NUM_COORDS:
            reg_idx = i
    if scores_idx is None or reg_idx is None:
        raise RuntimeError("cannot identify palm outputs")
    return scores_idx, reg_idx


def identify_landmark_outputs(outputs, provenance=None):
    """landmarks = FIRST 63-float output; later 63-float outputs are world."""
    lm_idx = None
    world_idx = []
    scalars = []
    for i, o in enumerate(outputs):
        if o.size >= NUM_LANDMARKS * 3:
            if lm_idx is None:
                lm_idx = i
            else:
                world_idx.append(i)
        elif o.size <= 2:
            scalars.append(i)
    return {"landmarks": lm_idx, "world": world_idx, "scalars": scalars}


def run_palm_detections(palm_model, img_bgr_u8, order, scale_mode, anchors,
                        decode_variant, letterbox=False):
    """Palm detection; detections returned in IMAGE-normalized coordinates."""
    if letterbox:
        lb, (ox, oy, rw, rh) = letterbox_192_bgr_float(img_bgr_u8)
        x = prepare_input(lb, order, scale_mode)
    else:
        x = prepare_input(resize_192_bgr_float(img_bgr_u8), order, scale_mode)
    outs = palm_model.run(x)
    s_idx, r_idx = palm_output_indices(outs)
    dets = decode_palms(outs[s_idx],
                        outs[r_idx].reshape(NUM_ANCHORS, NUM_COORDS),
                        anchors, decode_variant)
    if letterbox:
        def unmap(v):
            return ((v[0] * PALM_INPUT - ox) / rw, (v[1] * PALM_INPUT - oy) / rh)
        for d in dets:
            bx, by, bw, bh = d["box"]
            ix0, iy0 = unmap((bx, by))
            ix1, iy1 = unmap((bx + bw, by + bh))
            d["box"] = (ix0, iy0, ix1 - ix0, iy1 - iy0)
            d["kps"] = [unmap(kp) for kp in d["kps"]]
    return dets


def run_landmarks(lm_model, img_f, center, side_px, rot_deg, flip_rot, order,
                  scale_mode, lm_info):
    """Mirror of landmarksForCrop() with the VERIFIED rect math.

    Returns (points Nx3 image-normalized with z=raw/(224*0.4),
    {scalar_idx: raw_value}) or None when the crop geometry degenerates.
    """
    angle_deg = -rot_deg if flip_rot else rot_deg
    inv, size_px = make_crop_warp(img_f.shape[1], img_f.shape[0], center[0],
                                  center[1], side_px, angle_deg)
    if inv is None:
        return None
    crop = crop_224_bgr_float(img_f, inv, size_px)
    x = prepare_input(crop, order, scale_mode)
    outs = lm_model.run(x)
    lm_raw = outs[lm_info["landmarks"]]
    lm_raw = lm_raw[:NUM_LANDMARKS * 3].reshape(NUM_LANDMARKS, 3)

    # Graph semantics: x=raw/224 first (crop-normalized), then scale to the
    # crop side. (The current C++ multiplies raw*sizePx directly - it is
    # missing the /224 normalization; reported as a deviation.)
    px = lm_raw[:, 0] / float(LM_INPUT) * size_px
    py = lm_raw[:, 1] / float(LM_INPUT) * size_px
    ix, iy = apply_homography(inv, px, py)
    pts = np.stack([ix / img_f.shape[1], iy / img_f.shape[0],
                    lm_raw[:, 2] / Z_DIVISOR], axis=1)
    scalars = {i: float(outs[i][0]) for i in lm_info.get("scalars", [])}
    return pts, scalars


# ---------------------------------------------------------------------------
# Reference extraction (mediapipe.solutions.hands)
# ---------------------------------------------------------------------------

def discover_pool():
    letters = sorted(p for p in IMAGES_ROOT.iterdir() if p.is_dir())
    rng = random.Random(SEED)
    pool = []
    for letter in letters:
        files = sorted(p for p in letter.iterdir()
                       if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
        rng.shuffle(files)
        pool.extend(files[:POOL_PER_LETTER])
    return pool


def extract_reference(pool, model_complexity=1):
    import mediapipe as mp
    hands = mp.solutions.hands.Hands(
        static_image_mode=True, max_num_hands=MAX_HANDS,
        model_complexity=model_complexity)
    data = {}
    for p in pool:
        img = cv2.imread(str(p), cv2.IMREAD_COLOR)
        if img is None:
            continue
        res = hands.process(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        entry = {"w": img.shape[1], "h": img.shape[0], "hands": [],
                 "labels": []}
        if res.multi_hand_landmarks:
            for lm, hd in zip(res.multi_hand_landmarks,
                              res.multi_handedness or []):
                entry["hands"].append([[pt.x, pt.y, pt.z] for pt in lm.landmark])
                entry["labels"].append(hd.classification[0].label)
        data[str(p)] = entry
    hands.close()
    return data


def load_reference(pool, force=False):
    chosen = [str(p) for p in pool]
    if REFERENCE_JSON.exists() and not force:
        cached = json.loads(REFERENCE_JSON.read_text())
        if cached.get("seed") == SEED and cached.get("pool") == chosen:
            return cached["data"]
    print(f"[ref] running mediapipe.solutions.hands on {len(pool)} images ...")
    t0 = time.time()
    data = extract_reference(pool)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    REFERENCE_JSON.write_text(json.dumps(
        {"seed": SEED, "pool": chosen, "data": data}))
    n_with = sum(1 for v in data.values() if v["hands"])
    print(f"[ref] done in {time.time() - t0:.1f}s ({n_with}/{len(data)} "
          f"images with hands)")
    return data


# ---------------------------------------------------------------------------
# Sweep machinery
# ---------------------------------------------------------------------------

def match_hands(ours, ref):
    pairs = []
    if not ours:
        return pairs
    for rh in ref:
        best, best_d = None, 1e9
        for oh in ours:
            d = float(np.mean(np.hypot(oh[:, 0] - rh[:, 0],
                                       oh[:, 1] - rh[:, 1])))
            if d < best_d:
                best, best_d = oh, d
        if best is not None and best_d < MIN_MATCH_DIST:
            pairs.append((rh, best, best_d))
    return pairs


def combo_key(c):
    return (f"palm={c['palm_order']}/{c['palm_scale']}"
            f"{'/lb' if c.get('letterbox') else ''} "
            f"lm={c['lm_order']}/{c['lm_scale']} "
            f"decode={c.get('decode', '-')} rect={c.get('rect', '-')}")


def pipeline_hands(palm_model, lm_model, img_bgr, img_f, combo, anchors,
                   lm_info, presence_idx=None, collect=None):
    dets = run_palm_detections(palm_model, img_bgr, combo["palm_order"],
                               combo["palm_scale"], anchors,
                               combo["decode"], combo.get("letterbox", False))
    hands = []
    for det in dets[:MAX_HANDS]:
        if combo.get("rect") == "cpp":
            cx, cy, side, angle = rect_from_palm_cpp(det["kps"])
            center, side_px, rot = (cx, cy), side * img_bgr.shape[1], angle
        else:
            cx, cy, side_x, rot = rect_from_palm_mp(det["box"], det["kps"],
                                                    img_bgr.shape[1],
                                                    img_bgr.shape[0])
            center, side_px = (cx, cy), side_x * img_bgr.shape[1]
        out = run_landmarks(lm_model, img_f, center, side_px, rot,
                            combo.get("flip_rot", False), combo["lm_order"],
                            combo["lm_scale"], lm_info)
        if out is None:
            continue
        pts, scalars = out
        if presence_idx is not None and presence_idx in scalars:
            if sigmoid(scalars[presence_idx]) < 0.5:
                continue
        if collect is not None:
            collect.append({"center": center, "side_px": side_px,
                            "rot": rot, "scalars": scalars, "det": det})
        hands.append(pts)
    return hands


def calibrate(palm_model, lm_model, probe, combo, anchors, lm_info, ref_data):
    """Calibrate crop-rotation sign and the presence scalar on probe images.

    Returns (flip_rot, presence_idx, stats)."""
    results = {}
    for flip in (False, True):
        c = dict(combo, flip_rot=flip)
        dxy = []
        for p, img, rh in probe:
            hands = pipeline_hands(palm_model, lm_model, img,
                                   img.astype(np.float64), c, anchors, lm_info)
            pairs = match_hands(hands, rh)
            if pairs:
                ds = [float(np.mean(np.hypot(o[:, 0] - r[:, 0],
                                             o[:, 1] - r[:, 1])))
                      for r, o, _ in pairs]
                dxy.append(float(np.mean(ds)))
        results[flip] = float(np.mean(dxy)) if dxy else float("nan")
    flip_rot = min(results, key=lambda f: (results[f] if not math.isnan(results[f]) else 1e9))

    scalar_pass = {}
    scalar_dxy = {}
    c = dict(combo, flip_rot=flip_rot)
    for p, img, rh in probe:
        col = []
        hands = pipeline_hands(palm_model, lm_model, img,
                               img.astype(np.float64), c, anchors, lm_info,
                               collect=col)
        if not match_hands(hands, rh):
            continue
        for item in col:
            for i, v in item["scalars"].items():
                scalar_pass.setdefault(i, []).append(1.0 if sigmoid(v) >= 0.5
                                                     else 0.0)
        for r, o, _ in match_hands(hands, rh):
            d = float(np.mean(np.hypot(o[:, 0] - r[:, 0], o[:, 1] - r[:, 1])))
            for i in lm_info["scalars"]:
                scalar_dxy.setdefault(i, []).append(d)
    presence_idx, best_rate = None, 0.0
    for i, vals in scalar_pass.items():
        rate = float(np.mean(vals)) if vals else 0.0
        if rate > best_rate:
            presence_idx, best_rate = i, rate
    if best_rate < 0.9:
        presence_idx = None
    stats = {"crop_flip_dxy": {str(k): round(v, 5) for k, v in results.items()},
             "scalar_pass_rate": {str(i): float(np.mean(v))
                                  for i, v in scalar_pass.items()},
             "presence_idx": presence_idx,
             "mean_dxy_when_matched": (float(np.mean(scalar_dxy[presence_idx]))
                                       if presence_idx in scalar_dxy and
                                       scalar_dxy[presence_idx] else None)}
    return flip_rot, presence_idx, stats


def measure_floor(images, task_path):
    """Mean agreement between the OFFICIAL Tasks runtime (same tflite models,
    official graph) and the solutions reference on the same images.

    This is the best any mirror of these models can achieve against the
    reference: the reference pipeline uses the solutions-bundled tflite
    variants while the task archive carries the task variants.
    """
    import mediapipe as mp
    from mediapipe.tasks import python as mp_python
    from mediapipe.tasks.python import vision
    det = vision.HandLandmarker.create_from_options(vision.HandLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=task_path),
        running_mode=vision.RunningMode.IMAGE, num_hands=MAX_HANDS))
    dxy_all, dz_all, found = [], [], 0
    for p, img, rh in images:
        mpimg = mp.Image(image_format=mp.ImageFormat.SRGB,
                         data=img[:, :, ::-1].copy())
        res = det.detect(mpimg)
        hands = [np.array([[l.x, l.y, l.z] for l in hl])
                 for hl in res.hand_landmarks]
        if hands:
            found += 1
        for r_h, o_h, _ in match_hands(hands, rh):
            dxy_all.extend(np.hypot(o_h[:, 0] - r_h[:, 0],
                                    o_h[:, 1] - r_h[:, 1]).tolist())
            dz_all.extend(np.abs(o_h[:, 2] - r_h[:, 2]).tolist())
    det.close()
    return {"dxy_mean": float(np.mean(dxy_all)),
            "dxy_max": float(np.max(dxy_all)),
            "dz_mean": float(np.mean(dz_all)),
            "dz_max": float(np.max(dz_all)),
            "pairs": len(dxy_all) // NUM_LANDMARKS, "found": found}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--probe", type=int, default=N_PROBE)
    ap.add_argument("--reference-only", action="store_true")
    ap.add_argument("--floor-task", type=str, default=None,
                    help="path to a .task zip built from the two tflites; "
                         "measures the official-runtime agreement floor "
                         "(mediapipe Tasks vs solutions reference)")
    args = ap.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    pool = discover_pool()
    if args.reference_only:
        load_reference(pool, force=True)
        return
    ref_data = load_reference(pool)

    for p in (PALM_ONNX, LM_ONNX):
        if not p.exists():
            sys.exit(f"missing model: {p} (Agent C conversion not present)")
    provenance = {}
    if PROVENANCE_JSON.exists():
        try:
            provenance = json.loads(PROVENANCE_JSON.read_text())
        except Exception as e:
            print(f"[warn] PROVENANCE.json unreadable: {e}")

    images = []
    for p in pool:
        entry = ref_data.get(str(p))
        if not entry or not entry["hands"]:
            continue
        img = cv2.imread(str(p), cv2.IMREAD_COLOR)
        if img is None or (img.shape[1], img.shape[0]) != (entry["w"],
                                                          entry["h"]):
            continue
        images.append((p, img, np.array(entry["hands"], dtype=np.float64)))
        if len(images) >= N_IMAGES:
            break
    n_ref_any = sum(1 for v in ref_data.values() if v["hands"])
    n_pool = len(pool)
    print(f"[set] pool={n_pool}, ref found on {n_ref_any}; evaluating "
          f"{len(images)} ref-positive images")
    if len(images) < 40:
        sys.exit("fewer than 40 reference-positive images; refusing")

    palm_model = OnnxModel(PALM_ONNX)
    lm_model = OnnxModel(LM_ONNX)
    lm_outs = lm_model.run(np.zeros((LM_INPUT, LM_INPUT, 3), np.float32))
    lm_info = identify_landmark_outputs(lm_outs, provenance)
    dummy = palm_model.run(np.zeros((PALM_INPUT, PALM_INPUT, 3), np.float32))
    s_idx, r_idx = palm_output_indices(dummy)
    print(f"[palm] scores=output[{s_idx}] "
          f"({palm_model.output_names[s_idx]}), "
          f"boxes=output[{r_idx}] ({palm_model.output_names[r_idx]})")
    print(f"[lm]   roles: {lm_info}")

    probe = images[:args.probe]
    anchors_mp = build_anchors_mp_style()
    anchors_cpp = build_anchors_cpp_style()

    # ---- Stage A: palm preprocessing sweep on the probe subset -------------
    palm_combos = [("RGB", "div255", False), ("BGR", "div255", False),
                   ("RGB", "raw", False), ("BGR", "raw", False),
                   ("RGB", "pm1", False), ("BGR", "pm1", False),
                   ("RGB", "div255", True)]
    print(f"\n[stage A] palm preprocessing (probe {len(probe)} images; "
          "kp err = mean |kp-ref| for palm wrist/middle-MCP analogues "
          "(kp0, kp2))")
    print(f"{'palm input':>20} | {'dets/img':>8} | {'kp0/kp2 err':>11}")
    stage_a = []
    for order, scale, lb in palm_combos:
        n_dets = 0
        kp_err = []
        for p, img, rh in probe:
            dets = run_palm_detections(palm_model, img, order, scale,
                                       anchors_mp, "mp", letterbox=lb)
            n_dets += len(dets)
            if dets:
                # palm kp0/kp2 are model-specific keypoints; measure distance
                # to reference landmarks 0/9 as a proxy (they are shifted but
                # should be consistent); the real gate is the landmark stage.
                det = dets[0]
                for kp_i, lm_i in ((KP_WRIST, LM_WRIST),
                                   (KP_MIDDLE_MCP, LM_MIDDLE_MCP)):
                    kx, ky = det["kps"][kp_i]
                    kp_err.append(math.hypot(kx - rh[0, lm_i, 0],
                                             ky - rh[0, lm_i, 1]))
        e = float(np.mean(kp_err)) if kp_err else float("nan")
        print(f"{order + '/' + scale + ('/letterbox' if lb else ''):>20} | "
              f"{n_dets / len(probe):8.2f} | {e:11.4f}")
        stage_a.append({"order": order, "scale": scale, "letterbox": lb,
                        "n_dets": n_dets, "kp_err": e})

    # Keep combos that find a plausible number of palms (>=1 per 3 images).
    survivors = [s for s in stage_a
                 if s["n_dets"] >= max(1, len(probe) / 3)]
    if not survivors:
        sys.exit("no palm preprocessing found any detections")
    for s in survivors:
        print(f"[stage A] survivor palm={s['order']}/{s['scale']}"
              f"{'/letterbox' if s['letterbox'] else ''} dets={s['n_dets']}")

    # ---- Calibrate crop rotation sign + presence scalar --------------------
    top = survivors[0]
    top_combo = {"palm_order": top["order"], "palm_scale": top["scale"],
                 "letterbox": top["letterbox"], "decode": "mp",
                 "rect": "mp", "lm_order": "RGB", "lm_scale": "div255"}
    flip_rot, presence_idx, cal = calibrate(palm_model, lm_model, probe,
                                            top_combo, anchors_mp, lm_info,
                                            ref_data)
    print(f"[calib] {json.dumps(cal)} -> flip_rot={flip_rot}, "
          f"presence={presence_idx}")

    # ---- Stage B: full sweep (landmark combos + C++ comparison) -----------
    lm_combos = [(o, s) for o in ("RGB", "BGR")
                 for s in ("div255", "raw", "pm1")]
    results = []
    print(f"\n[stage B] sweep on {len(images)} images")
    print(f"{'combo':>56} | {'found':>9} | {'pairs':>5} | {'mean|dxy|':>9} "
          f"{'max|dxy|':>8} | {'mean|dz|':>8} {'max|dz|':>7}")

    for pa in survivors:
        anchors = anchors_mp
        for lo, ls in lm_combos:
            combo = {"palm_order": pa["order"], "palm_scale": pa["scale"],
                     "letterbox": pa["letterbox"], "lm_order": lo,
                     "lm_scale": ls, "decode": "mp", "rect": "mp",
                     "flip_rot": flip_rot}
            results.append(evaluate(palm_model, lm_model, images, combo,
                                    anchors, lm_info, presence_idx))
            r = results[-1]
            print(f"{combo_key(combo):>56} | {r['found']:4d}/{len(images):<4d} | "
                  f"{r['pairs']:5d} | {r['dxy_mean']:9.5f} "
                  f"{r['dxy_max']:8.4f} | {r['dz_mean']:8.5f} "
                  f"{r['dz_max']:7.4f}")

    # C++-as-is comparison (wrong decode + wrong rect + BGR/255 input).
    cpp_combo = {"palm_order": "BGR", "palm_scale": "div255",
                 "letterbox": False, "lm_order": "BGR", "lm_scale": "div255",
                 "decode": "cpp", "rect": "cpp", "flip_rot": False}
    results.append(evaluate(palm_model, lm_model, images, cpp_combo,
                            anchors_cpp, lm_info, presence_idx=None))
    r = results[-1]
    print(f"{combo_key(cpp_combo):>56} | {r['found']:4d}/{len(images):<4d} | "
          f"{r['pairs']:5d} | {r['dxy_mean']:9.5f} {r['dxy_max']:8.4f} | "
          f"{r['dz_mean']:8.5f} {r['dz_max']:7.4f}")

    ranked = sorted([r for r in results if r["pairs"] > 0],
                    key=lambda r: (r["dxy_mean"], r["dz_mean"]))
    if not ranked:
        sys.exit("no combo produced a matched hand")
    winner = ranked[0]
    win = dict(winner["combo"])

    # Detection-rate parity on the FULL pool (includes ref-negative images).
    anchors_win = anchors_mp
    pool_found = 0
    for p in pool:
        entry = ref_data.get(str(p))
        if not entry:
            continue
        img = cv2.imread(str(p), cv2.IMREAD_COLOR)
        if img is None:
            continue
        hands = pipeline_hands(palm_model, lm_model, img,
                               img.astype(np.float64), win, anchors_win,
                               lm_info, presence_idx)
        pool_found += 1 if hands else 0
    pool_ref_rate = 100.0 * n_ref_any / n_pool
    pool_ours_rate = 100.0 * pool_found / n_pool
    pos_rate = 100.0 * winner["found"] / len(images)

    targets = {"dxy_mean": 0.01, "dz_mean": 0.05, "rate_pp": 5.0}
    pool_delta = pool_ours_rate - pool_ref_rate
    verdict = "PASS" if (winner["dxy_mean"] <= targets["dxy_mean"]
                         and winner["dz_mean"] <= targets["dz_mean"]
                         and abs(pool_delta) <= targets["rate_pp"]
                         ) else "NEEDS-WORK"

    print("\n===== SUMMARY =====")
    print(f"winner : {combo_key(win)} (flip_rot={win['flip_rot']})")
    print(f"dxy    : mean={winner['dxy_mean']:.5f} (target<=0.01)  "
          f"max={winner['dxy_max']:.4f}  pairs={winner['pairs']}")
    print(f"dz     : mean={winner['dz_mean']:.5f} (target<=0.05)  "
          f"max={winner['dz_max']:.4f}")
    print(f"found  : {winner['found']}/{len(images)} ref-positive "
          f"({pos_rate:.1f}%); full pool {pool_ours_rate:.1f}% vs ref "
          f"{pool_ref_rate:.1f}% (delta {pool_delta:+.1f}pp)")

    floor = None
    if args.floor_task:
        floor = measure_floor(images, args.floor_task)
        print(f"floor  : official Tasks runtime vs reference on the same "
              f"{len(images)} images: mean|dxy|={floor['dxy_mean']:.5f} "
              f"(max {floor['dxy_max']:.4f}), mean|dz|={floor['dz_mean']:.5f}, "
              f"found {floor['found']}/{len(images)}")
    print(f"VERDICT: {verdict}")

    write_report(results, winner, verdict, targets, images, pool_ref_rate,
                 pool_ours_rate, pos_rate, stage_a, provenance, palm_model,
                 lm_model, lm_info, presence_idx, cal, flip_rot, n_ref_any,
                 n_pool, floor)
    write_golden(win, anchors_mp, images, palm_model, lm_model, lm_info,
                 presence_idx, flip_rot, ref_data)


def evaluate(palm_model, lm_model, images, combo, anchors, lm_info,
             presence_idx):
    ours_found = 0
    dxy_all, dz_all, per_image = [], [], {}
    for p, img, rh in images:
        hands = pipeline_hands(palm_model, lm_model, img,
                               img.astype(np.float64), combo, anchors, lm_info,
                               presence_idx)
        if hands:
            ours_found += 1
        pairs = match_hands(hands, rh)
        if pairs:
            ds = []
            for r_h, o_h, _ in pairs:
                e = np.hypot(o_h[:, 0] - r_h[:, 0], o_h[:, 1] - r_h[:, 1])
                z = np.abs(o_h[:, 2] - r_h[:, 2])
                dxy_all.extend(e.tolist())
                dz_all.extend(z.tolist())
                ds.append(float(np.mean(e)))
            per_image[str(p)] = float(np.mean(ds))
    return {"combo": combo, "found": ours_found,
            "dxy_mean": float(np.mean(dxy_all)) if dxy_all else float("nan"),
            "dxy_max": float(np.max(dxy_all)) if dxy_all else float("nan"),
            "dz_mean": float(np.mean(dz_all)) if dz_all else float("nan"),
            "dz_max": float(np.max(dz_all)) if dz_all else float("nan"),
            "pairs": len(dxy_all) // NUM_LANDMARKS,
            "per_image": per_image}


# ---------------------------------------------------------------------------
# Report + fixtures
# ---------------------------------------------------------------------------

DIV_LABEL = {"div255": "255.0f (feed [0,1])",
             "raw": "1.0f (feed 0..255)",
             "pm1": "127.5f plus a -1.0f offset (feed [-1,1])"}


def write_report(results, winner, verdict, targets, images, pool_ref_rate,
                 pool_ours_rate, pos_rate, stage_a, provenance, palm_model,
                 lm_model, lm_info, presence_idx, cal, flip_rot, n_ref_any,
                 n_pool, floor=None):
    win = winner["combo"]
    mp_ver = "?"
    try:
        import mediapipe
        mp_ver = mediapipe.__version__
    except Exception:
        pass
    import onnxruntime
    lines = []
    a = lines.append
    a("# Hand-pipeline fidelity report\n")
    a(f"Generated by `scripts/sign/fidelity_check.py` (reference: mediapipe "
      f"{mp_ver} solutions.hands static mode, model_complexity=1; inference: "
      f"onnxruntime {onnxruntime.__version__} on Agent C's ONNX conversions).\n")
    a(f"Image set: {len(images)} ARSL images with a reference hand (from a "
      f"{n_pool}-image pool spanning all 28 letters; {n_ref_any} pool images "
      f"had a reference hand).\n")
    a("\nMethod note: the decode/rect semantics were not guessed - they were "
      "extracted from the official MediaPipe 0.10.14 runtime (live task graph "
      "via TaskRunner.get_graph_config(), model injection, internal stream "
      "observation: input tensor floats, pre/post-NMS detections, "
      "DetectionsToRects/RectTransformation outputs) and diffed against this "
      "mirror. See the harness docstring for the verified semantics.\n")

    a("\n## Winning configuration\n")
    a("```")
    a(f"palm  input : {win['palm_order']} + {win['palm_scale']}"
      + (" + letterbox" if win.get("letterbox") else " + stretch resize"))
    a(f"lm    input : {win['lm_order']} + {win['lm_scale']}")
    a(f"palm decode: {win['decode']} (verified MediaPipe semantics)")
    a(f"rect mode  : {win['rect']} (verified MediaPipe semantics, "
      f"crop rotation flip={flip_rot})")
    a("```\n")

    a("\n## Agreement numbers (winning config)\n")
    a("| metric | value | target |")
    a("|---|---|---|")
    a(f"| mean &#124;Δxy&#124; | {winner['dxy_mean']:.5f} | <= 0.01 |")
    a(f"| max &#124;Δxy&#124; | {winner['dxy_max']:.4f} | — |")
    a(f"| mean &#124;Δz&#124; | {winner['dz_mean']:.5f} | <= 0.05 |")
    a(f"| max &#124;Δz&#124; | {winner['dz_max']:.4f} | — |")
    a(f"| matched hands | {winner['pairs']} | — |")
    a(f"| hand-found rate, ref-positive set | {pos_rate:.1f}% (ref 100%) | — |")
    pool_delta = pool_ours_rate - pool_ref_rate
    a(f"| hand-found rate, full pool (n={n_pool}) | {pool_ours_rate:.1f}% vs "
      f"ref {pool_ref_rate:.1f}% ({pool_delta:+.1f}pp) | within 5pp |")
    if floor:
        a(f"| **official-runtime floor** (mediapipe Tasks with the same task "
          f"tflites vs the same reference) | mean&#124;Δxy&#124; "
          f"{floor['dxy_mean']:.5f}, mean&#124;Δz&#124; "
          f"{floor['dz_mean']:.5f}, found {floor['found']}/{len(images)} | "
          f"— |")
    a(f"\n**Verdict: {verdict}** (targets: mean&#124;Δxy&#124;<=0.01, "
      f"mean&#124;Δz&#124;<=0.05, found-rate delta within 5pp)")
    if floor:
        at_floor = winner["dxy_mean"] <= floor["dxy_mean"] * 1.15
        a(f"\nThe mean&#124;Δxy&#124; target of 0.01 is BELOW the measured "
          f"official-runtime floor ({floor['dxy_mean']:.5f}): the reference "
          f"pipeline (mediapipe.solutions, bundled tflite variants) and these "
          f"task models are not weight-identical, so no mirror of the task "
          f"models can reach 0.01 against this reference. "
          + ("The winning config operates **at the floor** (within 15%)."
             if at_floor else
             "The winning config is still above the floor - see worst cases.")
          + "\n")

    a("\n## Calibration\n")
    a("```json")
    a(json.dumps(cal, indent=1))
    a("```\n")
    a(f"Presence gate: output index `{presence_idx}` (sigmoid >= 0.5; index "
      "None = no gate applied). This matches the hand_landmark graph's "
      "tensor order [landmarks, presence, handedness, world]: Identity_1 = "
      "presence, Identity_2 = handedness (post-sigmoid probabilities).\n")

    a("\n## Full sweep\n")
    a("| combo | found | pairs | mean&#124;Δxy&#124; | max&#124;Δxy&#124; | "
      "mean&#124;Δz&#124; | max&#124;Δz&#124; |")
    a("|---|---|---|---|---|---|---|")
    for r in sorted(results, key=lambda r: (1e9 if r["pairs"] == 0
                                            else r["dxy_mean"])):
        a(f"| {combo_key(r['combo'])} | {r['found']}/{len(images)} | "
          f"{r['pairs']} | {r['dxy_mean']:.5f} | {r['dxy_max']:.4f} | "
          f"{r['dz_mean']:.5f} | {r['dz_max']:.4f} |")

    a("\n## Stage A: palm preprocessing\n")
    a("| palm input | probe detections | kp0/kp2 err (proxy) |")
    a("|---|---|---|")
    for s in stage_a:
        err = f"{s['kp_err']:.4f}" if not math.isnan(s["kp_err"]) else "n/a"
        a(f"| {s['order']}/{s['scale']}"
          f"{'/letterbox' if s['letterbox'] else ''} | {s['n_dets']} | "
          f"{err} |")

    worst = sorted(winner["per_image"].items(), key=lambda kv: -kv[1])[:5]
    a("\n## Worst images (winning config, mean Δxy per image)\n")
    a("| image | mean Δxy |")
    a("|---|---|")
    for path, e in worst:
        a(f"| `{Path(path).parent.name}/{Path(path).name}` | {e:.4f} |")

    a("\n## Recommendation: C++ constants\n")
    dummy = palm_model.run(np.zeros((PALM_INPUT, PALM_INPUT, 3), np.float32))
    s_idx, r_idx = palm_output_indices(dummy)
    a("```")
    a(f"kPalmValueDivisor     = {DIV_LABEL[win['palm_scale']]}")
    a(f"kPalmChannelSwap      = "
      f"{'swap to RGB' if win['palm_order'] == 'RGB' else 'none (keep BGR)'}")
    a(f"kLandmarkValueDivisor = {DIV_LABEL[win['lm_scale']]}")
    a(f"kLandmarkChannelSwap  = "
      f"{'swap to RGB' if win['lm_order'] == 'RGB' else 'none (keep BGR)'}")
    a(f"scores output index   = {s_idx} ({palm_model.output_names[s_idx]}, "
      f"shape {palm_model.output_shapes[s_idx]})")
    a(f"boxes output index    = {r_idx} ({palm_model.output_names[r_idx]}, "
      f"shape {palm_model.output_shapes[r_idx]})")
    scal_str = ", ".join(f"[{i}] {lm_model.output_names[i]}"
                         for i in lm_info.get("scalars", []))
    a(f"landmark output indexes = landmarks [{lm_info['landmarks']}] "
      f"({lm_model.output_names[lm_info['landmarks']]}); presence "
      f"[{presence_idx}]; world {lm_info.get('world')}; scalars: {scal_str}")
    a("```\n")
    a("Decode semantics required for these indices (all verified against the "
      "official runtime):\n")
    a("```")
    a("anchors : rows 0..1151 = 24x24 grid (stride 8), y-major, 2/cell;")
    a("          rows 1152..2015 = 12x12 grid (stride 16), y-major, 6/cell")
    a("          (the three stride-16 layers are cell-interleaved);")
    a("          fixed_anchor_size -> every anchor w=h=1.0")
    a("box     : cx=r0/192+ax, cy=r1/192+ay, w=r2/192, h=r3/192 (x-first)")
    a(f"kps     : (r[4+2k]/192+ax, r[5+2k]/192+ay) (x-first)")
    a("scores  : sigmoid(clip(raw,+-100)), threshold 0.5, NMS IoU 0.3")
    a("rect    : center=box center; rot = 63.3798deg - atan2(-dy_px, dx_px)")
    a("          with dx,dy = kp2-kp0 scaled to pixels;")
    a("          side_px = max(2.6*w0*W, 2.6*h0*H) (square_long in pixels);")
    a("          center += (0.5*h0px*sin(rot), -0.5*h0px*cos(rot))")
    a("landmarks: x=raw/224, y=raw/224, z=raw/(224*0.4); inverse-project")
    a("          through the crop rect")
    a("```")

    a("\n## Deviations of the current C++ from verified semantics\n")
    a("1. **Output order**: C++ reads scores from outputs[0] and boxes from "
      "outputs[1]; the ONNX conversion emits regressions first "
      "(Identity=[1,2016,18]) and scores second (Identity_1=[1,2016,1]).")
    a("2. **Channel order**: C++ builds BGR input planes; the model wants "
      "RGB (official tensor == RGB/255 to 7e-8; BGR/[0,1] finds almost "
      "nothing).")
    a("3. **Anchor table**: C++ sizes anchors with the 0.148..0.75 scale "
      "pairs and lays the three stride-16 layers out as sequential blocks; "
      "the verified table uses w=h=1.0 everywhere and interleaves the three "
      "stride-16 layers per cell (6 anchors/cell, rows 1152..2015).")
    a("4. **Decode**: C++ applies regressions in anchor-size units "
      "(`r[0]*a.w + a.cx`); the verified decode divides raw by 192 and adds "
      "the anchor center.")
    a("5. **Hand rect**: C++ centers the crop on the kp0/kp2 midpoint with "
      "side 2.6*kp-distance (min 32) and no shift; the verified rect uses "
      "the detection box center, pixel-space square_long of 2.6x box "
      "dimensions, and the -0.5*h shift along the rotated rect axis, with "
      "rotation 63.3798deg - atan2(-dy,dx) (the C++ angle formula differs "
      "and misses the pixel-aspect scaling and the deployed wheel's constant "
      "phase).")
    a("6. **Letterbox**: MediaPipe letterboxes the palm input "
      "(keep_aspect_ratio); the C++ (and this mirror by default) "
      "stretch-resizes. The letterbox variant is measured in the sweep "
      "above.")
    a("7. **NMS**: MediaPipe uses the WEIGHTED NMS algorithm; the C++ (and "
      "this mirror) use greedy suppression at the same 0.3 IoU. Effect is "
      "small (only affects merged duplicate detections).")
    a("8. **Crop border**: MediaPipe's landmark crop uses the default border "
      "mode (replicate-ish via warpPerspective constant default) while the "
      "C++ mirrors BORDER_CONSTANT zero; impact is limited to crops "
      "extending past the frame.")
    a("9. **Landmark normalization**: `landmarksForCrop()` multiplies the "
      "raw landmark coordinates by `sizePx` directly (`aroundX = nx * "
      "sizePx`), but the model emits 224-crop pixel units - the raw values "
      "must be divided by 224 first (graph semantics x=raw/224). Without "
      "the /224 the inverse projection produces coordinates ~224x too large.")

    if provenance:
        a("\n## PROVENANCE.json (Agent C)\n")
        a("```json")
        a(json.dumps(provenance, indent=2)[:4000])
        a("```")

    REPORT_MD.write_text("\n".join(lines) + "\n")
    print(f"[report] wrote {REPORT_MD}")


def write_golden(win, anchors, images, palm_model, lm_model, lm_info,
                 presence_idx, flip_rot, ref_data):
    golden = {
        "description": "Golden fixtures for a C++ hand-pipeline test. "
                       "reference = mediapipe.solutions.hands (static, "
                       "complexity 1); ours = winning harness config. "
                       "Coordinates are image-normalized; z uses "
                       "z=raw/(224*0.4).",
        "winning_config": {
            "palm": {"order": win["palm_order"], "scale": win["palm_scale"],
                     "letterbox": bool(win.get("letterbox", False)),
                     "decode": win["decode"]},
            "landmark": {"order": win["lm_order"], "scale": win["lm_scale"],
                         "flip_rot": bool(flip_rot)}},
        "constants": {
            "palm_input": PALM_INPUT, "lm_input": LM_INPUT,
            "num_anchors": NUM_ANCHORS, "num_coords": NUM_COORDS,
            "anchor_w_h": 1.0, "decode_divisor": 192.0,
            "nms_iou": NMS_IOU, "palm_threshold": PALM_THRESHOLD,
            "sigmoid_clip": 100.0, "z_divisor": Z_DIVISOR,
            "rect_scale": 2.6, "rect_shift_y": -0.5,
            "rotation_base_deg": ROT_BASE_DEG,
            "presence_output_index": presence_idx,
            "palm_scores_output": 1, "palm_boxes_output": 0,
            "landmarks_output": lm_info["landmarks"]},
        "anchor_spot_values": {},
        "images": []}

    for i in [0, 1, 2, 575, 576, 1151, 1152, 1153, 1242, 1268, 1439, 1440,
              2015]:
        a = anchors[i]
        golden["anchor_spot_values"][str(i)] = {
            "cx": round(float(a[0]), 9), "cy": round(float(a[1]), 9),
            "w": float(a[2]), "h": float(a[3])}

    n_written = 0
    for p, img, rh in images:
        if n_written >= 5:
            break
        entry = ref_data.get(str(p), {})
        if not entry.get("hands"):
            continue
        img_f = img.astype(np.float64)
        hands = pipeline_hands(palm_model, lm_model, img, img_f, win, anchors,
                               lm_info, presence_idx)
        if not hands or not match_hands(hands, rh):
            continue
        dets = run_palm_detections(palm_model, img, win["palm_order"],
                                   win["palm_scale"], anchors, win["decode"],
                                   win.get("letterbox", False))
        g = {"path": str(p), "name": p.name, "letter": p.parent.name,
             "w": img.shape[1], "h": img.shape[0],
             "palm_top": None,
             "reference": [[[round(c, 6) for c in pt] for pt in hand]
                           for hand in entry["hands"]],
             "ours": [[[round(float(c), 6) for c in pt] for pt in hand]
                      for hand in hands]}
        if dets:
            det = dets[0]
            cx, cy, side_x, rot = rect_from_palm_mp(det["box"], det["kps"],
                                                    img.shape[1],
                                                    img.shape[0])
            g["palm_top"] = {
                "score": round(det["score"], 6),
                "box": [round(v, 6) for v in det["box"]],
                "kps": [[round(a, 6), round(b, 6)] for a, b in det["kps"]],
                "rect": {"center": [round(cx, 6), round(cy, 6)],
                         "side_px": round(side_x * img.shape[1], 6),
                         "rot_deg": round(rot, 6)}}
        golden["images"].append(g)
        n_written += 1
    GOLDEN_JSON.write_text(json.dumps(golden, indent=1))
    print(f"[golden] wrote {GOLDEN_JSON} ({n_written} images)")


if __name__ == "__main__":
    main()

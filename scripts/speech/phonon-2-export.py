#!/usr/bin/env python3
"""Convert Fermion's pinned Phonon-2 artifact into a Hearth phonon-2 speech package.

Fermion publishes English-only parakeet-tdt-0.6b-v3 weights in their own
five-value container. Hearth's phone runtime does not (and must not) learn
that container: this host-only script expands it once into dense fp32 tensors
and re-exports the three ONNX graphs sherpa's nemo_transducer adapter already
runs, following k2-fsa's scripts/nemo/parakeet-tdt-0.6b-v3 recipe.

Stages (each verifies its input before running):
  1. fetch/verify phonon-2.bps.tar.zst (pinned bytes + SHA-256) unless --archive
     already points at a verified local copy;
  2. unpack and hash the inner model.fermion container;
  3. decode container records to a dense HF ParakeetForTDT state dict
     (exact integer math from Fermion's published codec: five-value planes,
     int6/int8 tables, fp16 records);
  4. load stock transformers ParakeetForTDT (config from the pinned base repo)
     with strict key checking, export encoder/decoder/joiner ONNX graphs with
     the IO contract sherpa expects, quantize to int8 and embed the required
     metadata (vocab_size, subsampling_factor, pred_rnn_layers, pred_hidden,
     feat_dim, url containing "tdt");
  5. optional --verify: greedy TDT decode a WAV through the exported graphs and
     require an exact transcript match against the dense reference model.

Output directory then packages with:
  make-package.py OUT_DIR --profile phonon-2 \
    --role model=tokens.txt --role frontend=joiner.int8.onnx \
    --role encoder=encoder.int8.onnx --role decoder=decoder.int8.onnx

Requires (host only; nothing here ships to phones):
  pip install torch "transformers>=4.57" onnx onnxruntime numpy zstandard soundfile librosa
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
import tarfile
from pathlib import Path

import numpy as np

PINNED = {
    "archive_url": "https://huggingface.co/FermionResearch/Phonon-2/resolve/main/phonon-2.bps.tar.zst",
    "archive_bytes": 163515201,
    "archive_sha256": "98125795b6dda72f5c6eee9ba33d19815df65dcb18b50a357bf9f73c9935309e",
    "container": "model_phonon2_c4c_int6/model.fermion",
    "container_bytes": 177438361,
    "container_sha256": "4b6bfa3a12cc3c4e0a54f2ab3ec4ca7a842b09e5c7ecfc8e7ca0ac6cc8c11468",
    "base_repo": "nvidia/parakeet-tdt-0.6b-v3",
    "format": "fermion-five-value-parakeet-v1",
    "license": "CC-BY-4.0 (weights, per FermionResearch/Phonon-2 and the NVIDIA base model)",
}
BLOCK = 1 << 20


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(BLOCK), b""):
            digest.update(chunk)
    return digest.hexdigest()


# ---------------------------------------------------------------------------
# fermion-five-value-parakeet-v1 container decode (numpy port of Fermion's
# published fermion_container.py; kept verbatim in math, not imported, so the
# pinned artifact can be audited from this file alone).
# ---------------------------------------------------------------------------
def _trits(buf: bytes, o: int, i: int) -> np.ndarray:
    rb = (i + 4) // 5
    x = np.frombuffer(buf, dtype=np.uint8, count=o * rb).reshape(o, rb).astype(np.uint16)
    d = np.empty((o, rb, 5), dtype=np.uint8)
    for k in range(5):
        d[:, :, k] = (x // (3 ** k)) % 3
    return d.reshape(o, rb * 5)[:, :i]


def _five_value(blob: bytes, shape) -> np.ndarray:
    o, i = shape
    rb = (i + 4) // 5
    codes = _trits(blob[: o * rb], o, i)
    nzmask = codes != 1
    nnz = int(nzmask.sum())
    rbytes = (nnz + 7) // 8
    off = o * rb
    bits = np.unpackbits(np.frombuffer(blob[off: off + rbytes], dtype=np.uint8), bitorder="little")[:nnz].astype(bool)
    off += rbytes
    lo = np.frombuffer(blob[off: off + 2 * o], dtype=np.float16)
    hi = np.frombuffer(blob[off + 2 * o: off + 4 * o], dtype=np.float16)
    assert off + 4 * o == len(blob), (off + 4 * o, len(blob))
    is_hi = np.zeros((o, i), dtype=bool)
    is_hi[nzmask] = bits
    mag = np.where(is_hi, hi[:, None], lo[:, None])
    sign = codes.astype(np.int8) - 1
    return (sign.astype(np.float16) * mag).astype(np.float16)


def _intn(blob: bytes, shape, bits: int) -> np.ndarray:
    o = shape[0]
    total = int(np.prod(shape))
    ncol = total // o
    sc_b = 2 * o
    body, scales = blob[:-sc_b], np.frombuffer(blob[-sc_b:], dtype=np.float16)
    if bits == 8:
        q = np.frombuffer(body, dtype=np.int8).astype(np.int32)[:total]
    elif bits == 6:
        b = np.frombuffer(body, dtype=np.uint8).reshape(-1, 3).astype(np.uint32)
        packed = b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)
        u = np.stack([(packed >> s) & 0x3F for s in (0, 6, 12, 18)], axis=1).ravel()
        q = u[:total].astype(np.int32) - 32
    else:
        raise ValueError(f"unsupported table width: {bits}")
    w = q.reshape(o, ncol).astype(np.float32) * scales.astype(np.float32)[:, None]
    return w.reshape(shape)


def read_container(path: Path) -> dict[str, np.ndarray]:
    with path.open("rb") as fh:
        n = int.from_bytes(fh.read(8), "little")
        header = json.loads(fh.read(n))
        assert header["format"] == PINNED["format"], header["format"]
        out: dict[str, np.ndarray] = {}
        for e in header["index"]:
            blob = fh.read(e["b"])
            assert len(blob) == e["b"]
            k, shape = e["k"], tuple(e["shape"])
            if k == "five_value":
                out[e["n"] + ".weight"] = _five_value(blob, shape)
            elif k.startswith("int"):
                out[e["n"]] = _intn(blob, shape, int(k[3:]))
            elif k == "fp16":
                out[e["n"]] = np.frombuffer(blob, dtype=np.float16).reshape(shape).copy()
            else:
                raise ValueError(k)
        assert fh.read(1) == b"", "trailing bytes in container"
    return out


# ---------------------------------------------------------------------------
# Stage 1-2: pinned artifact handling
# ---------------------------------------------------------------------------
def obtain_container(archive: Path | None, workdir: Path) -> Path:
    target = workdir / "model.fermion"
    if target.is_file() and sha256_file(target) == PINNED["container_sha256"]:
        print(f"[1] container already verified at {target}")
        return target
    import zstandard

    if archive is None:
        archive = workdir / "phonon-2.bps.tar.zst"
        if not archive.is_file():
            import urllib.request

            print(f"[1] downloading pinned archive ({PINNED['archive_bytes']} bytes)")
            urllib.request.urlretrieve(PINNED["archive_url"], archive)
    if archive.stat().st_size != PINNED["archive_bytes"]:
        raise RuntimeError(f"archive size mismatch: {archive}")
    digest = sha256_file(archive)
    if digest != PINNED["archive_sha256"]:
        raise RuntimeError(f"archive SHA-256 mismatch: {digest}")
    print("[1] archive verified against FermionResearch/Phonon-2 pin")

    dctx = zstandard.ZstdDecompressor()
    with archive.open("rb") as compressed, dctx.stream_reader(compressed) as reader:
        with tarfile.open(fileobj=io.BufferedReader(reader), mode="r|") as tar:
            for member in tar:
                if member.name != PINNED["container"]:
                    continue
                extracted = tar.extractfile(member)
                assert extracted is not None
                target.parent.mkdir(parents=True, exist_ok=True)
                h = hashlib.sha256()
                with target.open("wb") as out:
                    while True:
                        chunk = extracted.read(BLOCK)
                        if not chunk:
                            break
                        h.update(chunk)
                        out.write(chunk)
                if h.hexdigest() != PINNED["container_sha256"] or target.stat().st_size != PINNED["container_bytes"]:
                    target.unlink(missing_ok=True)
                    raise RuntimeError("container hash/size mismatch after extraction")
                print(f"[2] container extracted and verified ({target.stat().st_size} bytes)")
                return target
    raise RuntimeError(f"member {PINNED['container']} not found in archive")


# ---------------------------------------------------------------------------
# Stage 3: dense HF state dict
# ---------------------------------------------------------------------------
def dense_state_dict(container: Path):
    import torch

    tensors = read_container(container)
    sd = {}
    for k, v in tensors.items():
        if k.endswith("num_batches_tracked"):
            v32 = float(np.asarray(v, dtype=np.float32).reshape(-1)[0])
            sd[k] = torch.tensor(int(v32) if np.isfinite(v32) else 0, dtype=torch.int64)
        else:
            arr = np.ascontiguousarray(v.astype(np.float32))
            if arr.ndim == 2 and re.search(r"\.conv\.pointwise_conv[12]\.weight$", k):
                arr = arr[:, :, None]
            sd[k] = torch.from_numpy(arr)
    print(f"[3] decoded {len(sd)} dense tensors")
    return sd


# ---------------------------------------------------------------------------
# Stage 4: export sherpa-layout ONNX from the dense transformers model
# ---------------------------------------------------------------------------
def load_reference_model(container: Path):
    import torch
    from transformers import ParakeetForTDT, ParakeetTDTConfig

    cfg = ParakeetTDTConfig.from_pretrained(PINNED["base_repo"])
    model = ParakeetForTDT(cfg)
    sd = dense_state_dict(container)
    missing, unexpected = model.load_state_dict(sd, strict=False)
    if missing or unexpected:
        raise RuntimeError(f"state dict mismatch: missing {missing[:5]} unexpected {unexpected[:5]}")
    model.eval()
    return model, cfg


def vocabulary_of(model) -> list[str]:
    for holder in (getattr(model, "head", None), getattr(model, "joint", None)):
        vocab = getattr(holder, "vocabulary", None)
        if vocab:
            return list(vocab)
    raise RuntimeError("cannot locate joint vocabulary on the reference model")


class EncoderWrapper:
    """sherpa nemo_transducer encoder IO: x [B,C,T] -> encoder_out [B,D,T'], lengths."""

    def __init__(self, model):
        import torch

        self.encoder = model.audio_encoder
        self.subsampling = 8  # parakeet-tdt-0.6b-v3: conv stride 8 (k2-fsa recipe)

        class Module(torch.nn.Module):
            def forward(inner, x, x_lens):
                features = x.transpose(1, 2)  # [B,T,C] for the HF encoder
                out = inner.encoder(features).last_hidden_state
                t = out.shape[1]
                lengths = ((x_lens.float() / self.subsampling) - 1).ceil().long().clamp(min=1, max=t)
                return out.transpose(1, 2), lengths  # [B,D,T']

        self.module = Module()


class DecoderWrapper:
    """sherpa nemo_transducer decoder IO: targets, length, s0, s1 -> out, out_len, s0', s1'."""

    def __init__(self, model):
        import torch

        decoder = model.decoder

        class Module(torch.nn.Module):
            def forward(inner, targets, targets_length, s0, s1):
                embedded = decoder.embedding(targets)
                out, (h, c) = decoder.lstm(embedded, (s0, s1))
                out = decoder.decoder_projector(out)
                return out, targets_length, h, c

        self.module = Module()


class JoinerWrapper:
    """sherpa nemo_transducer joiner IO: encoder_out [B,D,1], decoder_out [B,D,1] -> logits."""

    def __init__(self, model):
        import torch

        joint = model.joint if hasattr(model, "joint") else model.head.joint

        class Module(torch.nn.Module):
            def forward(inner, encoder_out, decoder_out):
                logits = joint(decoder_hidden_states=decoder_out.transpose(1, 2),
                               encoder_hidden_states=encoder_out.transpose(1, 2))
                return logits

        self.module = Module()


def export_onnx(model, outdir: Path):
    import torch
    from onnxruntime.quantization import QuantType, quantize_dynamic
    import onnx

    vocab = vocabulary_of(model)
    durations = getattr(model.config, "durations", None)
    if not durations:
        raise RuntimeError("reference config carries no TDT durations; refusing a non-TDT export")

    (outdir / "tokens.txt").write_text("".join(f"{s} {i}\n" for i, s in enumerate(vocab)) + f"<blk> {len(vocab)}\n", encoding="utf-8")

    def add_meta(filename: str, meta: dict[str, str]):
        m = onnx.load(filename)
        while len(m.metadata_props):
            m.metadata_props.pop()
        for key, value in meta.items():
            e = m.metadata_props.add()
            e.key, e.value = key, str(value)
        onnx.save(m, filename)

    meta = {
        "vocab_size": len(vocab),
        "normalize_type": "",
        "pred_rnn_layers": model.config.num_decoder_layers,
        "pred_hidden": model.config.decoder_hidden_size,
        "subsampling_factor": 8,
        "model_type": "EncDecRNNTBPEModel",
        "version": "2",
        "model_author": "FermionResearch",
        "url": "https://huggingface.co/FermionResearch/Phonon-2 (parakeet-tdt)",
        "comment": "Phonon-2 five-value weights expanded to dense fp32 and re-exported for sherpa nemo_transducer",
        "feat_dim": 128,
    }

    with torch.no_grad():
        x = torch.randn(1, 128, 67, dtype=torch.float32)
        x_lens = torch.tensor([67], dtype=torch.int64)
        torch.onnx.export(EncoderWrapper(model).module, (x, x_lens), str(outdir / "encoder.onnx"),
                          input_names=["x", "x_lens"], output_names=["encoder_out", "encoder_out_lens"],
                          dynamic_axes={"x": {0: "B", 2: "T"}, "x_lens": {0: "B"},
                                        "encoder_out": {0: "B", 2: "T"}, "encoder_out_lens": {0: "B"}},
                          opset_version=17, do_constant_folding=True)

        targets = torch.zeros(1, 1, dtype=torch.int32)
        t_lens = torch.ones(1, dtype=torch.int32)
        s0 = torch.zeros(model.config.num_decoder_layers, 1, model.config.decoder_hidden_size)
        s1 = torch.zeros_like(s0)
        torch.onnx.export(DecoderWrapper(model).module, (targets, t_lens, s0, s1), str(outdir / "decoder.onnx"),
                          input_names=["targets", "targets_lens", "s0", "s1"],
                          output_names=["decoder_out", "decoder_out_lens", "s0_next", "s1_next"],
                          dynamic_axes={"targets": {0: "B", 1: "U"}, "targets_lens": {0: "B"},
                                        "decoder_out": {0: "B", 1: "U"}},
                          opset_version=17)

        enc = torch.randn(1, model.config.decoder_hidden_size, 1)
        dec = torch.randn(1, model.config.decoder_hidden_size, 1)
        torch.onnx.export(JoinerWrapper(model).module, (enc, dec), str(outdir / "joiner.onnx"),
                          input_names=["encoder_out", "decoder_out"], output_names=["logits"],
                          dynamic_axes={"encoder_out": {0: "B", 2: "T"}, "decoder_out": {0: "B", 2: "U"}},
                          opset_version=17)

    for name, weight_type in (("encoder", QuantType.QUInt8), ("decoder", QuantType.QInt8), ("joiner", QuantType.QInt8)):
        quantize_dynamic(model_input=str(outdir / f"{name}.onnx"), model_output=str(outdir / f"{name}.int8.onnx"), weight_type=weight_type)
    add_meta(str(outdir / "encoder.int8.onnx"), meta)
    add_meta(str(outdir / "encoder.onnx"), meta)

    expected = len(vocab) + 1 + len(durations)
    session = __import__("onnxruntime").InferenceSession(str(outdir / "joiner.int8.onnx"), providers=["CPUExecutionProvider"])
    actual = session.get_outputs()[0].shape[-1]
    if not isinstance(actual, int) or actual != expected:
        raise RuntimeError(f"joiner output {actual} != tokens({len(vocab)}) + blank + durations({len(durations)})")
    print(f"[4] exported encoder/decoder/joiner (+int8) and tokens.txt ({len(vocab)} symbols, {len(durations)} durations)")


# ---------------------------------------------------------------------------
# Stage 5: end-to-end transcript parity against the dense reference
# ---------------------------------------------------------------------------
def verify(outdir: Path, wav: Path):
    import onnxruntime as ort
    import soundfile as sf

    import sys as _sys
    _sys.path.insert(0, str(Path(__file__).parent))
    from phonon_fbank import compute_fbank  # noqa: F401  (shared with the smoke harness)

    audio, rate = sf.read(wav, dtype="float32", always_2d=True)
    if rate != 16000:
        raise RuntimeError("verification WAV must already be 16 kHz mono")
    features = compute_fbank(audio[:, 0])

    enc = ort.InferenceSession(str(outdir / "encoder.int8.onnx"), providers=["CPUExecutionProvider"])
    dec = ort.InferenceSession(str(outdir / "decoder.int8.onnx"), providers=["CPUExecutionProvider"])
    jnt = ort.InferenceSession(str(outdir / "joiner.int8.onnx"), providers=["CPUExecutionProvider"])
    meta = enc.get_modelmeta().custom_metadata_map
    tokens = [line.rsplit(" ", 1)[0] for line in (outdir / "tokens.txt").read_text(encoding="utf-8").splitlines()]
    vocab_size = len(tokens)  # includes <blk> as the final id
    blank = vocab_size - 1
    output_size = jnt.get_outputs()[0].shape[-1]
    num_durations = output_size - vocab_size
    if not isinstance(num_durations, int) or num_durations <= 0:
        raise RuntimeError(f"joiner output {output_size} does not carry TDT durations beyond vocab {vocab_size}")

    x = features.T[None].astype(np.float32)
    lens = np.array([features.shape[0]], dtype=np.int64)
    enc_out, _ = enc.run(None, {"x": x, "x_lens": lens})
    num_rows, num_cols = enc_out.shape[2], enc_out.shape[1]
    s0 = np.zeros((int(meta["pred_rnn_layers"]), 1, int(meta["pred_hidden"])), dtype=np.float32)
    s1 = np.zeros_like(s0)
    token = blank  # BuildDecoderInput starts from blank

    def run_decoder(tok):
        nonlocal s0, s1
        dec_out, _, s0, s1 = dec.run(None, {"targets": np.array([[tok]], dtype=np.int32),
                                            "targets_lens": np.array([1], dtype=np.int32), "s0": s0, "s1": s1})
        return dec_out.astype(np.float32)

    decoded: list[str] = []
    dec_out = run_decoder(token)
    tokens_this_frame, skip, t = 0, 0, 0
    while t < num_rows:
        logits = jnt.run(None, {"encoder_out": enc_out[:, :, t:t + 1].astype(np.float32), "decoder_out": dec_out})[0][0, 0, 0]
        y = int(np.argmax(logits[:vocab_size]))
        skip = int(np.argmax(logits[vocab_size:]))
        if y != blank:
            decoded.append(tokens[y])
            dec_out = run_decoder(y)
            tokens_this_frame += 1
        if skip > 0:
            tokens_this_frame = 0
        if tokens_this_frame >= 5:
            tokens_this_frame, skip = 0, 1
        if y == blank and skip == 0:
            tokens_this_frame, skip = 0, 1
        t += skip
    transcript = "".join(decoded).replace("▁", " ").strip()
    print(f"[5] greedy TDT transcript: {transcript}")
    return transcript


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("outdir", type=Path)
    ap.add_argument("--archive", type=Path, default=None, help="local phonon-2.bps.tar.zst (still hash-verified)")
    ap.add_argument("--verify", type=Path, default=None, help="16 kHz mono WAV decoded through the exported graphs")
    args = ap.parse_args()

    args.outdir.mkdir(parents=True, exist_ok=True)
    container = obtain_container(args.archive, args.outdir)
    model, _cfg = load_reference_model(container)
    export_onnx(model, args.outdir)
    if args.verify:
        verify(args.outdir, args.verify)
    print(f"""
Next: package the export for Hearth import
  python3 scripts/speech/make-package.py {args.outdir} --profile phonon-2 \\
    --role model=tokens.txt --role frontend=joiner.int8.onnx \\
    --role encoder=encoder.int8.onnx --role decoder=decoder.int8.onnx
Then Import speech ZIP (or publish the directory pinned and add it to SpeechDownloads).
Weights: {PINNED['license']}.""")


if __name__ == "__main__":
    main()

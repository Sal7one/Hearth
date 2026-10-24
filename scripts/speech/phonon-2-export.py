#!/usr/bin/env python3
"""Convert pinned Phonon-2 / multilingual Phonon-2.5 weights into a Hearth ONNX package.

Fermion publishes Parakeet-TDT weights in its own five-value container.
The default phonon-2 profile is English-only; phonon-2.5 supports its 25 published
European languages. The packed 156 MB download is not the Android ONNX size. Hearth's phone runtime does not (and must not) learn
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
  5. optional --verify: diagnostic greedy TDT decode of a WAV through the exported
     graphs. This is not a native-runtime test or an assertion of reference parity;
     validate packaged graphs separately through Hearth's production speech backend.

Output directory then packages with:
  make-package.py OUT_DIR --profile phonon-2 \
    --role model=tokens.txt --role frontend=joiner.int8.onnx \
    --role encoder=encoder.int8.onnx --role decoder=decoder.int8.onnx

Requires (host only; nothing here ships to phones):
  pip install -r scripts/speech/requirements-phonon-export.txt
  (Parakeet TDT needs Transformers 5.12.1 here; earlier CTC-only releases do not suffice.)
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
    "profile": "phonon-2",
    "repository": "FermionResearch/Phonon-2",
    "revision": "3e6ab3a81d73b4d424ff34cc72535deb606378ef",
    "archive_name": "phonon-2.bps.tar.zst",
    "archive_url": "https://huggingface.co/FermionResearch/Phonon-2/resolve/3e6ab3a81d73b4d424ff34cc72535deb606378ef/phonon-2.bps.tar.zst",
    "languages": ["en"],
    "archive_bytes": 163515201,
    "archive_sha256": "98125795b6dda72f5c6eee9ba33d19815df65dcb18b50a357bf9f73c9935309e",
    "container": "model.fermion",
    "container_bytes": 177438361,
    "container_sha256": "4b6bfa3a12cc3c4e0a54f2ab3ec4ca7a842b09e5c7ecfc8e7ca0ac6cc8c11468",
    "base_repo": "nvidia/parakeet-tdt-0.6b-v3",
    "format": "fermion-five-value-parakeet-v1",
    "license": "CC-BY-4.0 (weights, per FermionResearch/Phonon-2 and the NVIDIA base model)",
}
PHONON_25 = {
    **PINNED,
    "profile": "phonon-2.5",
    "repository": "FermionResearch/Phonon-2.5",
    "revision": "96dad4f3b5b784b1aa595213ea7481bf8a7addcd",
    "archive_name": "phonon-2.5.bps.tar.zst",
    "archive_url": "https://huggingface.co/FermionResearch/Phonon-2.5/resolve/96dad4f3b5b784b1aa595213ea7481bf8a7addcd/phonon-2.5.bps.tar.zst",
    "archive_bytes": 162587031,
    "archive_sha256": "1faa04053a472cabab3bc31d07f30c23f22906f71044ca10eb1f0de36d1f19a9",
    "container_bytes": 177593644,
    "container_sha256": "17cb62d08e751a41819eb779db089aa7ac4eb2bab433ba5c2991d8cbd3cfefb9",
    "unpack_dir": "model_phonon25_f25x1_48k_int6",
    "languages": "en es fr de it pt nl pl ru uk ro cs sk bg hr sv da fi et lv lt hu sl mt el".split(),
    "source_files": {
        "NOTICE": (2664, "dd4993badd45eb336280a0ab5b4ecb3ed06260d189a30fcae8b9e89a587c4de8"),
        "LICENSE-WEIGHTS-CC-BY-4.0.txt": (18657, "9ba9550ad48438d0836ddab3da480b3b69ffa0aac7b7878b5a0039e7ab429411"),
    },
    "license": "CC-BY-4.0 (weights, per FermionResearch/Phonon-2.5 and the NVIDIA base model)",
}
PROFILES = {"phonon-2": PINNED, "phonon-2.5": PHONON_25}
BASE_REVISION = "541d1f99c6b0c3cd0b11a95167540bb8edefd82b"
BASE_FILES = {
    "config.json": (1153, "e747b85e1bdfd300c8b8ac63bac8dd5221f8fe9bc275b48d06c735fcd6971b6e"),
    "generation_config.json": (289, "b141de6ec6d7f982ece13f98f604e3fe1807ea9c0e839185d0ab7064604209d0"),
    "processor_config.json": (392, "8346a93a3b987fa1dec57a78f045cd0817d21786589a5a096b41a57a446fd1d7"),
    "tokenizer.json": (1159960, "bd321b096832a3f270bd3b2a88823957920f1a5c5ada71114a26ea729d0cbe91"),
    "tokenizer_config.json": (290, "0b2fe0037599ee335f0b972fa682bf0ece74e4ccfec755cb7daa3405d3d3e874"),
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


def read_container(path: Path, pins=PINNED) -> dict[str, np.ndarray]:
    with path.open("rb") as fh:
        n = int.from_bytes(fh.read(8), "little")
        header = json.loads(fh.read(n))
        assert header["format"] == pins["format"], header["format"]
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
def verified_download(url: str, destination: Path, size: int, sha256: str) -> Path:
    """No model-repository code is imported; only exact pinned bytes become reusable input."""
    if destination.is_file():
        if destination.stat().st_size != size or sha256_file(destination) != sha256:
            raise RuntimeError(f"pinned file hash/size mismatch: {destination}")
        return destination
    import urllib.request

    temporary = destination.with_name(destination.name + ".partial")
    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        urllib.request.urlretrieve(url, temporary)
        if temporary.stat().st_size != size or sha256_file(temporary) != sha256:
            raise RuntimeError(f"download hash/size mismatch: {url}")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def obtain_base(workdir: Path, pins=PINNED) -> Path:
    """A local, hash-verified config/tokenizer snapshot; Transformers never resolves mutable main."""
    root = workdir / ("base-" + BASE_REVISION)
    for filename, (size, digest) in BASE_FILES.items():
        verified_download(f"https://huggingface.co/{pins['base_repo']}/resolve/{BASE_REVISION}/{filename}",
                          root / filename, size, digest)
    return root


def obtain_container(archive: Path | None, workdir: Path, pins=PINNED) -> Path:
    target = workdir / "model.fermion"
    if target.is_file() and target.stat().st_size == pins["container_bytes"] and sha256_file(target) == pins["container_sha256"]:
        print(f"[1] container already verified at {target}", flush=True)
        return target
    import zstandard

    if archive is None:
        archive = verified_download(pins["archive_url"], workdir / pins["archive_name"],
                                    pins["archive_bytes"], pins["archive_sha256"])
    if archive.stat().st_size != pins["archive_bytes"] or sha256_file(archive) != pins["archive_sha256"]:
        raise RuntimeError(f"archive hash/size mismatch: {archive}")
    print(f"[1] archive verified against {pins['repository']} at {pins['revision']}", flush=True)

    accepted = {pins["container"]}
    if pins.get("unpack_dir"):
        accepted.add(pins["unpack_dir"] + "/" + pins["container"])
    temporary = target.with_name(target.name + ".partial")
    dctx = zstandard.ZstdDecompressor()
    try:
        with archive.open("rb") as compressed, dctx.stream_reader(compressed) as reader:
            with tarfile.open(fileobj=io.BufferedReader(reader), mode="r|") as tar:
                for member in tar:
                    if member.name not in accepted:
                        continue
                    if not member.isfile() or member.size != pins["container_bytes"]:
                        raise RuntimeError("container must be a regular archive member with its pinned size")
                    extracted = tar.extractfile(member)
                    if extracted is None:
                        raise RuntimeError("container cannot be read")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with temporary.open("wb") as out:
                        while chunk := extracted.read(BLOCK):
                            out.write(chunk)
                    if temporary.stat().st_size != pins["container_bytes"] or sha256_file(temporary) != pins["container_sha256"]:
                        raise RuntimeError("container hash/size mismatch after extraction")
                    temporary.replace(target)
                    print(f"[2] container extracted and verified ({target.stat().st_size} bytes)", flush=True)
                    return target
    finally:
        temporary.unlink(missing_ok=True)
    raise RuntimeError(f"member {pins['container']} not found in archive")


# ---------------------------------------------------------------------------
# Stage 3: dense HF state dict
# ---------------------------------------------------------------------------
def dense_state_dict(container: Path, pins=PINNED):
    import torch

    tensors = read_container(container, pins)
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
def load_reference_model(container: Path, base: Path, pins=PINNED):
    import torch
    from transformers import ParakeetForTDT, ParakeetTDTConfig

    cfg = ParakeetTDTConfig.from_pretrained(base, local_files_only=True)
    model = ParakeetForTDT(cfg)
    sd = dense_state_dict(container, pins)
    missing, unexpected = model.load_state_dict(sd, strict=False)
    if missing or unexpected:
        raise RuntimeError(f"state dict mismatch: missing {missing[:5]} unexpected {unexpected[:5]}")
    model.eval()
    return model, cfg


def vocabulary_of(model, base: Path) -> list[str]:
    """Tokenizer symbols indexed by id; the final id is the blank (config.blank_token_id)."""
    from transformers import AutoTokenizer

    blank = model.config.blank_token_id
    tok = AutoTokenizer.from_pretrained(base, local_files_only=True)
    vocab = tok.get_vocab()
    ids = sorted(vocab.values())
    if ids != list(range(len(ids))) or ids[-1] != blank:
        raise RuntimeError(f"tokenizer ids are not contiguous ending at blank {blank}")
    symbols = [""] * len(vocab)
    for symbol, i in vocab.items():
        symbols[i] = symbol
    return symbols  # symbols[blank] is the blank token; written as <blk> for sherpa


class EncoderExportModule:
    """sherpa nemo_transducer encoder IO: x [B,C,T] -> encoder_out [B,D,T'], lengths.

    Includes HF's encoder_projector (1024 -> 640) so the graph emits the joint's
    encoder dim, exactly like NeMo's exported encoder. Submodules are registered
    through __init__ so the tracer emits initializers (not folded Constants),
    which is what lets the int8 quantizer stream the weights."""

    def __init__(self, model):
        import torch

        class Module(torch.nn.Module):
            subsampling = 8  # parakeet-tdt-0.6b-v3: conv stride 8 (k2-fsa recipe)

            def __init__(inner):
                super().__init__()
                inner.encoder = model.encoder
                inner.projector = model.encoder_projector

            def forward(inner, x, x_lens):
                features = x.transpose(1, 2)  # [B,T,C] for the HF encoder
                out = inner.encoder(features).last_hidden_state
                out = inner.projector(out)
                t = out.shape[1]
                lengths = ((x_lens.float() / inner.subsampling) - 1).ceil().long().clamp(min=1, max=t)
                return out.transpose(1, 2), lengths  # [B,D,T']

        self.module = Module()


class DecoderExportModule:
    """sherpa nemo_transducer decoder IO: targets, length, s0, s1 -> out, out_len, s0', s1'."""

    def __init__(self, model):
        import torch

        class Module(torch.nn.Module):
            def __init__(inner):
                super().__init__()
                inner.decoder = model.decoder

            def forward(inner, targets, targets_length, s0, s1):
                embedded = inner.decoder.embedding(targets.long())
                out, (h, c) = inner.decoder.lstm(embedded, (s0, s1))
                out = inner.decoder.decoder_projector(out)
                # sherpa feeds the decoder output straight into the joiner as
                # [B, hidden, U]; the prediction network computes [B, U, hidden].
                return out.transpose(1, 2), targets_length, h, c

        self.module = Module()


class JoinerExportModule:
    """sherpa nemo_transducer joiner IO: encoder_out [B,D,1], decoder_out [B,D,1] -> logits."""

    def __init__(self, model):
        import torch

        class Module(torch.nn.Module):
            def __init__(inner):
                super().__init__()
                inner.joint = model.joint

            def forward(inner, encoder_out, decoder_out):
                return inner.joint(decoder_hidden_states=decoder_out.transpose(1, 2),
                                   encoder_hidden_states=encoder_out.transpose(1, 2))

        self.module = Module()


def export_onnx(model, outdir: Path, base: Path, pins=PINNED):
    import torch
    from onnxruntime.quantization import QuantType, quantize_dynamic
    import onnx

    # Some parakeet weights live outside registered submodules; the tracer then
    # wants them as constants, which forbids gradients.
    for p in model.parameters():
        p.requires_grad_(False)

    symbols = vocabulary_of(model, base)
    blank = model.config.blank_token_id
    durations = list(getattr(model.config, "durations", None) or [])
    if not durations:
        raise RuntimeError("reference config carries no TDT durations; refusing a non-TDT export")
    # sherpa tokens.txt: joint vocabulary then <blk>; metadata vocab_size excludes the blank.
    (outdir / "tokens.txt").write_text(
        "".join(f"{s} {i}\n" for i, s in enumerate(symbols) if i != blank) + f"<blk> {blank}\n", encoding="utf-8")

    def add_meta(filename: str, meta: dict[str, str], external: bool = False):
        m = onnx.load(filename)
        while len(m.metadata_props):
            m.metadata_props.pop()
        for key, value in meta.items():
            e = m.metadata_props.add()
            e.key, e.value = key, str(value)
        if external:
            # The fp32 encoder exceeds the 2 GB protobuf limit; keep weights beside it.
            onnx.save(m, filename, save_as_external_data=True, all_tensors_to_one_file=True,
                      location=Path(filename).stem + ".weights")
        else:
            onnx.save(m, filename)

    meta = {
        "vocab_size": blank,
        "normalize_type": "per_feature",  # parakeet-tdt-0.6b-v3 preprocessor.cfg
        "pred_rnn_layers": model.config.num_decoder_layers,
        "pred_hidden": model.config.decoder_hidden_size,
        "subsampling_factor": 8,
        "model_type": "EncDecRNNTBPEModel",
        "version": "2",
        "model_author": "FermionResearch",
        "url": f"https://huggingface.co/{pins['repository']} (parakeet-tdt)",
        "comment": f"{pins['profile']} five-value weights expanded to dense fp32 and re-exported for sherpa nemo_transducer",
        "hearth_profile": pins["profile"],
        "hearth_source_revision": pins["revision"],
        "hearth_source_languages": ",".join(pins["languages"]),
        "feat_dim": 128,
    }

    with torch.no_grad():
        x = torch.randn(1, 128, 67, dtype=torch.float32)
        x_lens = torch.tensor([67], dtype=torch.int64)
        print("[export] tracing encoder...")
        torch.onnx.export(EncoderExportModule(model).module, (x, x_lens), str(outdir / "encoder.onnx"),
                          input_names=["x", "x_lens"], output_names=["encoder_out", "encoder_out_lens"],
                          dynamic_axes={"x": {0: "B", 2: "T"}, "x_lens": {0: "B"},
                                        "encoder_out": {0: "B", 2: "T"}, "encoder_out_lens": {0: "B"}},
                          opset_version=17, do_constant_folding=True, dynamo=False)

        targets = torch.zeros(1, 1, dtype=torch.int32)
        t_lens = torch.ones(1, dtype=torch.int32)
        s0 = torch.zeros(model.config.num_decoder_layers, 1, model.config.decoder_hidden_size)
        s1 = torch.zeros_like(s0)
        torch.onnx.export(DecoderExportModule(model).module, (targets, t_lens, s0, s1), str(outdir / "decoder.onnx"),
                          input_names=["targets", "targets_lens", "s0", "s1"],
                          output_names=["decoder_out", "decoder_out_lens", "s0_next", "s1_next"],
                          dynamic_axes={"targets": {0: "B", 1: "U"}, "targets_lens": {0: "B"},
                                        "decoder_out": {0: "B", 2: "U"}},
                          opset_version=17, dynamo=False)

        enc = torch.randn(1, model.config.decoder_hidden_size, 1)
        dec = torch.randn(1, model.config.decoder_hidden_size, 1)
        torch.onnx.export(JoinerExportModule(model).module, (enc, dec), str(outdir / "joiner.onnx"),
                          input_names=["encoder_out", "decoder_out"], output_names=["logits"],
                          dynamic_axes={"encoder_out": {0: "B", 2: "T"}, "decoder_out": {0: "B", 2: "U"}},
                          opset_version=17, dynamo=False)

    for name, weight_type in (("encoder", QuantType.QUInt8), ("decoder", QuantType.QInt8), ("joiner", QuantType.QInt8)):
        quantize_dynamic(model_input=str(outdir / f"{name}.onnx"),
                         model_output=str(outdir / f"{name}.int8.onnx"), weight_type=weight_type)
        print(f"[quant] {name}.int8.onnx: {(outdir / (name + '.int8.onnx')).stat().st_size} bytes")
    add_meta(str(outdir / "encoder.int8.onnx"), meta)
    # Keep only the installable artifacts; drop fp32 graphs and their scattered
    # external constant files so a later make-package pass hashes a clean tree.
    keep = {"encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"}
    for stale in outdir.iterdir():
        if stale.is_file() and stale.name not in keep:
            stale.unlink()

    expected = blank + 1 + len(durations)
    session = __import__("onnxruntime").InferenceSession(str(outdir / "joiner.int8.onnx"), providers=["CPUExecutionProvider"])
    actual = session.get_outputs()[0].shape[-1]
    if not isinstance(actual, int) or actual != expected:
        raise RuntimeError(f"joiner output {actual} != tokens({blank + 1}) + durations({len(durations)})")
    print(f"[4] exported encoder/decoder/joiner (+int8) and tokens.txt ({blank + 1} symbols incl <blk>, {len(durations)} durations)")


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
    if rate != 16000 or audio.shape[1] != 1:
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
    ap.add_argument("--profile", choices=sorted(PROFILES), default="phonon-2", help="explicit pinned source; defaults to original English-only Phonon-2")
    ap.add_argument("--archive", type=Path, default=None, help="local archive for the chosen profile (still hash-verified)")
    ap.add_argument("--verify", type=Path, default=None, help="16 kHz mono WAV decoded through the exported graphs")
    args = ap.parse_args()

    pins = PROFILES[args.profile]
    args.outdir.mkdir(parents=True, exist_ok=True)
    # Cache (pinned archive + container) stays outside the package directory:
    # make-package hashes every file under the directory it is given.
    package = args.outdir / "package"
    package.mkdir(exist_ok=True)
    container = obtain_container(args.archive, args.outdir, pins)
    base = obtain_base(args.outdir, pins)
    model, _cfg = load_reference_model(container, base, pins)
    export_onnx(model, package, base, pins)
    if args.verify:
        verify(package, args.verify)
    for name, (size, digest) in pins.get("source_files", {}).items():
        verified_download(f"https://huggingface.co/{pins['repository']}/resolve/{pins['revision']}/{name}",
                          package / name, size, digest)
    import importlib.metadata
    provenance = {
        "profile": args.profile, "repository": pins["repository"], "revision": pins["revision"],
        "archiveBytes": pins["archive_bytes"], "archiveSha256": pins["archive_sha256"],
        "containerBytes": pins["container_bytes"], "containerSha256": pins["container_sha256"],
        "baseRepository": pins["base_repo"], "baseRevision": BASE_REVISION,
        "languages": pins["languages"], "license": pins["license"],
        "exporterSha256": sha256_file(Path(__file__)),
        "versions": {name: importlib.metadata.version(name) for name in ("torch", "transformers", "onnx", "onnxruntime", "numpy")},
        "files": [{"path": file.name, "bytes": file.stat().st_size, "sha256": sha256_file(file)}
                  for file in sorted(package.iterdir()) if file.is_file()],
        "limitations": ["Host ONNX export; phone speed and multilingual accuracy are unmeasured.",
                        "Converted INT8 package size differs from the publisher's packed FSQ download.",
                        "Language selection is a source declaration, not forced decoding or reliable auto-language metadata."],
    }
    (args.outdir / "conversion-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(f"""
Next: package the export for Hearth import
  python3 scripts/speech/make-package.py {package} --profile {args.profile} \\
    --role model=tokens.txt --role frontend=joiner.int8.onnx \\
    --role encoder=encoder.int8.onnx --role decoder=decoder.int8.onnx
Then Import speech ZIP (or publish the directory pinned and add it to SpeechDownloads).
Weights: {pins['license']}.""")


if __name__ == "__main__":
    main()

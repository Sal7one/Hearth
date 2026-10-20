#!/usr/bin/env python3
"""Export the two pinned Jisr checkpoints without OPUS-specific pad removal.

Requires torch, transformers and tokenizers. CTranslate2's pinned Python conversion
sources are used directly; its optional Python inference extension is unnecessary.
Only verified safetensors and built-in Marian code are loaded.
"""
import argparse
import hashlib
import importlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import types
import zipfile

ROOT = Path(__file__).resolve().parents[3]
CT2_PIN = "8c8cab2a954810216a45d65c1b8d56839c846e5b"


def conversion_namespace(source):
    pin = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if pin != CT2_PIN:
        raise ValueError("Use the pinned CTranslate2 conversion source revision")
    if subprocess.run(["git", "-C", str(source), "diff", "--quiet", "HEAD", "--"]).returncode:
        raise ValueError("CTranslate2 conversion sources differ from the pinned revision")
    # These namespaces expose the real pure-Python converter, without importing
    # the unrelated compiled Python inference extension from __init__.py.
    for name, folder in [("ctranslate2", source / "python/ctranslate2"),
                         ("ctranslate2.converters", source / "python/ctranslate2/converters")]:
        package = types.ModuleType(name)
        package.__path__ = [str(folder)]
        sys.modules[name] = package
    return importlib.import_module("ctranslate2.converters.transformers")


def verify_source(directory, direction):
    card = next(m for m in json.loads((ROOT / "docs/handoff/models/translation.json").read_text())["models"] if "jisr" in m["id"])
    for part in card["components"]:
        if not part["filename"].startswith(direction + "/"):
            continue
        path = directory / part["remoteFilename"]
        if path.stat().st_size != part["bytes"] or hashlib.file_digest(path.open("rb"), "sha256").hexdigest() != part["sha256"]:
            raise ValueError(f"Jisr source integrity mismatch: {path.name}")


def package_notices(output, direction, precision):
    card = next(m for m in json.loads((ROOT / "docs/handoff/models/translation.json").read_text())["models"] if "jisr" in m["id"])
    original = next(p for p in card["components"] if p["filename"] == direction + "/model.safetensors")
    shutil.copyfile(ROOT / "LICENSE", output / "LICENSE")
    (output / "NOTICE").write_text(
        f'Jisr-MT 50M {direction} ({precision} conversion)\n'
        f'Original model: {original["repo"]}, revision {original["revision"]}\n'
        f'Source and model attribution: https://huggingface.co/{original["repo"]}\n'
        'Original model weights and tokenizer: Apache-2.0. See LICENSE.\n'
        f'Converted by Hearth using CTranslate2 source revision {CT2_PIN}.\n'
        'Original token IDs, learned decoder-start embedding and vocabulary are preserved.\n'
        'INT8 output can differ from FLOAT32; no publisher quality score is claimed for this conversion.\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ct2-source", required=True, type=Path)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--direction", required=True, choices=["en-ar", "ar-en"])
    parser.add_argument("--precision", choices=["float32", "int8"], default="int8")
    args = parser.parse_args()
    verify_source(args.source, args.direction)
    if args.output.exists():
        raise ValueError("Output must be a new directory")
    module = conversion_namespace(args.ct2_source)
    import torch
    from transformers import AutoTokenizer, MarianMTModel
    torch.set_num_threads(2)
    tokenizer = AutoTokenizer.from_pretrained(args.source, local_files_only=True, trust_remote_code=False)
    model = MarianMTModel.from_pretrained(args.source, local_files_only=True).eval()
    if model.config.pad_token_id != 0 or model.config.decoder_start_token_id != 0 or model.config.eos_token_id != 1:
        raise ValueError("Unexpected Jisr generation token IDs")

    class JisrLoader(module.BartLoader):
        def get_model_spec(self, model):
            model.config.normalize_before = False
            model.config.normalize_embedding = False
            # Jisr's pad=0 embedding is trained and nonzero. MarianMTLoader's
            # start_from_zero_embedding + last-vocab-row removal corrupts it.
            return super().get_model_spec(model)

        def set_config(self, config, model, tokenizer):
            config.bos_token = "<pad>"
            config.eos_token = "</s>"
            config.unk_token = "<unk>"
            config.decoder_start_token = "<pad>"
            config.add_source_bos = False
            config.add_source_eos = False

    with torch.inference_mode():
        spec = JisrLoader()(model, tokenizer)
        spec.validate()
        spec.optimize(quantization=args.precision)
        args.output.mkdir(parents=True)
        spec.save(str(args.output))
    shutil.copyfile(args.source / "tokenizer.json", args.output / "tokenizer.json")
    model_id = "ct2-jisr-50m-" + args.direction
    if args.precision == "int8": model_id += "-int8"
    (args.output / "hearth-translation.json").write_text(json.dumps({"schemaVersion": 1, "modelId": model_id}) + "\n")
    package_notices(args.output, args.direction, args.precision)
    inventory = []
    for path in sorted(args.output.iterdir()):
        inventory.append({"filename": path.name, "bytes": path.stat().st_size,
                          "sha256": hashlib.file_digest(path.open("rb"), "sha256").hexdigest()})
    print(json.dumps({"modelId": model_id, "precision": args.precision, "components": inventory}, indent=2))
    with zipfile.ZipFile(args.output.with_suffix(".zip"), "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(args.output.iterdir()):
            archive.write(path, path.name)


if __name__ == "__main__":
    main()

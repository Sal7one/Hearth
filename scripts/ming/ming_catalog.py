#!/usr/bin/env python3
"""Offline Ming bundle verification. Does not import a model or download anything."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re

CATALOG = Path(__file__).resolve().parents[2] / "docs/models/ming-3.0.json"


def load_catalog(path=CATALOG):
    catalog = json.loads(Path(path).read_text(encoding="utf-8"))
    if catalog.get("schemaVersion") != 1 or len(catalog.get("models", [])) != 5:
        raise ValueError("Expected the five pinned Ming-3.0 checkpoints")
    seen = set()
    for model in catalog["models"]:
        if model["id"] in seen or not re.fullmatch(r"[a-f0-9]{40}", model["revision"]):
            raise ValueError("Duplicate model identity or invalid revision")
        seen.add(model["id"])
        if model["androidLocal"] or "ar" in model["languages"]:
            raise ValueError("Ming-3.0 has no verified local Android runtime or Arabic coverage")
        files = {}
        for file in model["files"]:
            name = PurePosixPath(file["path"])
            if (name.is_absolute() or ".." in name.parts or "\\" in str(name)
                    or file["path"] in files or file["bytes"] < 0
                    or not re.fullmatch(r"[a-f0-9]{64}", file["sha256"])):
                raise ValueError("Unsafe, duplicated or unpinned model component")
            expected = f'https://huggingface.co/{model["id"]}/resolve/{model["revision"]}/'
            if not file["url"].startswith(expected):
                raise ValueError("Component URL is not pinned to its model revision")
            files[file["path"]] = file
        for profile in model["profiles"].values():
            names = profile["files"]
            if (not names or len(names) != len(set(names)) or not set(names) <= files.keys()
                    or profile["bytes"] != sum(files[n]["bytes"] for n in names)):
                raise ValueError("Incomplete or inconsistent bundle profile")
    return catalog


def verify_bundle(model_id, directory, profile="speech", catalog_path=CATALOG):
    models = load_catalog(catalog_path)["models"]
    model = next((m for m in models if m["id"] == model_id), None)
    if model is None or profile not in model["profiles"]:
        raise ValueError("Unknown Ming model or bundle profile")
    root = Path(directory).resolve(strict=True)
    for file in model["files"]:
        if file["path"] not in model["profiles"][profile]["files"]:
            continue
        path = root / file["path"]
        if not path.is_file() or path.stat().st_size != file["bytes"]:
            raise ValueError(f'Missing or wrong-size Ming component: {file["path"]}')
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != file["sha256"]:
            raise ValueError(f'Ming integrity mismatch: {file["path"]}')
    return model


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", nargs="?")
    parser.add_argument("directory", nargs="?")
    parser.add_argument("--profile", default="speech")
    args = parser.parse_args()
    if args.model and args.directory:
        model = verify_bundle(args.model, args.directory, args.profile)
        print(f'Verified {model["id"]} at {model["revision"]} ({args.profile})')
    elif args.model or args.directory:
        parser.error("Supply both model ID and local directory")
    else:
        for model in load_catalog()["models"]:
            print(model["id"], {name: p["bytes"] for name, p in model["profiles"].items()})

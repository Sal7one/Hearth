#!/usr/bin/env python3
"""Hash an existing, trusted local model directory; no downloads or model conversion.
Example:
 make-package.py DIR --profile qwen3-asr-0.6b --role frontend=conv_frontend.onnx \
   --role encoder=encoder.int8.onnx --role decoder=decoder.int8.onnx --role tokenizer=tokenizer
"""
import argparse
import hashlib
import json
import os
from pathlib import Path

PROFILES = {"moonshine-tiny-en-v2", "moonshine-base-en-v2", "qwen3-asr-0.6b", "qwen3-asr-1.7b", "nemotron-3.5-asr-0.6b"}
MANIFEST = "hearth-speech.json"

def create_manifest(root, profile, roles):
    if profile not in PROFILES:
        raise ValueError("Unsupported profile")
    expected = {"model", "encoder", "decoder"} if profile.startswith("moonshine") else {"model"} if profile.startswith("nemotron") else {"frontend", "encoder", "decoder", "tokenizer"}
    if set(roles) != expected:
        raise ValueError(f"Required roles: {sorted(expected)}")
    if not root.is_dir() or root.is_symlink():
        raise ValueError("Model root must be a real directory")
    files = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError(f"Symbolic link: {path}")
        if not path.is_file() or path.name == MANIFEST and path.parent == root:
            continue
        size = path.stat().st_size
        if not 0 < size <= 8 * 1024**3:
            raise ValueError(f"Empty or oversized asset: {path}")
        h = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                h.update(chunk)
        files.append({"path": path.relative_to(root).as_posix(), "bytes": size, "sha256": h.hexdigest()})
    declared = {f["path"] for f in files}
    for role, relative in roles.items():
        parts = relative.split("/")
        if any(p in ("", ".", "..") for p in parts) or "\\" in relative or ":" in relative or "\0" in relative:
            raise ValueError(f"Invalid role path: {relative}")
        required = [relative] if role != "tokenizer" else [f"{relative}/{n}" for n in ("vocab.json", "merges.txt", "tokenizer_config.json")]
        if not set(required) <= declared:
            raise ValueError(f"Missing assets for role {role}: {required}")
    if not 1 <= len(files) <= 1000 or sum(f["bytes"] for f in files) > 16 * 1024**3:
        raise ValueError("Package exceeds asset count/size limits")
    return {"schemaVersion": 1, "profile": profile, "roles": roles, "files": files}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--profile", required=True, choices=sorted(PROFILES))
    parser.add_argument("--role", action="append", required=True, metavar="ROLE=RELATIVE_PATH")
    args = parser.parse_args()
    pairs = [r.split("=", 1) for r in args.role]
    if any(len(r) != 2 for r in pairs) or len(dict(pairs)) != len(pairs):
        parser.error("Roles must be unique ROLE=RELATIVE_PATH entries")
    manifest = create_manifest(args.directory, args.profile, dict(pairs))
    temporary = args.directory / (MANIFEST + ".partial")
    # Hashing happens before staging this manifest. Replacement is atomic.
    created = False
    try:
        with temporary.open("x", encoding="utf-8") as out:
            created = True
            json.dump(manifest, out, indent=2, ensure_ascii=False)
            out.write("\n"); out.flush(); os.fsync(out.fileno())
        temporary.replace(args.directory / MANIFEST)
    finally:
        if created:
            temporary.unlink(missing_ok=True)
    print(args.directory / MANIFEST)

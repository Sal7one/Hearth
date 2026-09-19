#!/usr/bin/env python3
"""Verify the bundled Manga vocabulary against its pinned publisher file (network)."""
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

root = Path(__file__).resolve().parents[2]
assets = root / "common-jni/src/main/assets/ocr"
record = json.loads((assets / "manga-provenance.json").read_text())
url = f"https://huggingface.co/{record['repo']}/resolve/{record['revision']}/{record['rfilename']}"
with urlopen(url, timeout=30) as response:
    original = response.read(100_001)
assert len(original) == record["size"], "Unexpected vocabulary size"
assert hashlib.sha256(original).hexdigest() == record["source_sha256"], "Publisher vocabulary changed"
tokens = original.decode("utf-8").splitlines()
assert len(tokens) == record["classes"] == 6144
packaged = (assets / "manga.json").read_bytes()
assert json.loads(packaged) == tokens, "Vocabulary token order differs from publisher"
assert hashlib.sha256(packaged).hexdigest() == record["dictionary_sha256"]
print("Manga vocabulary: pinned source, 6144 token IDs and packaged hash PASS")

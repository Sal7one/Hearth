#!/usr/bin/env python3
"""Strip generated runtime copies, verify their ABI/dependencies/page alignment, record hashes."""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

lib_dir, tool_dir = map(Path, sys.argv[1:3])
allowed = {"libc++_shared.so", "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
records = []
for filename in ("libhearth_qwen.so", "libhearth_nemotron.so"):
    file = lib_dir / filename
    if not file.is_file():
        continue
    subprocess.run([str(tool_dir / "llvm-strip"), "--strip-unneeded", str(file)], check=True)
    def read(*args):
        return subprocess.check_output([str(tool_dir / "llvm-readelf"), *args, str(file)], text=True)
    headers = read("-lW")
    alignments = [int(line.split()[-1], 16) for line in headers.splitlines() if line.strip().startswith("LOAD ")]
    if not alignments or min(alignments) < 16384:
        raise RuntimeError(f"{filename}: not 16KB aligned")
    header = read("-h")
    if "AArch64" not in header:
        raise RuntimeError(f"{filename}: expected arm64")
    dynamic = read("-d")
    needed = re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic)
    if set(needed) - allowed:
        raise RuntimeError(f"{filename}: unexpected dynamic dependencies: {needed}")
    soname = re.findall(r"\(SONAME\).*?\[(.*?)\]", dynamic)
    if soname != [filename]:
        raise RuntimeError(f"{filename}: bad SONAME: {soname}")
    symbols = subprocess.check_output([str(tool_dir / "llvm-nm"), "-D", "--defined-only", str(file)], text=True)
    exported = [line.split()[-1] for line in symbols.splitlines() if line.split()]
    if exported != ["hearth_speech_backend_v1"]:
        raise RuntimeError(f"{filename}: unexpected exports: {exported[:20]}")
    records.append({"file": filename, "bytes": file.stat().st_size, "sha256": hashlib.sha256(file.read_bytes()).hexdigest(), "minPageAlignment": min(alignments), "dependencies": needed})
if not records:
    raise RuntimeError("No Android speech runtimes were built")
output = lib_dir.parent / "runtime-artifacts.json"
output.write_text(json.dumps(records, indent=2) + "\n")
print(output.read_text())

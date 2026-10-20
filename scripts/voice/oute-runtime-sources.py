#!/usr/bin/env python3
"""Fingerprint the isolated Oute runtime recipe and public native contract."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[2]
files=['scripts/voice/build-oute-runtime.sh','scripts/voice/oute-runtime-sources.py','scripts/voice/oute/CMakeLists.txt','scripts/voice/oute/exports.map','scripts/voice/oute/prepare-source.py','scripts/voice/oute/runtime.cpp','scripts/voice/oute/oute_sampling.h','hearth-tts-oute/src/main/cpp/vendor/hearth_oute.h']
print(json.dumps({name:hashlib.sha256((root/name).read_bytes()).hexdigest() for name in files},sort_keys=True,indent=2))

#!/usr/bin/env python3
"""Source snapshot binding the optional Magpie CPU build recipe and C ABI contract."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[2]
files=['scripts/voice/build-magpie-runtime.sh','scripts/voice/magpie-runtime-sources.py','scripts/voice/nemo-tts-inject.cmake','scripts/voice/magpie.map','hearth-tts-magpie/src/main/cpp/vendor/tts.h']
print(json.dumps({name:hashlib.sha256((root/name).read_bytes()).hexdigest() for name in files},sort_keys=True,indent=2))

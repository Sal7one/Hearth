#!/usr/bin/env python3
"""Fingerprint the exact optional OmniVoice build recipe and public native contract."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[2]
files=['scripts/voice/build-omnivoice-runtime.sh','scripts/voice/omnivoice-runtime-sources.py','scripts/voice/omnivoice-runtime.cmake','scripts/voice/omnivoice.map','hearth-tts-omnivoice/src/main/cpp/vendor/omnivoice.h']
print(json.dumps({n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in files},sort_keys=True,indent=2))

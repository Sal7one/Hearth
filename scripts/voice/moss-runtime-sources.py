#!/usr/bin/env python3
"""Bind the real MOSS ONNX orchestration and exact build inputs to the staged runtime."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[2]
files=['scripts/voice/build-moss-runtime.sh','scripts/voice/moss-runtime-sources.py','scripts/voice/moss/CMakeLists.txt','hearth-tts-moss/src/main/cpp/moss.cpp','hearth-tts-moss/src/main/cpp/pcm_bounds.h','hearth-tts-moss/src/main/cpp/vendor/moss.h','hearth-tts-moss/src/main/cpp/vendor/json.hpp','hearth-tts-moss/src/main/cpp/runtime.map','common-jni/src/main/cpp/onnx/include/onnxruntime/onnxruntime_c_api.h']
print(json.dumps({n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in files},sort_keys=True,indent=2))

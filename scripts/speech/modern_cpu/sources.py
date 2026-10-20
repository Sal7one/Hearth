#!/usr/bin/env python3
"""Exact Hearth inputs for one independently built modern CPU speech adapter."""
import hashlib,json,sys
from pathlib import Path
root=Path(__file__).resolve().parents[3]
backend=sys.argv[1]
assert backend in ('owsm','audar')
paths=[f'common-jni/src/main/cpp/speech/modern_cpu/{backend}_backend.cpp',
 f'common-jni/src/main/cpp/speech/modern_cpu/{backend}.map','common-jni/src/main/cpp/speech/modern_cpu/versions.h',
 'common-jni/src/main/cpp/speech/backend_abi.h','common-jni/src/main/cpp/speech/utterance_segmenter.h',
 'common-jni/src/main/cpp/speech/backends/backend_guard.h',
 'common-jni/src/main/cpp/common/audio_gate.h',
 f'scripts/speech/modern_cpu/{backend}.cmake','scripts/speech/modern_cpu/build.sh','scripts/speech/modern_cpu/sources.py']
paths += ['scripts/speech/modern_cpu/prepare-owsm.py'] if backend=='owsm' else ['common-jni/src/main/cpp/speech/modern_cpu/audar_protocol.h']
print(json.dumps({p:hashlib.sha256((root/p).read_bytes()).hexdigest() for p in sorted(paths)},indent=2))

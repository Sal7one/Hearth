#!/usr/bin/env python3
"""Exact inputs compiled into the independent multilingual native STT plugins."""
import hashlib
from pathlib import Path
import json
REPO = Path(__file__).resolve().parents[2]
def snapshot():
    paths = list((REPO / 'common-jni/src/main/cpp/speech/expansion').glob('*'))
    paths += [REPO / 'common-jni/src/main/cpp/speech' / n for n in ('backend_abi.h', 'utterance_segmenter.h')]
    paths += [REPO / 'common-jni/src/main/cpp/speech/backends/backend_guard.h']
    paths += [REPO / 'common-jni/src/main/cpp/common' / n for n in ('audio_gate.h', 'dsp_core.h', 'numeric_utils.h')]
    paths += [REPO / 'scripts/speech' / n for n in ('transcribe-cpp.cmake', 'build-expansion-runtimes.sh', 'expansion-runtime-sources.py')]
    return {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths) if p.is_file()}
if __name__ == '__main__': print(json.dumps(snapshot(), indent=2))
